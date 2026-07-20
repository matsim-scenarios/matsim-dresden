package org.matsim.store;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.population.routes.RouteUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Null-handling and schema assertions for {@link PlanSnapshotWriter}, plus a content
 * cross-check against {@link PlanSnapshotWriterDuckDB}.
 *
 * The two writers no longer share a schema: this one writes ONE ordered {@code elements[]} array
 * per plan, the DuckDB one still writes the older {@code activities[]} + {@code legs[]} split with a
 * synthetic {@code sequence} column. So the cross-check unrolls both to the same plan-element grain
 * and asserts the symmetric difference is empty — i.e. the regrouping is pure repackaging and loses
 * nothing. That makes the older writer the drift guard for the newer one.
 */
class PlanSnapshotWriterTest {

	/** One person: a pre-scoring plan with a teleported leg, and a scored plan with a network route. */
	private static Population fixture() {
		Population pop = PopulationUtils.createPopulation(org.matsim.core.config.ConfigUtils.createConfig());
		PopulationFactory pf = pop.getFactory();

		Person person = pf.createPerson(Id.createPersonId("A"));
		person.getAttributes().putAttribute("subpopulation", "person");

		Plan p0 = pf.createPlan();
		Activity home = pf.createActivityFromLinkId("home", Id.createLinkId("1"));
		home.setMaximumDuration(3600.);
		home.getAttributes().putAttribute("typicalDuration", 3600.0);
		p0.addActivity(home);
		Leg teleported = pf.createLeg("pt");
		teleported.setRoute(RouteUtils.createGenericRouteImpl(Id.createLinkId("1"), Id.createLinkId("2")));
		p0.addLeg(teleported);
		Activity work = pf.createActivityFromLinkId("work", Id.createLinkId("2"));
		work.setEndTime(9 * 3600.);
		p0.addActivity(work);
		person.addPlan(p0);
		person.setSelectedPlan(p0);

		Plan p1 = pf.createPlan();
		Activity h2 = pf.createActivityFromLinkId("home", Id.createLinkId("2"));
		h2.setEndTime(8 * 3600.);
		p1.addActivity(h2);
		Leg carLeg = pf.createLeg("car");
		NetworkRoute nr = RouteUtils.createLinkNetworkRouteImpl(
			Id.createLinkId("2"), List.of(Id.createLinkId("3")), Id.createLinkId("4"));
		nr.setDistance(1234.5);
		nr.setTravelTime(600.);
		carLeg.setRoute(nr);
		carLeg.setTravelTime(600.);
		carLeg.setRoutingMode("car");
		carLeg.getAttributes().putAttribute("totalRouteCost", 12.5);
		p1.addLeg(carLeg);
		Activity w2 = pf.createActivityFromLinkId("work", Id.createLinkId("4"));
		p1.addActivity(w2);
		p1.setScore(-3.5);
		person.addPlan(p1);

		pop.addPerson(person);
		return pop;
	}

	private static String readParquet(Path p) {
		return "read_parquet('" + p.toString().replace("'", "''") + "')";
	}

	@Test
	void nullsSurviveRoundTrip() throws Exception {
		Path out = Files.createTempFile("plansnap-avro", ".parquet");

		new PlanSnapshotWriter().write(fixture(), out.toString());

		assertTrue(Files.size(out) > 0, "parquet not written");

		String pq = readParquet(out);
		try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
			 Statement st = conn.createStatement()) {

			try (ResultSet rs = st.executeQuery(
					"SELECT count(*) FROM " + pq + " WHERE personId = 'A'")) {
				assertTrue(rs.next());
				assertEquals(2, rs.getInt(1), "expected one row per plan");
			}

			// activities and legs live in ONE ordered array, interleaved as in the XML
			try (ResultSet rs = st.executeQuery(
					"SELECT list_transform(elements, e -> e.kind) FROM " + pq + " WHERE personId = 'A' AND planIdx = 0")) {
				assertTrue(rs.next());
				assertEquals("[activity, leg, activity]", rs.getString(1), "elements must keep plan order, both kinds in one array");
			}

			// planIdx 0 = selected pre-scoring plan: score NULL, first activity has NULL start/end time but a
			// defined maxDuration; teleported leg -> travTime / route.distance / route.travTime all NULL.
			try (ResultSet rs = st.executeQuery(
					"SELECT score, selected, elements[1].startTime, elements[1].endTime, " +
					"       elements[1].maxDuration, elements[1].link, elements[1].actType, " +
					"       elements[2].travTime, elements[2].route.distance, elements[2].route.travTime, " +
					"       elements[2].route.links, elements[2].mode, " +
					"       personAttributes['subpopulation'], elements[1].attributes['typicalDuration'], " +
					"       elements[1].mode, elements[2].actType " +
					"FROM " + pq + " WHERE personId = 'A' AND planIdx = 0")) {
				assertTrue(rs.next());
				rs.getObject(1); assertTrue(rs.wasNull(), "pre-scoring score should be SQL NULL");
				assertTrue(rs.getBoolean(2), "plan 0 should be marked selected");
				rs.getObject(3); assertTrue(rs.wasNull(), "undefined activity start time should be SQL NULL");
				rs.getObject(4); assertTrue(rs.wasNull(), "undefined activity end time should be SQL NULL");
				assertEquals(3600., rs.getDouble(5), 1e-9, "activity maxDuration should round-trip");
				assertEquals("1", rs.getString(6), "activity link id should be inlined as a string");
				assertEquals("home", rs.getString(7), "activity type");
				rs.getObject(8); assertTrue(rs.wasNull(), "undefined leg travel time should be SQL NULL");
				rs.getObject(9); assertTrue(rs.wasNull(), "NaN route distance should be SQL NULL");
				rs.getObject(10); assertTrue(rs.wasNull(), "undefined route travel time should be SQL NULL");
				assertEquals("[]", rs.getString(11), "teleported leg should have no route links");
				assertEquals("pt", rs.getString(12), "leg mode");
				assertEquals("person", rs.getString(13), "person attribute should surface in personAttributes map");
				assertEquals("3600.0", rs.getString(14), "activity attribute should surface in the element's attributes map");
				rs.getObject(15); assertTrue(rs.wasNull(), "an activity must carry no leg fields");
				rs.getObject(16); assertTrue(rs.wasNull(), "a leg must carry no activity fields");
			}

			// planIdx 1 = fully populated network-routed plan: non-null side + inlined link sequence.
			try (ResultSet rs = st.executeQuery(
					"SELECT score, selected, elements[2].route.distance, elements[2].route.travTime, " +
					"       elements[2].route.startLink, elements[2].route.endLink, " +
					"       array_to_string(elements[2].route.links, ','), " +
					"       elements[2].routingMode, elements[2].attributes['totalRouteCost'], " +
					"       elements[1].route " +
					"FROM " + pq + " WHERE personId = 'A' AND planIdx = 1")) {
				assertTrue(rs.next());
				assertEquals(-3.5, rs.getDouble(1), 1e-9);
				assertFalse(rs.getBoolean(2), "plan 1 should not be selected");
				assertEquals(1234.5, rs.getDouble(3), 1e-9);
				assertEquals(600., rs.getDouble(4), 1e-9);
				assertEquals("2", rs.getString(5), "start link id");
				assertEquals("4", rs.getString(6), "end link id");
				assertEquals("2,3,4", rs.getString(7), "route link sequence: start, hops, end");
				assertEquals("car", rs.getString(8), "routingMode should round-trip from the dedicated field");
				assertEquals("12.5", rs.getString(9), "leg attribute should surface in the element's attributes map");
				rs.getObject(10); assertTrue(rs.wasNull(), "an activity must have no route");
			}
		} finally {
			Files.deleteIfExists(out);
		}
	}

	/**
	 * The elements[] regrouping carries exactly the information the older activities[]+legs[] schema
	 * did: unroll both files to one row per plan element and assert the symmetric difference is empty.
	 * The old schema's 0-based {@code sequence} equals the new array subscript minus one.
	 */
	@Test
	void carriesTheSameContentAsTheDuckDBWriter() throws Exception {
		Population pop = fixture();
		Path avro = Files.createTempFile("plansnap-avro", ".parquet");
		Path duck = Files.createTempFile("plansnap-duckdb", ".parquet");
		Files.deleteIfExists(duck); // COPY writes a fresh file

		new PlanSnapshotWriter().write(pop, avro.toString());
		new PlanSnapshotWriterDuckDB().write(pop, duck.toString());

		// one row per plan element, same column list on both sides
		String newElems =
			"SELECT personId, planIdx, selected, score, seq - 1 AS seq, " +
			"       e.kind, e.actType, e.link, e.startTime, e.endTime, e.maxDuration, " +
			"       e.mode, e.routingMode, e.travTime, " +
			"       e.route.routeType, e.route.distance, e.route.travTime AS routeTravTime, " +
			"       e.route.startLink, e.route.endLink, e.route.links " +
			// both table functions must be in the SAME select, or the array expands twice
			"FROM (SELECT personId, planIdx, selected, score, " +
			"             generate_subscripts(elements, 1) AS seq, unnest(elements) AS e " +
			"      FROM " + readParquet(avro) + ")";
		String oldElems =
			"SELECT personId, planIdx, selected, score, a.sequence AS seq, " +
			"       'activity' AS kind, a.actType, a.link, a.startTime, a.endTime, a.maxDuration, " +
			"       NULL::VARCHAR, NULL::VARCHAR, NULL::DOUBLE, " +
			"       NULL::VARCHAR, NULL::DOUBLE, NULL::DOUBLE, NULL::VARCHAR, NULL::VARCHAR, NULL::VARCHAR[] " +
			"FROM " + readParquet(duck) + ", UNNEST(activities) AS t(a) " +
			"UNION ALL " +
			"SELECT personId, planIdx, selected, score, l.sequence, " +
			"       'leg', NULL::VARCHAR, NULL::VARCHAR, NULL::DOUBLE, NULL::DOUBLE, NULL::DOUBLE, " +
			"       l.mode, l.routingMode, l.travTime, " +
			"       l.route.routeType, l.route.distance, l.route.travTime, " +
			"       l.route.startLink, l.route.endLink, l.route.links " +
			"FROM " + readParquet(duck) + ", UNNEST(legs) AS t(l)";

		try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
			 Statement st = conn.createStatement()) {

			try (ResultSet rs = st.executeQuery(
					"SELECT (SELECT count(*) FROM ((" + newElems + ") EXCEPT (" + oldElems + "))), " +
					"       (SELECT count(*) FROM ((" + oldElems + ") EXCEPT (" + newElems + "))), " +
					"       (SELECT count(*) FROM (" + newElems + "))")) {
				assertTrue(rs.next());
				assertEquals(0, rs.getInt(1), "plan elements the elements[] file has but the old schema does not");
				assertEquals(0, rs.getInt(2), "plan elements the old schema has but the elements[] file does not");
				assertEquals(6, rs.getInt(3), "fixture has 2 plans x 3 elements");
			}
		} finally {
			Files.deleteIfExists(avro);
			Files.deleteIfExists(duck);
		}
	}
}
