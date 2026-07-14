package org.matsim.run.scenarios;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.algorithms.MutateActivityTimeAllocation;
import org.matsim.core.scenario.ScenarioUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DresdenModelStampInitialEndTimesTest {

	@Test
	void stampsEndTimesOnceAndLeavesTheRestAlone() {
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		Person person = PopulationUtils.getFactory().createPerson(Id.createPersonId("p"));
		Plan plan = PopulationUtils.createPlan();
		Activity home = PopulationUtils.createAndAddActivityFromCoord(plan, "home", new Coord(0., 0.));
		home.setEndTime(28800.);
		PopulationUtils.createAndAddLeg(plan, TransportMode.walk);
		Activity preStamped = PopulationUtils.createAndAddActivityFromCoord(plan, "work", new Coord(1., 0.));
		preStamped.setEndTime(59400.);
		preStamped.getAttributes().putAttribute(MutateActivityTimeAllocation.INITIAL_END_TIME_ATTRIBUTE, 57600.);
		PopulationUtils.createAndAddLeg(plan, TransportMode.walk);
		Activity durationBased = PopulationUtils.createAndAddActivityFromCoord(plan, "shop", new Coord(2., 0.));
		durationBased.setMaximumDuration(1800.);
		PopulationUtils.createAndAddLeg(plan, TransportMode.walk);
		PopulationUtils.createAndAddActivityFromCoord(plan, "home_evening", new Coord(0., 0.));
		person.addPlan(plan);
		scenario.getPopulation().addPerson(person);

		DresdenModel.stampInitialEndTimes(scenario);

		assertEquals(28800., (Double) home.getAttributes().getAttribute(MutateActivityTimeAllocation.INITIAL_END_TIME_ATTRIBUTE));
		// an already-present anchor (from preprocessing or a previous run) must win over the current end time
		assertEquals(57600., (Double) preStamped.getAttributes().getAttribute(MutateActivityTimeAllocation.INITIAL_END_TIME_ATTRIBUTE));
		// duration-based and open activities have no end time to anchor
		assertNull(durationBased.getAttributes().getAttribute(MutateActivityTimeAllocation.INITIAL_END_TIME_ATTRIBUTE));
	}
}
