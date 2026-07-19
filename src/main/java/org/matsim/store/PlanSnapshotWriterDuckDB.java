package org.matsim.store;

import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.listener.BeforeMobsimListener;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.utils.misc.OptionalTime;
import org.matsim.utils.objectattributes.attributable.Attributable;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/**
 * Plan-memory snapshot writer using ONLY the DuckDB JDBC driver (single fat jar,
 * no hadoop-common, no parquet-mr, no Arrow).
 *
 * Grain is ONE ROW PER PLAN: personId/planIdx/selected/score are flat columns, and
 * a person's choice set is reconstructed with {@code GROUP BY personId}. Only the
 * intra-plan structure stays nested — activities and legs are LIST&lt;STRUCT&gt;, and a
 * route carries its ordered link ids as a leaf LIST&lt;VARCHAR&gt; (a route just is a
 * sequence of links). Link ids are inlined as strings; there is no separate link
 * dictionary.
 *
 * The snapshot carries NO iteration field: a set of plans (initial, in-memory, or
 * experienced) has no intrinsic iteration. When the {@link IterationEndsListener}
 * writes one, the iteration lives in the {@code ITERS/it.NNN/} path; recover it in
 * analytics from the filename (read_parquet(..., filename := true)). See
 * src/main/resources/plans.avsc for the intended contract (hand-maintained; nothing
 * generates/validates against it — {@code PlanSnapshotWriterDuckDBTest} is the drift guard).
 *
 * Because the DuckDB Java Appender cleanly appends only SCALAR values (not nested
 * LIST&lt;STRUCT&lt;...&gt;&gt; cells), we don't build the nesting in Java. Instead:
 *
 *   1. append flat rows into staging tables (scalars only) via the fast Appender
 *   2. let DuckDB assemble the nested structure in SQL (list()/struct_pack)
 *   3. COPY the assembled result to the target parquet file
 *
 * Fires at BeforeMobsim on exactly the iterations MATSim dumps the regular plans XML —
 * mirroring {@code PlansDumpingImpl}'s schedule ({@link ControllerConfigGroup#getWritePlansInterval()}
 * plus every early iteration up to {@link ControllerConfigGroup#getWritePlansUntilIteration()}) — so a
 * {@code plans.parquet} lands next to each {@code NNN.plans.xml.gz}, capturing the same plan state.
 */
public final class PlanSnapshotWriterDuckDB implements BeforeMobsimListener {

	public PlanSnapshotWriterDuckDB() {
	}

	@Override
	public void notifyBeforeMobsim(BeforeMobsimEvent event) {
		int iteration = event.getIteration();
		ControllerConfigGroup cfg = event.getServices().getConfig().controller();
		if (!writesOnIteration(iteration, cfg.getWritePlansInterval(), cfg.getWritePlansUntilIteration())) {
			return;
		}
		Population population = event.getServices().getScenario().getPopulation();
		String outPath = event.getServices().getControllerIO().getIterationFilename(iteration, "plans.parquet");
		write(population, outPath);
	}

	/**
	 * Mirrors {@code PlansDumpingImpl}: true on the iterations the regular plans XML is dumped —
	 * every {@code interval} iterations (interval &lt;= 0 disables all writing), plus every early
	 * iteration up to and including {@code writePlansUntilIteration}.
	 */
	static boolean writesOnIteration(int iteration, int interval, int writePlansUntilIteration) {
		boolean writingAtAll = interval > 0;
		boolean regular = writingAtAll && iteration > 0 && iteration % interval == 0;
		boolean early = iteration <= writePlansUntilIteration;
		return writingAtAll && (regular || early);
	}

	/** Snapshot {@code population} to a single parquet file at {@code outPath}. */
	public void write(Population population, String outPath) {
		// In-memory DuckDB; nothing persisted except the COPY output.
		try (DuckDBConnection conn = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {
			createStagingTables(conn);
			stagePopulation(conn, population);
			assembleAndCopy(conn, outPath);
		} catch (SQLException e) {
			throw new RuntimeException("DuckDB snapshot failed for " + outPath, e);
		}
	}

	private void createStagingTables(DuckDBConnection conn) throws SQLException {
		try (Statement st = conn.createStatement()) {
			st.execute("CREATE TABLE stg_plan     (plan_id BIGINT, person_id VARCHAR, plan_idx INT, score DOUBLE, selected BOOLEAN)");
			st.execute("CREATE TABLE stg_act      (plan_id BIGINT, sequence INT, act_type VARCHAR, link VARCHAR, " +
				"start_time DOUBLE, end_time DOUBLE, max_dur DOUBLE)");
			st.execute("CREATE TABLE stg_leg      (plan_id BIGINT, leg_id BIGINT, sequence INT, mode VARCHAR, routing_mode VARCHAR, trav_time DOUBLE, " +
				"route_type VARCHAR, distance DOUBLE, route_trav_time DOUBLE, start_link VARCHAR, end_link VARCHAR)");
			st.execute("CREATE TABLE stg_routelink(leg_id BIGINT, ord INT, link VARCHAR)");
			// generic MATSim entity attributes (getAttributes()) captured as name->string, one row per attribute
			st.execute("CREATE TABLE stg_person_attr(person_id VARCHAR, attr_key VARCHAR, attr_value VARCHAR)");
			st.execute("CREATE TABLE stg_plan_attr  (plan_id BIGINT, attr_key VARCHAR, attr_value VARCHAR)");
			st.execute("CREATE TABLE stg_act_attr   (plan_id BIGINT, sequence INT, attr_key VARCHAR, attr_value VARCHAR)");
			st.execute("CREATE TABLE stg_leg_attr   (leg_id BIGINT, attr_key VARCHAR, attr_value VARCHAR)");
		}
	}

	private void stagePopulation(DuckDBConnection conn, Population population) throws SQLException {
		long planId = 0, legId = 0;

		// One Appender per staging table. Appender only ever sees scalars.
		try (DuckDBAppender plan = conn.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "stg_plan");
			 DuckDBAppender act = conn.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "stg_act");
			 DuckDBAppender leg = conn.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "stg_leg");
			 DuckDBAppender routelink = conn.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "stg_routelink");
			 DuckDBAppender personAttr = conn.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "stg_person_attr");
			 DuckDBAppender planAttr = conn.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "stg_plan_attr");
			 DuckDBAppender actAttr = conn.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "stg_act_attr");
			 DuckDBAppender legAttr = conn.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "stg_leg_attr")) {

			for (Person p : population.getPersons().values()) {
				String personId = p.getId().toString();
				Plan selected = p.getSelectedPlan();

				stageAttrs(p, (k, v) -> { personAttr.beginRow(); personAttr.append(personId); personAttr.append(k); personAttr.append(v); personAttr.endRow(); });

				int planIdx = 0;
				for (Plan pl : p.getPlans()) {
					long thisPlan = planId++;
					plan.beginRow();
					plan.append(thisPlan);
					plan.append(personId);
					plan.append(planIdx++);
					plan.append(pl.getScore()); // append(Double) maps null -> NULL (pre-scoring)
					plan.append(pl == selected);
					plan.endRow();

					stageAttrs(pl, (k, v) -> { planAttr.beginRow(); planAttr.append(thisPlan); planAttr.append(k); planAttr.append(v); planAttr.endRow(); });

					int seq = 0;
					for (PlanElement pe : pl.getPlanElements()) {
						int thisSeq = seq;
						if (pe instanceof Activity a) {
							act.beginRow();
							act.append(thisPlan);
							act.append(seq);
							act.append(a.getType());
							appendLink(act, a.getLinkId());
							appendOptionalTime(act, a.getStartTime());
							appendOptionalTime(act, a.getEndTime());
							appendOptionalTime(act, a.getMaximumDuration());
							act.endRow();
							stageAttrs(a, (k, v) -> { actAttr.beginRow(); actAttr.append(thisPlan); actAttr.append(thisSeq); actAttr.append(k); actAttr.append(v); actAttr.endRow(); });
						} else if (pe instanceof Leg lg) {
							long thisLeg = legId++;
							Route r = lg.getRoute();
							leg.beginRow();
							leg.append(thisPlan);
							leg.append(thisLeg);
							leg.append(seq);
							leg.append(lg.getMode());
							leg.append(lg.getRoutingMode()); // dedicated field, NOT in getAttributes(); null if unset
							appendOptionalTime(leg, lg.getTravelTime());
							// route attributes for ALL legs, teleported or network
							leg.append(r != null ? r.getRouteType() : null);
							appendRouteDistance(leg, r);
							if (r != null) appendOptionalTime(leg, r.getTravelTime()); else leg.appendNull();
							appendLink(leg, r != null ? r.getStartLinkId() : null);
							appendLink(leg, r != null ? r.getEndLinkId() : null);
							leg.endRow();
							stageAttrs(lg, (k, v) -> { legAttr.beginRow(); legAttr.append(thisLeg); legAttr.append(k); legAttr.append(v); legAttr.endRow(); });

							// route link sequence: only for NetworkRoute; teleported -> none
							if (r instanceof NetworkRoute nr) {
								int ord = 0;
								ord = appendRouteLink(routelink, thisLeg, ord, nr.getStartLinkId());
								for (Id<Link> l : nr.getLinkIds()) {
									ord = appendRouteLink(routelink, thisLeg, ord, l);
								}
								appendRouteLink(routelink, thisLeg, ord, nr.getEndLinkId());
							}
						}
						seq++;
					}
				}
			}
		}
	}

	/** Functional sink for one (key, value) attribute row; lets the walk keep the per-table id columns local. */
	@FunctionalInterface
	private interface AttrSink { void put(String key, String value) throws SQLException; }

	/** Stream an entity's generic MATSim attributes (name -> String.valueOf(value)) into {@code sink}. */
	private static void stageAttrs(Attributable entity, AttrSink sink) throws SQLException {
		for (Map.Entry<String, Object> e : entity.getAttributes().getAsMap().entrySet()) {
			sink.put(e.getKey(), String.valueOf(e.getValue()));
		}
	}

	/** SQL that folds flat staging into the plan-grain nested schema and COPYs to parquet. */
	private void assembleAndCopy(DuckDBConnection conn, String outPath) throws SQLException {
		String assembly = """
			WITH leg_routes AS (
			    SELECT leg_id, list(link ORDER BY ord) AS links
			    FROM stg_routelink GROUP BY leg_id
			),
			person_attrs AS (
			    SELECT person_id, map_from_entries(list(struct_pack(k := attr_key, v := attr_value))) AS attributes
			    FROM stg_person_attr GROUP BY person_id
			),
			plan_attrs AS (
			    SELECT plan_id, map_from_entries(list(struct_pack(k := attr_key, v := attr_value))) AS attributes
			    FROM stg_plan_attr GROUP BY plan_id
			),
			act_attrs AS (
			    SELECT plan_id, sequence, map_from_entries(list(struct_pack(k := attr_key, v := attr_value))) AS attributes
			    FROM stg_act_attr GROUP BY plan_id, sequence
			),
			leg_attrs AS (
			    SELECT leg_id, map_from_entries(list(struct_pack(k := attr_key, v := attr_value))) AS attributes
			    FROM stg_leg_attr GROUP BY leg_id
			),
			legs AS (
			    SELECT l.plan_id,
			           list(struct_pack(
			               sequence := l.sequence, mode := l.mode, routingMode := l.routing_mode, travTime := l.trav_time,
			               route := struct_pack(
			                   routeType := l.route_type, distance := l.distance,
			                   travTime := l.route_trav_time,
			                   startLink := l.start_link, endLink := l.end_link,
			                   links := COALESCE(r.links, [])
			               ),
			               attributes := la.attributes
			           ) ORDER BY l.sequence) AS legs
			    FROM stg_leg l
			    LEFT JOIN leg_routes r USING (leg_id)
			    LEFT JOIN leg_attrs la USING (leg_id)
			    GROUP BY l.plan_id
			),
			acts AS (
			    SELECT a.plan_id,
			           list(struct_pack(
			               sequence := a.sequence, actType := a.act_type, link := a.link,
			               startTime := a.start_time, endTime := a.end_time, maxDuration := a.max_dur,
			               attributes := aa.attributes
			           ) ORDER BY a.sequence) AS activities
			    FROM stg_act a
			    LEFT JOIN act_attrs aa USING (plan_id, sequence)
			    GROUP BY a.plan_id
			)
			SELECT p.person_id AS personId, p.plan_idx AS planIdx,
			       p.selected, p.score,
			       pa.attributes  AS personAttributes,
			       pla.attributes AS planAttributes,
			       COALESCE(a.activities, []) AS activities,
			       COALESCE(g.legs, []) AS legs
			FROM stg_plan p
			LEFT JOIN acts a USING (plan_id)
			LEFT JOIN legs g USING (plan_id)
			LEFT JOIN plan_attrs pla USING (plan_id)
			LEFT JOIN person_attrs pa ON pa.person_id = p.person_id
			ORDER BY p.person_id, p.plan_idx
			""";

		String copy = "COPY (" + assembly + ") TO '" + outPath.replace("'", "''") +
			"' (FORMAT parquet, COMPRESSION zstd)";
		try (Statement st = conn.createStatement()) {
			st.execute(copy);
		}
	}

	private static void appendOptionalTime(DuckDBAppender ap, OptionalTime t) throws SQLException {
		if (t.isDefined()) ap.append(t.seconds()); else ap.appendNull();
	}

	private static void appendLink(DuckDBAppender ap, Id<Link> linkId) throws SQLException {
		ap.append(linkId != null ? linkId.toString() : null); // append(String) maps null -> NULL
	}

	private static int appendRouteLink(DuckDBAppender routelink, long legId, int ord, Id<Link> linkId) throws SQLException {
		routelink.beginRow();
		routelink.append(legId);
		routelink.append(ord);
		appendLink(routelink, linkId);
		routelink.endRow();
		return ord + 1;
	}

	private static void appendRouteDistance(DuckDBAppender ap, Route r) throws SQLException {
		if (r == null) { ap.appendNull(); return; }
		double d = r.getDistance();
		if (Double.isNaN(d)) ap.appendNull(); else ap.append(d);   // MATSim uses NaN for undefined
	}
}
