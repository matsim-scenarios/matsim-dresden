package org.matsim.scoring;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.algorithms.MutateActivityTimeAllocation;
import org.matsim.core.scoring.SumScoringFunction;
import org.matsim.core.scoring.functions.ActivityAttributeTypicalDurationCalculator;
import org.matsim.core.scoring.functions.CharyparNagelActivityScoring;
import org.matsim.core.scoring.functions.ScoringParameters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the Dresden-specific scoring pieces that remain after the per-activity typical duration was consolidated
 * into MATSim core (see {@code CharyparNagelActivityScoringTypicalDurationTest} there): the schedule-delay corridor
 * ({@link DresdenActivityScoring}) and the strict typical-duration requirement
 * ({@link RequiredTypicalDurationCalculator}). The runtime scoring is handed attribute-less activity reconstructions;
 * these tests feed exactly such bare activities and verify that the plan cursor supplies the attributes.
 */
class DresdenActivityScoringTest {

	private static Config config() {
		Config config = ConfigUtils.createConfig();
		ScoringConfigGroup scoring = config.scoring();
		scoring.setPerforming_utils_hr(6.);
		scoring.setLateArrival_utils_hr(-18.);
		scoring.setEarlyDeparture_utils_hr(-6.);
		for (String type : new String[]{"home", "work", "home_evening"}) {
			scoring.addActivityParams(new ScoringConfigGroup.ActivityParams(type).setTypicalDuration(2. * 3600.));
		}
		ScoringConfigGroup.ActivityParams interaction = new ScoringConfigGroup.ActivityParams("pt interaction");
		interaction.setScoringThisActivityAtAll(false);
		scoring.addActivityParams(interaction);
		return config;
	}

	private static Person person(Plan plan) {
		Person person = PopulationUtils.getFactory().createPerson(Id.createPersonId("p"));
		person.addPlan(plan);
		person.setSelectedPlan(plan);
		return person;
	}

	private static ScoringParameters params(Config config) {
		return new ScoringParameters.Builder(config.scoring(), config.scoring().getScoringParameters(null), config.scenario()).build();
	}

	/** The run's activity scoring: core Charypar-Nagel with per-activity typical durations, plus the corridor when armed. */
	private static SumScoringFunction runScoring(Config config, Person person, boolean scheduleDelayScoring) {
		SumScoringFunction sumScoringFunction = new SumScoringFunction();
		sumScoringFunction.addScoringFunction(new CharyparNagelActivityScoring(params(config), new ActivityAttributeTypicalDurationCalculator(), person));
		if (scheduleDelayScoring) {
			sumScoringFunction.addScoringFunction(new DresdenActivityScoring(params(config), person));
		}
		return sumScoringFunction;
	}

	/** The plan with attributes: home(t*=8h, e*=8:00) - work(t*=8h, s*=8:30, e*=16:30) - home_evening. */
	private static Plan plan() {
		Plan plan = PopulationUtils.createPlan();
		Activity home = PopulationUtils.createAndAddActivityFromCoord(plan, "home", new Coord(0., 0.));
		home.setEndTime(28800.);
		home.getAttributes().putAttribute("typicalDuration", 28800.);
		home.getAttributes().putAttribute(MutateActivityTimeAllocation.INITIAL_END_TIME_ATTRIBUTE, 28800.);
		PopulationUtils.createAndAddLeg(plan, "car");
		Activity work = PopulationUtils.createAndAddActivityFromCoord(plan, "work", new Coord(1., 0.));
		work.setEndTime(59400.);
		work.getAttributes().putAttribute("typicalDuration", 28800.);
		work.getAttributes().putAttribute(DresdenActivityScoring.INITIAL_START_TIME_ATTRIBUTE, 30600.);
		work.getAttributes().putAttribute(MutateActivityTimeAllocation.INITIAL_END_TIME_ATTRIBUTE, 59400.);
		PopulationUtils.createAndAddLeg(plan, "car");
		PopulationUtils.createAndAddActivityFromCoord(plan, "home_evening", new Coord(0., 0.));
		return plan;
	}

	/** Bare activity as EventsToActivities produces it: type and times only, no attributes. */
	private static Activity bare(String type, Double start, Double end) {
		Activity act = PopulationUtils.createActivityFromCoord(type, new Coord(0., 0.));
		if (start != null) act.setStartTime(start);
		if (end != null) act.setEndTime(end);
		return act;
	}

	/** Feeds the standard bare day; SumScoringFunction routes first/middle/last by the defined times. */
	private static double score(SumScoringFunction scoring, double workStart, double workEnd) {
		scoring.handleActivity(bare("home", null, 28800.));
		scoring.handleActivity(bare("work", workStart, workEnd));
		scoring.handleActivity(bare("home_evening", workEnd + 1800., null));
		scoring.finish();
		return scoring.getScore();
	}

	@Test
	void corridorFiresOnlyWhenArmed() {
		Config config = config();
		// work starts 30min late (32400 vs anchor 30600) and ends 30min early (57600 vs anchor 59400 -> 1800s early):
		// armed = disarmed - 18*0.5 - 6*0.5 = disarmed - 12.
		double disarmed = score(runScoring(config, person(plan()), false), 32400., 57600.);
		double armed = score(runScoring(config, person(plan()), true), 32400., 57600.);
		assertEquals(disarmed - 18. * 0.5 - 6. * 0.5, armed, 1e-9);
	}

	/**
	 * The corridor's plan cursor supplies the anchors for the bare realized activities; handing in the
	 * attribute-carrying plan activities directly (the offline/VTTS path, no person) gives the same terms.
	 */
	@Test
	void corridorReadsAnchorsThroughThePlanCursor() {
		Config config = config();
		DresdenActivityScoring viaCursor = new DresdenActivityScoring(params(config), person(plan()));
		viaCursor.handleFirstActivity(bare("home", null, 28800.));
		viaCursor.handleActivity(bare("work", 32400., 57600.));
		viaCursor.handleLastActivity(bare("home_evening", 59400., null));
		viaCursor.finish();
		// work: 1800s late (-18/h) and 1800s early (-6/h) => -9 - 3 = -12
		assertEquals(-12., viaCursor.getScore(), 1e-9);

		DresdenActivityScoring direct = new DresdenActivityScoring(params(config), null);
		Plan p = plan();
		direct.handleFirstActivity((Activity) p.getPlanElements().get(0));
		Activity work = (Activity) p.getPlanElements().get(2);
		work.setStartTime(32400.);
		work.setEndTime(57600.);
		direct.handleActivity(work);
		Activity last = (Activity) p.getPlanElements().get(4);
		last.setStartTime(59400.);
		direct.handleLastActivity(last);
		direct.finish();
		assertEquals(viaCursor.getScore(), direct.getScore(), 1e-9);
	}

	/**
	 * Stage activities must not consume the cursor: the realized stream contains "pt interaction" activities whose
	 * count varies with routing, while the plan cursor aligns main activities only. Scores must equal the
	 * interaction-free day exactly (interactions have scoringThisActivityAtAll=false and carry no anchors).
	 */
	@Test
	void stageActivitiesBypassTheCursor() {
		Config config = config();
		SumScoringFunction withStages = runScoring(config, person(plan()), true);
		withStages.handleActivity(bare("home", null, 28800.));
		withStages.handleActivity(bare("pt interaction", 29000., 29000.));
		withStages.handleActivity(bare("pt interaction", 29800., 29800.));
		withStages.handleActivity(bare("work", 30600., 59400.));
		withStages.handleActivity(bare("pt interaction", 60000., 60000.));
		withStages.handleActivity(bare("home_evening", 61200., null));
		withStages.finish();

		double plain = score(runScoring(config, person(plan()), true), 30600., 59400.);
		assertEquals(plain, withStages.getScore(), 1e-9);
	}

	/** A main-activity divergence between plan and realization is a broken model invariant: fail hard, never degrade. */
	@Test
	void cursorMismatchAbortsTheRun() {
		Config config = config();
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("unexpected").setTypicalDuration(2. * 3600.));
		DresdenActivityScoring scoring = new DresdenActivityScoring(params(config), person(plan()));
		RuntimeException e = assertThrows(RuntimeException.class,
			() -> scoring.handleFirstActivity(bare("unexpected", null, 28800.)));
		assertTrue(e.getMessage().contains("diverged"), e.getMessage());
	}

	/** An anchored activity whose type also defines the type-level counterpart would be double-counted: abort. */
	@Test
	void typeLevelValueNextToAnchorAborts() {
		Config config = config();
		config.scoring().getActivityParams("work").setLatestStartTime(30600.);
		DresdenActivityScoring scoring = new DresdenActivityScoring(params(config), person(plan()));
		scoring.handleFirstActivity(bare("home", null, 28800.));
		RuntimeException e = assertThrows(RuntimeException.class,
			() -> scoring.handleActivity(bare("work", 30600., 59400.)));
		assertTrue(e.getMessage().contains("double-count"), e.getMessage());
	}

	/** With the attribute requirement armed, a scored activity without a typicalDuration attribute aborts the run. */
	@Test
	void missingTypicalDurationAbortsWhenRequired() {
		Config config = config();
		Plan stripped = plan();
		((Activity) stripped.getPlanElements().get(2)).getAttributes().removeAttribute("typicalDuration");

		CharyparNagelActivityScoring strict = new CharyparNagelActivityScoring(params(config),
			new RequiredTypicalDurationCalculator(null), person(stripped));
		strict.handleFirstActivity(bare("home", null, 28800.));
		RuntimeException e = assertThrows(RuntimeException.class,
			() -> strict.handleActivity(bare("work", 30600., 59400.)));
		assertTrue(e.getMessage().contains("typicalDuration"), e.getMessage());

		// lenient (legacy type-encoded populations): same day scores against the config typical without complaint
		Plan stripped2 = plan();
		((Activity) stripped2.getPlanElements().get(2)).getAttributes().removeAttribute("typicalDuration");
		score(runScoring(config, person(stripped2), false), 30600., 59400.); // must not throw
	}
}
