package org.matsim.run.scenarios;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.algorithms.MutateActivityTimeAllocation;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.scoring.DresdenActivityScoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DresdenModelStampInitialEndTimesTest {

	private static Double attr(Activity act, String name) {
		return (Double) act.getAttributes().getAttribute(name);
	}

	@Test
	void stampsAnchorsOnceAndLeavesTheRestAlone() {
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		Person person = PopulationUtils.getFactory().createPerson(Id.createPersonId("p"));
		Plan plan = PopulationUtils.createPlan();
		Activity home = PopulationUtils.createAndAddActivityFromCoord(plan, "home", new Coord(0., 0.));
		home.setEndTime(28800.);
		Leg leg1 = PopulationUtils.createAndAddLeg(plan, TransportMode.walk);
		leg1.setTravelTime(900.);
		Activity preStamped = PopulationUtils.createAndAddActivityFromCoord(plan, "work", new Coord(1., 0.));
		preStamped.setEndTime(59400.);
		preStamped.getAttributes().putAttribute(MutateActivityTimeAllocation.INITIAL_END_TIME_ATTRIBUTE, 57600.);
		Leg leg2 = PopulationUtils.createAndAddLeg(plan, TransportMode.walk);
		leg2.setTravelTime(600.);
		Activity durationBased = PopulationUtils.createAndAddActivityFromCoord(plan, "shop", new Coord(2., 0.));
		durationBased.setMaximumDuration(1800.);
		Leg leg3 = PopulationUtils.createAndAddLeg(plan, TransportMode.walk);
		leg3.setTravelTime(600.);
		Activity recorded = PopulationUtils.createAndAddActivityFromCoord(plan, "leisure", new Coord(3., 0.));
		recorded.setStartTime(64000.); // recorded start wins over the chain-walk value
		recorded.setEndTime(68000.);
		Leg leg4 = PopulationUtils.createAndAddLeg(plan, TransportMode.walk);
		leg4.setTravelTime(600.);
		PopulationUtils.createAndAddActivityFromCoord(plan, "home_evening", new Coord(0., 0.));
		person.addPlan(plan);
		scenario.getPopulation().addPerson(person);

		DresdenModel.stampScheduleAnchors(scenario);

		// first activity: end anchor only (its start is the day start, not a schedule choice)
		assertEquals(28800., attr(home, MutateActivityTimeAllocation.INITIAL_END_TIME_ATTRIBUTE));
		assertNull(attr(home, DresdenActivityScoring.INITIAL_START_TIME_ATTRIBUTE));
		// an already-present end anchor (preprocessing / previous run) must win over the current end time
		assertEquals(57600., attr(preStamped, MutateActivityTimeAllocation.INITIAL_END_TIME_ATTRIBUTE));
		// start reconstructed by walking the chain: 28800 + 900
		assertEquals(29700., attr(preStamped, DresdenActivityScoring.INITIAL_START_TIME_ATTRIBUTE));
		// duration-based activities get no anchors at all
		assertNull(attr(durationBased, MutateActivityTimeAllocation.INITIAL_END_TIME_ATTRIBUTE));
		assertNull(attr(durationBased, DresdenActivityScoring.INITIAL_START_TIME_ATTRIBUTE));
		// a recorded start time wins over the chain-walk value (which would be 59400 + 1800 + 600 = 61800)
		assertEquals(64000., attr(recorded, DresdenActivityScoring.INITIAL_START_TIME_ATTRIBUTE));
		assertEquals(68000., attr(recorded, MutateActivityTimeAllocation.INITIAL_END_TIME_ATTRIBUTE));
		// open last activity: nothing to anchor
		Activity last = (Activity) plan.getPlanElements().get(8);
		assertNull(attr(last, MutateActivityTimeAllocation.INITIAL_END_TIME_ATTRIBUTE));
		assertNull(attr(last, DresdenActivityScoring.INITIAL_START_TIME_ATTRIBUTE));
	}
}
