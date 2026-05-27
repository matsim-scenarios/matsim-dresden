package org.matsim.run.scenarios;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup.ActivityParams;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.contrib.vsp.scenario.SnzActivities;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dresden-local, clamp-aware copy of {@link org.matsim.contrib.vsp.scenario.Activities}.
 *
 * <p>The upstream {@code Activities.changeWrapAroundActsIntoMorningAndEveningActs} splits a
 * wrap-around activity (same first/last type, e.g. home) into a {@code _morning} and an
 * {@code _evening} sub-type so the day is scored via the else-branch of
 * {@link org.matsim.core.scoring.functions.CharyparNagelActivityScoring#handleOvernightActivity}
 * instead of the wrap-around if-branch. It assigns the evening typical duration as
 * {@code originalTypical - durationFirst}. That formula silently assumes the evening activity is
 * scored against a 24:00 day-end, and it lands at (or below) zero — collapsing into the 10-minute
 * floor bin — whenever the morning departure exceeds the whole-day typical.</p>
 *
 * <p>In Dresden the else-branch clamp is moved to 27:00 via
 * {@code config.scenario().setSimulationPeriodInDays(1.125)} (the scoring reads
 * {@code simulationPeriodInDays * 24 * 3600} as the overnight clamp). That gives the evening
 * activity {@code clamp - 24h} extra room relative to the wrap frame in which the original
 * typical was measured. This copy carries that extra room into the typical:
 * {@code evening = originalTypical - durationFirst + (clamp - 24h)}, the value that keeps the
 * evening "satisfied" at the same reference plan, and lets both sub-types bin up to the clamp
 * rather than 24h. When {@code simulationPeriodInDays == 1.0} (clamp at 24:00) the slack is zero
 * and the maximum bin is 86400, so this reduces exactly to the upstream behaviour.</p>
 *
 * @see org.matsim.contrib.vsp.scenario.Activities
 */
public final class DresdenActivities {
	private static final Logger log = LogManager.getLogger( DresdenActivities.class );

	/** The wrap-around if-branch scores the last act to firstActEnd + 24h; hardcoded in MATSim. */
	private static final double WRAP_OFFSET_S = 24 * 3600;

	private DresdenActivities(){}

	/**
	 * Round a duration to the nearest 10-minute bin, clamped to [600, maxSeconds]. Clamp-aware
	 * variant of the upstream getDurationBin, whose ceiling is fixed at 86400; here it tracks the
	 * day length so morning/evening typicals can reach the 27:00 clamp (matching the scoring params
	 * SnzActivities registers up to 97200).
	 */
	private static int getDurationBin( double duration, int maxSeconds ) {
		final int maxCategories = maxSeconds / 600;

		int durationCategoryNr = (int) Math.round(duration / 600);

		if (durationCategoryNr <= 0) {
			durationCategoryNr = 1;
		}

		if (durationCategoryNr >= maxCategories) {
			durationCategoryNr = maxCategories;
		}
		return durationCategoryNr * 600;
	}


	private static final int BIN_SIZE = 600;

	/**
	 * Reference anchor for overnight activities: CharyparNagelActivityScoring scores the
	 * wrap-around (first==last type) activity over [last.startTime, first.endTime + 24h], so a
	 * morning departure maps to closing-time {@code 24h + morningDeparture}. Matches the
	 * hard-coded {@code 24*3600} in {@code handleOvernightActivity}.
	 */
	private static final int ANCHOR_S = 24 * 3600;

	/**
	 * Buffer (seconds) added on each side of the observed window before binning. 0 means the
	 * scored opening window is exactly the observed performance window (snapped outward to the
	 * bin grid). Increase to give agents a tolerance band in which they can shift without the
	 * opening-time duration clamp biting. The single knob for experimenting with this rewrite.
	 */
	private static final double OPENING_TIME_BUFFER_S = 0.0;

	/**
	 * Derive each activity instance's scoring opening window from the initial plan and encode it
	 * into the activity type, the same way typical durations are read off the plan and encoded as
	 * a {@code _<seconds>} type suffix (cf.
	 * {@link org.matsim.application.prepare.population.SplitActivityTypesDuration}). MATSim only
	 * supports opening times per activity <em>type</em>, so we mint a distinct type per binned
	 * (base, typical, opening, closing) tuple and register its {@link ActivityParams}
	 * programmatically.
	 *
	 * <p>Run <em>after</em> {@link #changeNonWrapAroundActsIntoWrapAroundActs}: every plan is then
	 * wrap-around, so the first and last activity are scored as one combined overnight term over
	 * [last.startTime, first.endTime + 24h] using the last activity's params (see
	 * {@code CharyparNagelActivityScoring#handleOvernightActivity}). We therefore give first and
	 * last the <em>same</em> augmented type — preserving the type equality the wrap branch keys on
	 * — with opening = observed evening arrival and closing = 24h + observed morning departure.
	 * Middle activities get their own observed [start, end] window.
	 *
	 * <p>The opening window is read from the <em>initial</em> plan; at scoring time MATSim passes
	 * the experienced activity (correct simulated start/end), looks up these params by type, and
	 * clamps the experienced duration to the baked-in observed window.
	 *
	 * <p>Note: this mints one ActivityParams per distinct binned window, so the scoring config can
	 * grow large on big populations; and because the wrap (first/last) activity now carries opening
	 * times, CharyparNagelActivityScoring logs a (harmless, here intentional) warning that scoring
	 * correctness "cannot be guaranteed" for first/last activities.
	 */
	public static void setPlanDerivedOpeningTimes(Scenario scenario) {
		ScoringConfigGroup scoring = scenario.getConfig().scoring();
		Set<String> registered = new HashSet<>();

		for (Person p : scenario.getPopulation().getPersons().values()) {
//			ignore freight / commercial traffic agents and stay home agents (same cohort as the wrap rewrite)
			if (!p.getAttributes().getAttribute("subpopulation").equals("person") ||
				p.getSelectedPlan().getPlanElements().size() == 1) {
				continue;
			}

			for (Plan plan : p.getPlans()) {
				List<Activity> acts = TripStructureUtils.getActivities(plan, TripStructureUtils.StageActivityHandling.ExcludeStageActivities);
				int n = acts.size();
				if (n < 2) {
					continue;
				}

				Activity first = acts.getFirst();
				Activity last = acts.getLast();

//				combined overnight (wrap-around) home: opening = observed evening arrival,
//				closing = 24h + observed morning departure. Both endpoints share one augmented type.
				double open = observedStart(last);
				double close = ANCHOR_S + observedEnd(first);
				applyOpeningWindow(first, open, close, scoring, registered);
				applyOpeningWindow(last, open, close, scoring, registered);

//				middle activities: their own observed [start, end] window.
				for (int i = 1; i < n - 1; i++) {
					Activity act = acts.get(i);
					applyOpeningWindow(act, observedStart(act), observedEnd(act), scoring, registered);
				}
			}
		}
		log.info("plan-derived opening times: registered {} distinct activity-type params", registered.size());
	}

	/**
	 * Observed start (arrival) of an activity in the initial plan. The first activity of a plan has
	 * no start time (the agent begins there at t=0).
	 */
	private static double observedStart(Activity act) {
		return act.getStartTime().orElse(0.0);
	}

	/**
	 * Observed end (departure) of an activity in the initial plan: its end time, or start + maximum
	 * duration when the end time was encoded as a duration (cf. SplitActivityTypesDuration's
	 * end-time-to-duration step). Not meaningful for an open-ended overnight last activity, which
	 * this method is never called on.
	 */
	private static double observedEnd(Activity act) {
		if (act.getEndTime().isDefined()) {
			return act.getEndTime().seconds();
		}
		if (act.getMaximumDuration().isDefined()) {
			return observedStart(act) + act.getMaximumDuration().seconds();
		}
		return observedStart(act);
	}

	/**
	 * Map an observed [start, end] window (± buffer) to a binned scoring opening window and bake it
	 * onto the activity: append {@code _op<opening>_cl<closing>} to the type and register the
	 * matching {@link ActivityParams} once. The typical duration is preserved from the incoming
	 * {@code <base>_<typical>} type. Opening is floored and closing ceiled to the bin grid, so the
	 * binned window always contains the observed window (it never collapses, even for short acts).
	 */
	private static void applyOpeningWindow(Activity act, double obsStart, double obsEnd,
	                                       ScoringConfigGroup scoring, Set<String> registered) {
		double opening = Math.max(0.0, Math.floor((obsStart - OPENING_TIME_BUFFER_S) / BIN_SIZE) * BIN_SIZE);
		double closing = Math.ceil((obsEnd + OPENING_TIME_BUFFER_S) / BIN_SIZE) * BIN_SIZE;
//		guard against a collapsed window for zero-/sub-bin observed durations (floor(start)==ceil(end)):
//		keep it at least one bin wide so the activity stays performable rather than scoring as "closed".
		closing = Math.max(closing, opening + BIN_SIZE);

		String baseType = act.getType();
		int typical = Integer.parseInt(baseType.substring(baseType.lastIndexOf('_') + 1));
		String newType = String.format("%s_op%d_cl%d", baseType, (long) opening, (long) closing);
		act.setType(newType);

		if (registered.add(newType)) {
			scoring.addActivityParams(new ActivityParams(newType)
				.setTypicalDuration(typical)
				.setOpeningTime(opening)
				.setClosingTime(closing));
		}
	}
}
