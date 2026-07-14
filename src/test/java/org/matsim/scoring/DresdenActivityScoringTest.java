package org.matsim.scoring;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.algorithms.MutateActivityTimeAllocation;
import org.matsim.core.scoring.functions.ScoringParameters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
		return config;
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
		DresdenActivityScoring withPlan = new DresdenActivityScoring(params(config), set, plan(), false);
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
		double disarmed = score(new DresdenActivityScoring(params(config), set, plan(), false), 32400., 57600.);
		double armed = score(new DresdenActivityScoring(params(config), set, plan(), true), 32400., 57600.);
		assertEquals(disarmed - 18. * 0.5 - 6. * 0.5, armed, 1e-9);
	}

	@Test
	void cursorMismatchFallsBackToConfigValues() {
		Config config = config();
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("unexpected").setTypicalDuration(2. * 3600.));
		ScoringConfigGroup.ScoringParameterSet set = config.scoring().getScoringParameters(null);
		// realized day diverges from the plan right away ("unexpected" vs the plan's "home"): the cursor is abandoned,
		// nothing blows up, and the whole day scores exactly like the no-plan (config-values) scoring.
		DresdenActivityScoring scoring = new DresdenActivityScoring(params(config), set, plan(), true);
		DresdenActivityScoring noPlan = new DresdenActivityScoring(params(config), set, null, true);
		for (DresdenActivityScoring s : new DresdenActivityScoring[]{scoring, noPlan}) {
			s.handleFirstActivity(bare("unexpected", null, 28800.));
			s.handleActivity(bare("work", 30600., 59400.));
			s.handleLastActivity(bare("home_evening", 61200., null));
			s.finish();
		}
		assertEquals(noPlan.getScore(), scoring.getScore(), 1e-9);
	}
}
