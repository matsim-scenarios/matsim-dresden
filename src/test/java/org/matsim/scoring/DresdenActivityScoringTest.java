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
import org.matsim.core.scoring.functions.ScoringParameters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The runtime scoring is handed attribute-less activity reconstructions (see {@link DresdenActivityScoring}'s class
 * javadoc); these tests feed exactly such bare activities and verify that the plan cursor supplies the attributes.
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

	private static double score(DresdenActivityScoring scoring, double workStart, double workEnd) {
		scoring.handleFirstActivity(bare("home", null, 28800.));
		scoring.handleActivity(bare("work", workStart, workEnd));
		scoring.handleLastActivity(bare("home_evening", workEnd + 1800., null));
		scoring.finish();
		return scoring.getScore();
	}

	@Test
	void planCursorSuppliesTheAttributes() {
		Config config = config();
		ScoringConfigGroup.ScoringParameterSet set = config.scoring().getScoringParameters(null);
		// with the plan cursor, work is scored against its 8h attribute typical; without a plan, the bare activities
		// fall back to the 2h config typical -- the scores must differ (they were identical before the fix).
		DresdenActivityScoring withPlan = new DresdenActivityScoring(params(config), set, person(plan()), false);
		DresdenActivityScoring withoutPlan = new DresdenActivityScoring(params(config), set, null, false);
		double scoreWithPlan = score(withPlan, 30600., 59400.);
		double scoreWithoutPlan = score(withoutPlan, 30600., 59400.);
		assertNotEquals(scoreWithPlan, scoreWithoutPlan, 1.);

		// gold standard: handing in the attribute-carrying plan activities directly (the offline/VTTS path) must give
		// exactly the same result as the cursor.
		DresdenActivityScoring direct = new DresdenActivityScoring(params(config), set, null, false);
		Plan p = plan();
		direct.handleFirstActivity((Activity) p.getPlanElements().get(0));
		Activity work = (Activity) p.getPlanElements().get(2);
		work.setStartTime(30600.);
		direct.handleActivity(work);
		Activity last = (Activity) p.getPlanElements().get(4);
		last.setStartTime(61200.);
		direct.handleLastActivity(last);
		direct.finish();
		assertEquals(direct.getScore(), scoreWithPlan, 1e-9);
	}

	@Test
	void corridorFiresOnlyWhenArmed() {
		Config config = config();
		ScoringConfigGroup.ScoringParameterSet set = config.scoring().getScoringParameters(null);
		// work starts 30min late (31200 vs anchor 30600) and ends 30min early (57600 vs anchor 59400 -> 1800s early):
		// armed = disarmed - 18*0.5 - 6*0.5 = disarmed - 12.
		double disarmed = score(new DresdenActivityScoring(params(config), set, person(plan()), false), 32400., 57600.);
		double armed = score(new DresdenActivityScoring(params(config), set, person(plan()), true), 32400., 57600.);
		assertEquals(disarmed - 18. * 0.5 - 6. * 0.5, armed, 1e-9);
	}

	/**
	 * Stage activities must not consume the cursor: the realized stream contains "pt interaction" activities whose
	 * count varies with routing (and, before this fix, with the replanning-vs-scoring-function-creation race), while
	 * the plan cursor aligns main activities only. Scores must equal the interaction-free day exactly (interactions
	 * have scoringThisActivityAtAll=false).
	 */
	@Test
	void stageActivitiesBypassTheCursor() {
		Config config = config();
		ScoringConfigGroup.ScoringParameterSet set = config.scoring().getScoringParameters(null);
		DresdenActivityScoring withStages = new DresdenActivityScoring(params(config), set, person(plan()), true);
		withStages.handleFirstActivity(bare("home", null, 28800.));
		withStages.handleActivity(bare("pt interaction", 29000., 29000.));
		withStages.handleActivity(bare("pt interaction", 29800., 29800.));
		withStages.handleActivity(bare("work", 30600., 59400.));
		withStages.handleActivity(bare("pt interaction", 60000., 60000.));
		withStages.handleLastActivity(bare("home_evening", 61200., null));
		withStages.finish();

		double plain = score(new DresdenActivityScoring(params(config), set, person(plan()), true), 30600., 59400.);
		assertEquals(plain, withStages.getScore(), 1e-9);
	}

	/**
	 * Scoring functions are created at iteration start, BEFORE replanning: the plan selected at construction time is
	 * not necessarily the executed one. The cursor must resolve the plan lazily -- here the person's selected plan is
	 * replaced after construction (with different attributes standing in for a structurally different plan), and the
	 * scoring must use the replacement.
	 */
	@Test
	void planIsResolvedAfterConstructionNotAt() {
		Config config = config();
		ScoringConfigGroup.ScoringParameterSet set = config.scoring().getScoringParameters(null);
		Person person = person(plan());
		DresdenActivityScoring scoring = new DresdenActivityScoring(params(config), set, person, false);

		// "replanning": a new selected plan with a different work typical duration (4h instead of 8h)
		person.addPlan(planWithWorkTypical(14400.));
		person.setSelectedPlan(person.getPlans().get(1));

		double lazily = score(scoring, 30600., 59400.);
		double onReplanned = score(new DresdenActivityScoring(params(config), set, person(planWithWorkTypical(14400.)), false), 30600., 59400.);
		double onStale = score(new DresdenActivityScoring(params(config), set, person(plan()), false), 30600., 59400.);
		assertEquals(onReplanned, lazily, 1e-9);
		assertNotEquals(onStale, lazily, 1.);
	}

	private static Plan planWithWorkTypical(double typicalDuration) {
		Plan plan = plan();
		((Activity) plan.getPlanElements().get(2)).getAttributes().putAttribute("typicalDuration", typicalDuration);
		return plan;
	}

	/** A main-activity divergence between plan and realization is a broken model invariant: fail hard, never degrade. */
	@Test
	void cursorMismatchAbortsTheRun() {
		Config config = config();
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("unexpected").setTypicalDuration(2. * 3600.));
		ScoringConfigGroup.ScoringParameterSet set = config.scoring().getScoringParameters(null);
		DresdenActivityScoring scoring = new DresdenActivityScoring(params(config), set, person(plan()), true);
		RuntimeException e = assertThrows(RuntimeException.class,
			() -> scoring.handleFirstActivity(bare("unexpected", null, 28800.)));
		assertTrue(e.getMessage().contains("diverged"), e.getMessage());
	}

	/** With the attribute requirement armed, a scored activity without a typicalDuration attribute aborts the run. */
	@Test
	void missingTypicalDurationAbortsWhenRequired() {
		Config config = config();
		ScoringConfigGroup.ScoringParameterSet set = config.scoring().getScoringParameters(null);
		Plan stripped = plan();
		((Activity) stripped.getPlanElements().get(2)).getAttributes().removeAttribute("typicalDuration");

		DresdenActivityScoring strict = new DresdenActivityScoring(params(config), set, person(stripped), false, true);
		strict.handleFirstActivity(bare("home", null, 28800.));
		RuntimeException e = assertThrows(RuntimeException.class,
			() -> strict.handleActivity(bare("work", 30600., 59400.)));
		assertTrue(e.getMessage().contains("typicalDuration"), e.getMessage());

		// lenient (legacy type-encoded populations): same day scores against the config typical without complaint
		Plan stripped2 = plan();
		((Activity) stripped2.getPlanElements().get(2)).getAttributes().removeAttribute("typicalDuration");
		DresdenActivityScoring lenient = new DresdenActivityScoring(params(config), set, person(stripped2), false, false);
		score(lenient, 30600., 59400.); // must not throw
	}
}
