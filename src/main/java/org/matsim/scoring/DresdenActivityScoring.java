package org.matsim.scoring;

import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.population.algorithms.MutateActivityTimeAllocation;
import org.matsim.core.router.StageActivityTypeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.scoring.SumScoringFunction;
import org.matsim.core.scoring.functions.ActivityUtilityParameters;
import org.matsim.core.scoring.functions.ScoringParameters;
import org.matsim.core.utils.misc.OptionalTime;

import java.util.List;

/**
 * The Dresden-specific additive activity-scoring term: the schedule-delay corridor. Per-activity anchor attributes
 * {@value #INITIAL_START_TIME_ATTRIBUTE} / {@code initialEndTime} (stamped in {@code DresdenModel.stampScheduleAnchors})
 * act as a per-activity latestStartTime / earliestEndTime: starting later than the anchored start is penalized at the
 * lateArrival rate, ending earlier than the anchored end at the earlyDeparture rate. This class is added to the
 * {@link SumScoringFunction} <em>alongside</em> the core activity scoring only when the corridor is armed
 * ({@link DresdenScoringConfigGroup#isScheduleDelayScoring()}); disarmed, it is simply not added. The gate sits here
 * rather than in the config slopes because zeroing lateArrival would also soften the stuck-agent penalty
 * ({@code abortedPlanScore} derives from it) and the best-response scheduler's slopes.
 * <p>
 * This class used to be a full copy of {@code CharyparNagelActivityScoring} that additionally scored each activity
 * against a per-activity typical duration. That part has been consolidated into MATSim core: the run now uses the
 * stock {@link org.matsim.core.scoring.functions.CharyparNagelActivityScoring} with an
 * {@link org.matsim.core.scoring.functions.ActivityAttributeTypicalDurationCalculator} (see
 * {@link DresdenScoringFunctionFactory}), and only the corridor terms remain here.
 * <p>
 * Because the terms are additive on top of the core scoring, the config must not define type-level
 * {@code latestStartTime} / {@code earliestEndTime} for an anchored activity's type -- the core scoring would apply
 * them in addition to the anchors (the old combined implementation had the anchors <em>replace</em> the type-level
 * values). This scenario defines neither; a violation aborts the run. Unlike the core scoring, the corridor also
 * ignores opening times (the interval an activity is performed in is taken as arrival to departure); this scenario
 * defines no opening times either.
 *
 * <h3>Where the attributes come from</h3>
 * At runtime, scoring is event-driven and the handed activity reconstructions carry no attributes (see the class
 * javadoc of {@code CharyparNagelActivityScoring} in core). So when constructed with the {@link Person}, this class
 * walks a cursor over the selected plan's main activities exactly like the core scoring does, reading the anchors from
 * the aligned plan activity while all times come from the handed one; the plan is resolved lazily at the first scoring
 * callback (after replanning). Unlike core, which warns and falls back, a misalignment aborts the run: the
 * main-activity sequence is invariant across a person's plans in this model, so a divergence is corrupt scoring input.
 * Offline consumers (the VTTS analysis) construct this class without a person and hand in activities that carry their
 * attributes directly.
 *
 * @author rashid_waraich (original Charypar-Nagel structure); adapted for schedule-delay anchors
 */
public final class DresdenActivityScoring implements SumScoringFunction.ActivityScoring {

	/**
	 * Attribute key under which the initial (surveyed) start time is stored on each activity (see
	 * {@code DresdenModel.stampScheduleAnchors}); the per-activity counterpart of the type-level
	 * {@code latestStartTime}, named after {@code MutateActivityTimeAllocation#INITIAL_END_TIME_ATTRIBUTE}.
	 */
	public static final String INITIAL_START_TIME_ATTRIBUTE = "initialStartTime";

	private final ScoringParameters params;

	/** Owner of the plan the cursor aligns against; null => read attributes off the handed activities themselves. */
	private final Person person;
	private List<Activity> planActivities;
	private int planActivityIndex = 0;

	private Activity firstActivity;
	/** Attribute source aligned with {@link #firstActivity}; the first activity is only scored at {@link #finish()}. */
	private Activity firstActivitySource;

	private double lateArrival_s = 0.;
	private double lateArrival_util = 0.;
	private double earlyDeparture_s = 0.;
	private double earlyDeparture_util = 0.;

	public DresdenActivityScoring(final ScoringParameters params, final Person person) {
		this.params = params;
		this.person = person;
	}

	/**
	 * The attribute source for a handed activity: the aligned main activity of the executed plan (advancing the
	 * cursor), otherwise the handed activity itself. Stage activities bypass the cursor on both sides -- their count
	 * varies with routing, they are not really scored, and they carry no scheduling attributes. A main-activity type
	 * mismatch is a broken model invariant (the main sequence is identical across all of a person's plans, and the
	 * plan is resolved after replanning), so it aborts the run rather than silently degrading the scoring input.
	 */
	private Activity attributeSource(Activity handed) {
		if (person == null || StageActivityTypeIdentifier.isStageActivity(handed.getType())) {
			return handed;
		}
		if (planActivities == null) {
			// lazily: at the first callback we are past replanning, so this is the executed plan
			planActivities = TripStructureUtils.getActivities(person.getSelectedPlan(), TripStructureUtils.StageActivityHandling.ExcludeStageActivities);
		}
		if (planActivityIndex >= planActivities.size()) {
			throw new RuntimeException("Person " + person.getId() + " realized more main activities than the selected plan contains "
				+ "(realized " + handed.getType() + " after " + planActivities.size() + " plan activities). "
				+ "The realized sequence must be a prefix of the executed plan; this run's scoring input is corrupt.");
		}
		Activity planned = planActivities.get(planActivityIndex++);
		if (!planned.getType().equals(handed.getType())) {
			throw new RuntimeException("Realized activity sequence of person " + person.getId() + " diverged from the selected plan "
				+ "(plan: " + planned.getType() + ", realized: " + handed.getType() + " at main-activity index " + (planActivityIndex - 1) + "). "
				+ "The main-activity sequence is invariant across a person's plans, so this indicates a broken model invariant; "
				+ "aborting instead of scoring against corrupt input.");
		}
		return planned;
	}

	/**
	 * The corridor terms for one activity performed from {@code arrival} to {@code departure}, with the anchors read
	 * from {@code source}. The windows mirror the core activity scoring: a middle activity is its own interval, the
	 * first activity runs from 0 to its end, the wrap-around activity from its start to the first end + 24h, and a
	 * non-wrapping last activity from its start to the end of the simulation period.
	 */
	private void scoreCorridor(double arrival, double departure, Activity act, Activity source) {
		OptionalTime anchorStart = anchorTime(source, INITIAL_START_TIME_ATTRIBUTE);
		if (anchorStart.isDefined()) {
			requireNoTypeLevelValue(act, ActivityUtilityParameters::getLatestStartTime, "latestStartTime");
			if (arrival > anchorStart.seconds()) {
				double late = arrival - anchorStart.seconds();
				this.lateArrival_s += late;
				this.lateArrival_util += this.params.marginalUtilityOfLateArrival_s * late;
			}
		}
		OptionalTime anchorEnd = anchorTime(source, MutateActivityTimeAllocation.INITIAL_END_TIME_ATTRIBUTE);
		if (anchorEnd.isDefined()) {
			requireNoTypeLevelValue(act, ActivityUtilityParameters::getEarliestEndTime, "earliestEndTime");
			if (departure < anchorEnd.seconds()) {
				double early = anchorEnd.seconds() - departure;
				this.earlyDeparture_s += early;
				this.earlyDeparture_util += this.params.marginalUtilityOfEarlyDeparture_s * early;
			}
		}
	}

	/** The per-activity schedule-delay anchor from the given attribute, or undefined where not stamped. */
	private static OptionalTime anchorTime(Activity act, String attribute) {
		Object value = act.getAttributes().getAttribute(attribute);
		if (value instanceof Number number) {
			return OptionalTime.defined(number.doubleValue());
		}
		return OptionalTime.undefined();
	}

	/**
	 * An anchored activity whose type also defines the corresponding type-level config value would be penalized
	 * twice (the core scoring applies the type-level value, this class the anchor); the old combined implementation
	 * had the anchor replace the type-level value. This scenario defines no type-level values, so this is a
	 * misconfiguration: abort rather than double-count.
	 */
	private void requireNoTypeLevelValue(Activity act, java.util.function.Function<ActivityUtilityParameters, OptionalTime> getter, String name) {
		ActivityUtilityParameters actParams = this.params.actParams.get(act.getType());
		if (actParams != null && getter.apply(actParams).isDefined()) {
			throw new RuntimeException("Activity type " + act.getType() + " defines a type-level " + name + " AND its activities "
				+ "carry schedule-delay anchors; the corridor terms would double-count on top of the core scoring. "
				+ "Remove the type-level value (the anchors replace it).");
		}
	}

	@Override
	public void handleFirstActivity(Activity act) {
		assert act != null;
		this.firstActivity = act;
		this.firstActivitySource = attributeSource(act);
	}

	@Override
	public void handleActivity(Activity act) {
		scoreCorridor(act.getStartTime().seconds(), act.getEndTime().seconds(), act, attributeSource(act));
	}

	@Override
	public void handleLastActivity(Activity act) {
		Activity source = attributeSource(act);
		if (act.getType().equals(this.firstActivity.getType()) || this.firstActivity.getType().equals("not specified")) {
			// wrap-around: one activity from the last start to the first end + 24h, anchored by the last activity
			scoreCorridor(act.getStartTime().seconds(), this.firstActivity.getEndTime().seconds() + 24 * 3600, act, source);
		} else {
			scoreCorridor(0.0, this.firstActivity.getEndTime().seconds(), this.firstActivity, this.firstActivitySource);
			scoreCorridor(act.getStartTime().seconds(), this.params.simulationPeriodInDays * 24 * 3600, act, source);
		}
		this.firstActivity = null;
	}

	@Override
	public void finish() {
		if (this.firstActivity != null) {
			// no last activity was handled; score the first (morning) activity like the core scoring does
			scoreCorridor(0.0, this.firstActivity.getEndTime().seconds(), this.firstActivity, this.firstActivitySource);
		}
	}

	@Override
	public double getScore() {
		return this.lateArrival_util + this.earlyDeparture_util;
	}

	@Override
	public void explainScore(StringBuilder out) {
		out.append("actScheduleDelayLateArrival_util=").append(this.lateArrival_util).append(ScoringFunction.SCORE_DELIMITER);
		out.append("actScheduleDelayLateArrival_s=").append(this.lateArrival_s).append(ScoringFunction.SCORE_DELIMITER);
		out.append("actScheduleDelayEarlyDeparture_util=").append(this.earlyDeparture_util).append(ScoringFunction.SCORE_DELIMITER);
		out.append("actScheduleDelayEarlyDeparture_s=").append(this.earlyDeparture_s);
	}

}
