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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class DresdenActivities {
	private static final Logger log = LogManager.getLogger( DresdenActivities.class );

	/** The wrap-around if-branch scores the last act to firstActEnd + 24h; hardcoded in MATSim. */
	private static final double WRAP_OFFSET_S = 24 * 3600;

	private DresdenActivities(){}


	private static final int BIN_SIZE = 600;

	/**
	 * The {@code _op<opening>_cl<closing>} suffix this class appends, with the opening/closing values
	 * captured. When the suffix is already present we leave the window
	 * untouched — it was derived from the original demand on the first run and stays pinned to it.
	 */
	private static final Pattern OPENING_WINDOW_SUFFIX = Pattern.compile("_op(\\d+)_cl(\\d+)$");

	/**
	 * Buffer (seconds) added on each side of the observed window before binning. 0 means the
	 * scored opening window is exactly the observed performance window (snapped outward to the
	 * bin grid). Increase to give agents a tolerance band in which they can shift without the
	 * opening-time duration clamp biting. The single knob for experimenting with this rewrite.
	 */
	private static final double OPENING_TIME_BUFFER_S = 0.0;

	/**
	 * Derive each <em>middle</em> activity instance's scoring opening window from the initial plan
	 * and encode it into the activity type, the same way typical durations are read off the plan
	 * and encoded as a {@code _<seconds>} type suffix (cf.
	 * {@link org.matsim.application.prepare.population.SplitActivityTypesDuration}). MATSim only
	 * supports opening times per activity <em>type</em>, so we mint a distinct type per binned
	 * (base, typical, opening, closing) tuple and register its {@link ActivityParams}
	 * programmatically.
	 *
	 * <p>The first and last activity are deliberately left untouched.
	 *
	 * <p>Note: this creates one ActivityParams per distinct binned window, so the scoring config can
	 * grow large on big populations.
	 */
	public static void setPlanDerivedOpeningTimes(Scenario scenario) {
		ScoringConfigGroup scoring = scenario.getConfig().scoring();
		Set<String> registered = new HashSet<>();

		for (Person p : scenario.getPopulation().getPersons().values()) {
//			ignore freight / commercial traffic agents and stay home agents
			if (!p.getAttributes().getAttribute("subpopulation").equals("person") ||
				p.getSelectedPlan().getPlanElements().size() == 1) {
				continue;
			}

			for (Plan plan : p.getPlans()) {
				List<Activity> acts = TripStructureUtils.getActivities(plan, TripStructureUtils.StageActivityHandling.ExcludeStageActivities);

//				tag only middle activities, each with its own observed [start, end] window. The
//				first/last (wrap-around overnight) activity is left untouched so the overnight term
//				is not scored with opening times.
				for (int i = 1; i < acts.size() - 1; i++) {
					Activity act = acts.get(i);
					applyOpeningWindow(act, observedStart(act), observedEnd(act), scoring, registered);
				}
			}
		}
		log.info("plan-derived opening times: registered {} distinct activity-type params (middle activities only)", registered.size());
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
		double opening, closing;

//		chained run: this activity already carries a window tag from a previous run. Leave the type
//		and window as-is (pinned to the original demand) and just re-register the params.
		var matcher = OPENING_WINDOW_SUFFIX.matcher(act.getType());
		if (matcher.find()) {
			opening = Double.parseDouble(matcher.group(1));
			closing = Double.parseDouble(matcher.group(2));
		} else {
			opening = Math.max(0.0, Math.floor((obsStart - OPENING_TIME_BUFFER_S) / BIN_SIZE) * BIN_SIZE);
			closing = Math.ceil((obsEnd + OPENING_TIME_BUFFER_S) / BIN_SIZE) * BIN_SIZE;
//			guard against a collapsed window for zero-/sub-bin observed durations (floor(start)==ceil(end)):
//			keep it at least one bin wide so the activity stays performable rather than scoring as "closed".
			closing = Math.max(closing, opening + BIN_SIZE);
		}

//		recover <base>_<typical> by stripping any window suffix, then derive the (re)tagged type.
		String baseType = matcher.replaceFirst("");
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
