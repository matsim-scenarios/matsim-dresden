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
 * Exercises the null-handling paths of {@link PlanSnapshotWriterDuckDB} against the pinned DuckDB
 * driver: a pre-scoring plan (null score), an activity with an undefined end time, and a teleported
 * leg (NaN distance / undefined travel time). The bug was that the old {@code append((Object) null)}
 * no longer compiles/works; this asserts the produced parquet actually carries SQL NULL in those cells.
 *
 * Grain is one row per plan (see the writer): {@code (personId, planIdx, selected, score,
 * activities[], legs[])} — no iteration field.
 */
class PlanSnapshotWriterDuckDBTest {

	@Test
	void nullsSurviveRoundTrip() throws Exception {
		Population pop = PopulationUtils.createPopulation(org.matsim.core.config.ConfigUtils.createConfig());
		PopulationFactory pf = pop.getFactory();

		Person person = pf.createPerson(Id.createPersonId("A"));
		person.getAttributes().putAttribute("subpopulation", "person"); // generic person attribute -> personAttributes map

		// Plan 0 (selected): score left null (pre-scoring), first activity has NO end time,
		// and a teleported leg whose generic route has NaN distance + undefined travel time.
		Plan p0 = pf.createPlan();
		Activity home = pf.createActivityFromLinkId("home", Id.createLinkId("1"));
		// deliberately no start/end time on `home`, but a maximum duration -> exercises both null and non-null time fields
		home.setMaximumDuration(3600.);
		home.getAttributes().putAttribute("typicalDuration", 3600.0); // generic activity attribute -> activities[].attributes map
		p0.addActivity(home);
		Leg teleported = pf.createLeg("pt");
		teleported.setRoute(RouteUtils.createGenericRouteImpl(Id.createLinkId("1"), Id.createLinkId("2")));
		// generic route: distance stays NaN, travel time stays undefined
		p0.addLeg(teleported);
		Activity work = pf.createActivityFromLinkId("work", Id.createLinkId("2"));
		work.setEndTime(9 * 3600.);
		p0.addActivity(work);
		// score intentionally not set -> null
		person.addPlan(p0);
		person.setSelectedPlan(p0);

		// Plan 1 (not selected): fully populated, network-routed leg -> the non-null side of every branch.
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
		carLeg.setRoutingMode("car"); // dedicated field -> routingMode column (NOT a generic attribute)
		carLeg.getAttributes().putAttribute("totalRouteCost", 12.5); // generic leg attribute -> legs[].attributes map
		p1.addLeg(carLeg);
		Activity w2 = pf.createActivityFromLinkId("work", Id.createLinkId("4"));
		p1.addActivity(w2);
		p1.setScore(-3.5);
		person.addPlan(p1);

		pop.addPerson(person);

		Path out = Files.createTempFile("plansnap", ".parquet");
		Files.deleteIfExists(out); // COPY writes a fresh file

		new PlanSnapshotWriterDuckDB().write(pop, out.toString());

		assertTrue(Files.exists(out) && Files.size(out) > 0, "parquet not written");

		String pq = "read_parquet('" + out.toString().replace("'", "''") + "')";
		try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
			 Statement st = conn.createStatement()) {

			// two plan rows for person A (one per plan); no iteration field in the schema
			try (ResultSet rs = st.executeQuery(
					"SELECT count(*) FROM " + pq + " WHERE personId = 'A'")) {
				assertTrue(rs.next());
				assertEquals(2, rs.getInt(1), "expected one row per plan");
			}

			// planIdx 0 = selected pre-scoring plan: score NULL, first activity has NULL start/end time
			// but a defined maxDuration; teleported leg -> travTime / route.distance / route.travTime all NULL;
			// generic attributes surface as maps (person-level repeated onto the plan row, activity-level nested).
			try (ResultSet rs = st.executeQuery(
					"SELECT score, selected, activities[1].startTime, activities[1].endTime, " +
					"       activities[1].maxDuration, activities[1].link, " +
					"       legs[1].travTime, legs[1].route.distance, legs[1].route.travTime, " +
					"       legs[1].route.links, " +
					"       personAttributes['subpopulation'], activities[1].attributes['typicalDuration'] " +
					"FROM " + pq + " WHERE personId = 'A' AND planIdx = 0")) {
				assertTrue(rs.next());
				rs.getObject(1); assertTrue(rs.wasNull(), "pre-scoring score should be SQL NULL");
				assertTrue(rs.getBoolean(2), "plan 0 should be marked selected");
				rs.getObject(3); assertTrue(rs.wasNull(), "undefined activity start time should be SQL NULL");
				rs.getObject(4); assertTrue(rs.wasNull(), "undefined activity end time should be SQL NULL");
				assertEquals(3600., rs.getDouble(5), 1e-9, "activity maxDuration should round-trip");
				assertEquals("1", rs.getString(6), "activity link id should be inlined as a string");
				rs.getObject(7); assertTrue(rs.wasNull(), "undefined leg travel time should be SQL NULL");
				rs.getObject(8); assertTrue(rs.wasNull(), "NaN route distance should be SQL NULL");
				rs.getObject(9); assertTrue(rs.wasNull(), "undefined route travel time should be SQL NULL");
				assertEquals("[]", rs.getString(10), "teleported leg should have no route links");
				assertEquals("person", rs.getString(11), "person attribute should surface in personAttributes map");
				assertEquals("3600.0", rs.getString(12), "activity attribute should surface in activities[].attributes map");
			}

			// planIdx 1 = fully populated network-routed plan: non-null side + inlined link sequence,
			// plus routingMode (dedicated field) and a generic leg attribute (map).
			try (ResultSet rs = st.executeQuery(
					"SELECT score, selected, legs[1].route.distance, legs[1].route.travTime, " +
					"       legs[1].route.startLink, legs[1].route.endLink, " +
					"       array_to_string(legs[1].route.links, ','), " +
					"       legs[1].routingMode, legs[1].attributes['totalRouteCost'] " +
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
				assertEquals("12.5", rs.getString(9), "leg attribute should surface in legs[].attributes map");
			}
		} finally {
			Files.deleteIfExists(out);
		}
	}

	/** The write schedule must match PlansDumpingImpl: every `interval` iterations, plus early iterations. */
	@Test
	void writesOnSameIterationsAsRegularPlansDump() {
		// interval 10, write-more-until 1 (defaults are 50 / 1)
		assertTrue(PlanSnapshotWriterDuckDB.writesOnIteration(0, 10, 1), "iteration 0 is an early iteration");
		assertTrue(PlanSnapshotWriterDuckDB.writesOnIteration(1, 10, 1), "iteration 1 is an early iteration");
		assertFalse(PlanSnapshotWriterDuckDB.writesOnIteration(2, 10, 1), "iteration 2: neither on-interval nor early");
		assertFalse(PlanSnapshotWriterDuckDB.writesOnIteration(5, 10, 1), "iteration 5: off-interval");
		assertTrue(PlanSnapshotWriterDuckDB.writesOnIteration(10, 10, 1), "iteration 10 is on-interval");
		assertTrue(PlanSnapshotWriterDuckDB.writesOnIteration(20, 10, 1), "iteration 20 is on-interval");

		// interval 0 disables writing entirely — even at iteration 0
		assertFalse(PlanSnapshotWriterDuckDB.writesOnIteration(0, 0, 1), "interval 0 disables all writing");
		assertFalse(PlanSnapshotWriterDuckDB.writesOnIteration(50, 0, 1), "interval 0 disables all writing");

		// writePlansUntilIteration extends the early window (inclusive)
		assertTrue(PlanSnapshotWriterDuckDB.writesOnIteration(3, 100, 3), "iteration 3 within early window");
		assertFalse(PlanSnapshotWriterDuckDB.writesOnIteration(4, 100, 3), "iteration 4 past early window, off-interval");
	}
}
