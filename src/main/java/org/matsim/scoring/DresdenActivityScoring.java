/* *********************************************************************** *
 * project: org.matsim.*
 * CharyparNagelActivityScoring.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2007 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.scoring;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.scoring.functions.ActivityTypeOpeningIntervalCalculator;
import org.matsim.core.scoring.functions.ActivityUtilityParameters;
import org.matsim.core.scoring.functions.OpeningIntervalCalculator;
import org.matsim.core.population.algorithms.MutateActivityTimeAllocation;
import org.matsim.core.scoring.functions.ScoringParameters;
import org.matsim.core.router.StageActivityTypeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.misc.OptionalTime;
import org.matsim.prepare.EncodeTypicalDuration;

import java.util.List;

/**
 * A copy of {@link org.matsim.core.scoring.functions.CharyparNagelActivityScoring} that, for each activity,
 * uses the plan-derived typical duration stored in the {@value EncodeTypicalDuration#TYPICAL_DURATION} attribute
 * (see {@link EncodeTypicalDuration}) when present, instead of the generic typical duration of the activity type
 * from the scoring config. This replaces the previous scheme, which encoded the typical duration in the activity
 * type itself (one config activity type per duration bin).
 * <p>
 * Because the Charypar-Nagel performing utility depends on both the typical duration and the derived zero-utility
 * duration, the zero-utility duration is recomputed from the per-activity typical duration exactly as
 * {@link ActivityUtilityParameters} does: using the activity type's configured
 * {@code typicalDurationScoreComputation} ({@code relative -> SameRelativeScore}, {@code uniform ->
 * SameAbsoluteScore}) and priority, so the curve stays self-consistent (peak at the typical duration, zero at the
 * zero-utility duration) and matches the run's configured scoring mode instead of a hardcoded variant.
 * <p>
 * When the attribute is absent (e.g. freight/commercial activities, which never receive it), the activity is
 * scored exactly like the original Charypar-Nagel activity scoring, using the type's config parameters. The base
 * class is {@code final}, so this is a copy with a modified {@code calcActScore} rather than a subclass.
 *
 * <h3>Where the attributes come from</h3>
 * At runtime, scoring is event-driven: {@code EventsToActivities} rebuilds each activity from events
 * ({@code PopulationUtils.createActivityFromLinkId}) and those reconstructions carry <em>no attributes</em> -- reading
 * attributes off the handed activity silently falls back to the config values for every activity (which is exactly
 * what happened until this was discovered: every run scored against the uniform config typical durations). So when
 * constructed with the {@link Person}, this class walks a cursor over the selected plan's <em>main</em> activities
 * and reads the attributes from the aligned plan activity, while all times still come from the realized one. Stage
 * ("interaction") activities bypass the cursor on both sides: they are never really scored and their count is not
 * invariant, whereas the main-activity sequence is identical across all of a person's plans (participation and order
 * are fixed). The plan is resolved lazily at the first scoring callback, i.e. after replanning -- scoring functions
 * are created at iteration start, <em>before</em> replanning, so capturing the selected plan at construction time
 * would walk the previous iteration's plan (whose trip structure can differ after mode choice or rerouting).
 * Activity types are compared as a safety net; on a mismatch the cursor is abandoned and scoring falls back to the
 * handed activity (i.e. to config values). Offline consumers (the VTTS analysis) construct this class without a
 * person and hand in plan activities that carry their attributes directly.
 *
 * <h3>Schedule-delay corridor</h3>
 * When armed ({@code scheduleDelayScoring}), the per-activity anchor attributes {@code initialStartTime} /
 * {@code initialEndTime} (stamped in {@code DresdenModel.stampScheduleAnchors}) act as the per-activity
 * {@code latestStartTime} / {@code earliestEndTime}: starting later than the anchored start is penalized at the
 * lateArrival rate, ending earlier than the anchored end at the earlyDeparture rate. Disarmed (the default), only
 * the type-level config values apply -- which are undefined in this scenario, so the terms are dead, exactly as
 * before. The gate sits here rather than in the config slopes because zeroing lateArrival would also soften the
 * stuck-agent penalty ({@code abortedPlanScore} derives from it) and the best-response scheduler's slopes.
 *
 * @author rashid_waraich (original); adapted for per-activity typical duration and schedule-delay anchors
 */
public final class DresdenActivityScoring implements org.matsim.core.scoring.SumScoringFunction.ActivityScoring {

	/**
	 * Attribute key under which the initial (surveyed) start time is stored on each activity (see
	 * {@code DresdenModel.stampScheduleAnchors}); the per-activity counterpart of the type-level
	 * {@code latestStartTime}, named after {@code MutateActivityTimeAllocation#INITIAL_END_TIME_ATTRIBUTE}.
	 */
	public static final String INITIAL_START_TIME_ATTRIBUTE = "initialStartTime";

	private static final double INITIAL_SCORE = 0.0;

	private final Score score = new Score();

	private static int firstLastActWarning = 0;
	private static short firstLastActOpeningTimesWarning = 0;

	private final ScoringParameters params;
	/** Config scoring parameters for the person's subpopulation; source of the per-type {@code typicalDurationScoreComputation} and priority used to recompute the zero-utility duration for a per-activity typical duration. */
	private final ScoringConfigGroup.ScoringParameterSet scoringParameterSet;
	private final OpeningIntervalCalculator openingIntervalCalculator;
	/** Whether the schedule-delay corridor (per-activity anchor attributes) is armed; see class javadoc. */
	private final boolean scheduleDelayScoring;
	/** Fail hard when a scored activity carries no positive typicalDuration attribute. The experiment's premise is
	 * that every (person-subpopulation) activity is scored against its survey-derived typical duration; a silent
	 * config-typical fallback would corrupt exactly the quantity under study. Off for subpopulations that score by
	 * config typicals by design (freight/commercial) and for legacy type-encoded populations. */
	private final boolean requireTypicalDurationAttribute;

	/** Owner of the plan the cursor aligns against; null => read attributes off the handed activities themselves. */
	private final Person person;
	/** The executed plan's main activities in order, resolved lazily at the first scoring callback (i.e. after
	 * replanning; see class javadoc); the attribute source for the realized activities handed in by the events
	 * machinery. */
	private List<Activity> planActivities;
	private int planActivityIndex = 0;

	private Activity firstActivity;
	/** Attribute source aligned with {@link #firstActivity}; the first activity is only scored at {@link #finish()}. */
	private Activity firstActivitySource;

	private static final Logger log = LogManager.getLogger(DresdenActivityScoring.class);

	/** Attribute-carrying activities are handed in directly (offline use, e.g. the VTTS analysis); no plan cursor. */
	public DresdenActivityScoring(final ScoringParameters params, final ScoringConfigGroup.ScoringParameterSet scoringParameterSet) {
		this(params, scoringParameterSet, null, false, false, new ActivityTypeOpeningIntervalCalculator(params));
	}

	public DresdenActivityScoring(final ScoringParameters params, final ScoringConfigGroup.ScoringParameterSet scoringParameterSet,
								  final Person person, final boolean scheduleDelayScoring) {
		this(params, scoringParameterSet, person, scheduleDelayScoring, false, new ActivityTypeOpeningIntervalCalculator(params));
	}

	public DresdenActivityScoring(final ScoringParameters params, final ScoringConfigGroup.ScoringParameterSet scoringParameterSet,
								  final Person person, final boolean scheduleDelayScoring,
								  final boolean requireTypicalDurationAttribute) {
		this(params, scoringParameterSet, person, scheduleDelayScoring, requireTypicalDurationAttribute, new ActivityTypeOpeningIntervalCalculator(params));
	}

	public DresdenActivityScoring(final ScoringParameters params, final ScoringConfigGroup.ScoringParameterSet scoringParameterSet,
								  final Person person, final boolean scheduleDelayScoring,
								  final boolean requireTypicalDurationAttribute,
								  final OpeningIntervalCalculator openingIntervalCalculator) {
		this.params = params;
		this.scoringParameterSet = scoringParameterSet;
		this.openingIntervalCalculator = openingIntervalCalculator;
		this.scheduleDelayScoring = scheduleDelayScoring;
		this.requireTypicalDurationAttribute = requireTypicalDurationAttribute;
		this.person = person;
	}

	/**
	 * The attribute source for a realized activity: the aligned main activity of the executed plan (advancing the
	 * cursor), otherwise the realized activity itself. Stage activities bypass the cursor on both sides -- their count
	 * varies with routing, they are not really scored, and they carry no scheduling attributes. A main-activity type
	 * mismatch is a broken model invariant (the main sequence is identical across all of a person's plans, and the
	 * plan is resolved after replanning), so it aborts the run rather than silently degrading the scoring input.
	 */
	private Activity attributeSource(Activity realized) {
		if (person == null || StageActivityTypeIdentifier.isStageActivity(realized.getType())) {
			return realized;
		}
		if (planActivities == null) {
			// lazily: at the first callback we are past replanning, so this is the executed plan
			planActivities = TripStructureUtils.getActivities(person.getSelectedPlan(), TripStructureUtils.StageActivityHandling.ExcludeStageActivities);
		}
		if (planActivityIndex >= planActivities.size()) {
			throw new RuntimeException("Person " + person.getId() + " realized more main activities than the selected plan contains "
				+ "(realized " + realized.getType() + " after " + planActivities.size() + " plan activities). "
				+ "The realized sequence must be a prefix of the executed plan; this run's scoring input is corrupt.");
		}
		Activity planned = planActivities.get(planActivityIndex++);
		if (!planned.getType().equals(realized.getType())) {
			throw new RuntimeException("Realized activity sequence of person " + person.getId() + " diverged from the selected plan "
				+ "(plan: " + planned.getType() + ", realized: " + realized.getType() + " at main-activity index " + (planActivityIndex - 1) + "). "
				+ "The main-activity sequence is invariant across a person's plans, so this indicates a broken model invariant; "
				+ "aborting instead of scoring against corrupt input.");
		}
		return planned;
	}

	@Override
	public void finish() {
		if (this.firstActivity != null) {
			handleMorningActivity();
		}
		// Else, no activity has started so far.
		// This probably means that the plan contains at most one activity.
		// We cannot handle that correctly, because we do not know what it is.
	}

	@Override
	public double getScore() {
		return this.score.actPerforming_util + this.score.actWaiting_util + this.score.actLateArrival_util + this.score.actEarlyDeparture_util;
	}

	@Override
	public void explainScore(StringBuilder out) {
		out.append("actPerforming_util=").append(this.score.actPerforming_util).append(ScoringFunction.SCORE_DELIMITER);
		out.append("actPerforming_s=").append(this.score.actPerforming_s).append(ScoringFunction.SCORE_DELIMITER);
		out.append("actWaiting_util=").append(this.score.actWaiting_util).append(ScoringFunction.SCORE_DELIMITER);
		out.append("actWaiting_s=").append(this.score.actWaiting_s).append(ScoringFunction.SCORE_DELIMITER);
		out.append("actLateArrival_util=").append(this.score.actLateArrival_util).append(ScoringFunction.SCORE_DELIMITER);
		out.append("actLateArrival_s=").append(this.score.actLateArrival_s).append(ScoringFunction.SCORE_DELIMITER);
		out.append("actEarlyDeparture_util=").append(this.score.actEarlyDeparture_util).append(ScoringFunction.SCORE_DELIMITER);
		out.append("actEarlyDeparture_s=").append(this.score.actEarlyDeparture_s);
	}

	private Score calcActScore(final double arrivalTime, final double departureTime, final Activity act) {

		ActivityUtilityParameters actParams = this.params.actParams.get(act.getType());
		if (actParams == null) {
			throw new IllegalArgumentException("acttype \"" + act.getType() + "\" is not known in utility parameters " +
					"(module name=\"scoring\" in the config file).");
		}

		Score tmpScore = new Score();

		if (actParams.isScoreAtAll()) {
			/* Calculate the times the agent actually performs the
			 * activity.  The facility must be open for the agent to
			 * perform the activity.  If it's closed, but the agent is
			 * there, the agent must wait instead of performing the
			 * activity (until it opens).
			 *
			 *                                             Interval during which
			 * Relationship between times:                 activity is performed:
			 *
			 *      O________C A~~D  ( 0 <= C <= A <= D )   D...D (not performed)
			 * A~~D O________C       ( A <= D <= O <= C )   D...D (not performed)
			 *      O__A+++++C~~D    ( O <= A <= C <= D )   A...C
			 *      O__A++D__C       ( O <= A <= D <= C )   A...D
			 *   A~~O++++++++C~~D    ( A <= O <= C <= D )   O...C
			 *   A~~O+++++D__C       ( A <= O <= D <= C )   O...D
			 *
			 * Legend:
			 *  A = arrivalTime    (when agent gets to the facility)
			 *  D = departureTime  (when agent leaves the facility)
			 *  O = openingTime    (when facility opens)
			 *  C = closingTime    (when facility closes)
			 *  + = agent performs activity
			 *  ~ = agent waits (agent at facility, but not performing activity)
			 *  _ = facility open, but agent not there
			 *
			 * assume O <= C
			 * assume A <= D
			 */

			OptionalTime[] openingInterval = openingIntervalCalculator.getOpeningInterval(act);
			OptionalTime openingTime = openingInterval[0];
			OptionalTime closingTime = openingInterval[1];

			double activityStart = arrivalTime;
			double activityEnd = departureTime;

			if (openingTime.isDefined() && arrivalTime < openingTime.seconds()) {
				activityStart = openingTime.seconds();
			}
			if (closingTime.isDefined() && closingTime.seconds() < departureTime) {
				activityEnd = closingTime.seconds();
			}
			if (openingTime.isDefined() && closingTime.isDefined()
					&& (openingTime.seconds() > departureTime || closingTime.seconds() < arrivalTime)) {
				// agent could not perform action
				activityStart = departureTime;
				activityEnd = departureTime;
			}
			double duration = activityEnd - activityStart;

			// disutility if too early:
			if (arrivalTime < activityStart) {
				// agent arrives to early, has to wait
				double waitTime = activityStart - arrivalTime;
				tmpScore.actWaiting_s += waitTime;
				tmpScore.actWaiting_util += this.params.marginalUtilityOfWaiting_s * waitTime;
			}

			// disutility if too late: when the schedule-delay corridor is armed, the per-activity initial (surveyed)
			// start time replaces the type-level latestStartTime (like the typical duration below).
			OptionalTime latestStartTime = scheduleDelayScoring
				? anchorTime(act, INITIAL_START_TIME_ATTRIBUTE, actParams.getLatestStartTime())
				: actParams.getLatestStartTime();
			if (latestStartTime.isDefined() && (activityStart > latestStartTime.seconds())) {
				double lateTime = activityStart - latestStartTime.seconds();
				tmpScore.actLateArrival_s += lateTime;
				tmpScore.actLateArrival_util += this.params.marginalUtilityOfLateArrival_s * lateTime;
			}

			// Use the plan-derived typical duration from the activity attribute when present, otherwise the
			// generic typical duration of the activity type. The zero-utility duration is recomputed from the
			// typical duration the same way ActivityUtilityParameters does, so both stay consistent.
			double typicalDuration;
			double zeroUtilityDuration_h;
			Object typicalDurationAttribute = act.getAttributes().getAttribute(EncodeTypicalDuration.TYPICAL_DURATION);
			if (typicalDurationAttribute != null && ((Number) typicalDurationAttribute).doubleValue() > 0) {
				typicalDuration = ((Number) typicalDurationAttribute).doubleValue();
				zeroUtilityDuration_h = recomputeZeroUtilityDuration_h(act.getType(), typicalDuration);
			} else if (requireTypicalDurationAttribute) {
				throw new RuntimeException("Activity of type " + act.getType() + (person != null ? " of person " + person.getId() : "")
					+ " carries no positive typicalDuration attribute (" + typicalDurationAttribute + "), but this run requires "
					+ "every scored activity to use its survey-derived typical duration. Either the population was not "
					+ "preprocessed (EncodeTypicalDuration) or the attribute was lost; for legacy type-encoded populations "
					+ "pass --allow-config-typical-durations.");
			} else {
				typicalDuration = actParams.getTypicalDuration();
				zeroUtilityDuration_h = actParams.getZeroUtilityDuration_h();
			}

			tmpScore.actPerforming_s += duration;

			if ( this.params.usingOldScoringBelowZeroUtilityDuration ) {
				if (duration > 0) {
					double utilPerf = this.params.marginalUtilityOfPerforming_s * typicalDuration
							* Math.log((duration / 3600.0) / zeroUtilityDuration_h);
					double utilWait = this.params.marginalUtilityOfWaiting_s * duration;

					tmpScore.actPerforming_util += Math.max(0, Math.max(utilPerf, utilWait));
				} else {
					tmpScore.actLateArrival_util += 2*this.params.marginalUtilityOfLateArrival_s*Math.abs(duration);
				}
			} else {
				if ( duration >= 3600.*zeroUtilityDuration_h ) {
					double utilPerf = this.params.marginalUtilityOfPerforming_s * typicalDuration
							* Math.log((duration / 3600.0) / zeroUtilityDuration_h);
					// also removing the "wait" alternative scoring.
					tmpScore.actPerforming_util += utilPerf ;
				} else {
					// below zeroUtilityDuration, we linearly extend the slope ...:
					double slopeAtZeroUtility = this.params.marginalUtilityOfPerforming_s * typicalDuration / ( 3600.*zeroUtilityDuration_h ) ;
					// (this slope is actually always beta_perf * e !!)

					if ( slopeAtZeroUtility < 0. ) {
						// (beta_perf might be = 0)
						System.err.println("beta_perf: " + this.params.marginalUtilityOfPerforming_s);
						System.err.println("typicalDuration: " + typicalDuration );
						System.err.println( "zero utl duration: " + zeroUtilityDuration_h );
						throw new RuntimeException( "slope at zero utility < 0.; this should not happen ...");
					}
					double durationUnderrun = zeroUtilityDuration_h*3600. - duration ;
					if ( durationUnderrun < 0. ) {
						throw new RuntimeException( "durationUnderrun < 0; this should not happen ...") ;
					}
					tmpScore.actPerforming_util -= slopeAtZeroUtility * durationUnderrun ;
				}

			}

			// disutility if stopping too early: when the schedule-delay corridor is armed, the per-activity initial
			// (surveyed) end time replaces the type-level earliestEndTime.
			OptionalTime earliestEndTime = scheduleDelayScoring
				? anchorTime(act, MutateActivityTimeAllocation.INITIAL_END_TIME_ATTRIBUTE, actParams.getEarliestEndTime())
				: actParams.getEarliestEndTime();
			if ((earliestEndTime.isDefined()) && (activityEnd < earliestEndTime.seconds())) {
				double earlyDeparture = earliestEndTime.seconds() - activityEnd;
				tmpScore.actEarlyDeparture_s += earlyDeparture;
				tmpScore.actEarlyDeparture_util += this.params.marginalUtilityOfEarlyDeparture_s * earlyDeparture;
			}

			// disutility if going to away to late
			if (activityEnd < departureTime) {
				double waiting = departureTime - activityEnd;
				tmpScore.actWaiting_s += waiting;
				tmpScore.actWaiting_util += this.params.marginalUtilityOfWaiting_s * waiting;
			}

			// disutility if duration was too short
			OptionalTime minimalDuration = actParams.getMinimalDuration();
			if ((minimalDuration.isDefined()) && (duration < minimalDuration.seconds())) {
				double earlyDeparture = minimalDuration.seconds() - duration;
				tmpScore.actEarlyDeparture_s += earlyDeparture;
				tmpScore.actEarlyDeparture_util += this.params.marginalUtilityOfEarlyDeparture_s * earlyDeparture;
			}
		}
		return tmpScore;
	}

	/** The per-activity schedule-delay anchor from the given attribute, or the type-level fallback where not stamped. */
	private static OptionalTime anchorTime(Activity act, String attribute, OptionalTime typeLevelFallback) {
		Object value = act.getAttributes().getAttribute(attribute);
		if (value instanceof Number number) {
			return OptionalTime.defined(number.doubleValue());
		}
		return typeLevelFallback;
	}

	/**
	 * Recompute the Charypar-Nagel zero-utility duration (in hours) for a per-activity typical duration, using the
	 * same {@link ActivityUtilityParameters.ZeroUtilityComputation} ({@code relative -> SameRelativeScore},
	 * {@code uniform -> SameAbsoluteScore}) and priority that {@link ActivityUtilityParameters.Builder} selects for
	 * this activity type from the scoring config. This keeps the per-activity typical duration consistent with the
	 * run's configured {@code typicalDurationScoreComputation}, instead of hardcoding one variant.
	 */
	private double recomputeZeroUtilityDuration_h(String activityType, double typicalDuration_s) {
		ScoringConfigGroup.ActivityParams activityParams = scoringParameterSet.getActivityParams(activityType);
		ActivityUtilityParameters.ZeroUtilityComputation computation = switch (activityParams.getTypicalDurationScoreComputation()) {
			case relative -> new ActivityUtilityParameters.SameRelativeScore();
			case uniform -> new ActivityUtilityParameters.SameAbsoluteScore();
		};
		return computation.computeZeroUtilityDuration_s(activityParams.getPriority(), typicalDuration_s) / 3600.0;
	}

	private void handleOvernightActivity(Activity lastActivity, Activity lastActivitySource) {
		assert firstActivity != null;
		assert lastActivity != null;


		if (lastActivity.getType().equals(this.firstActivity.getType()) || this.firstActivity.getType().equals("not specified") ) {
			// the first Act and the last Act have the same type:

			// yyyy find better way to encode "not specified".  It is quite common for travel surveys that the type of the
			// first activity is not encoded at all, and then we can as well assume that it is the same as that of the last.  kai, sep'16

			if (firstLastActOpeningTimesWarning <= 10) {
				OptionalTime[] openInterval = openingIntervalCalculator.getOpeningInterval(lastActivity);
				if (openInterval[0].isDefined() || openInterval[1].isDefined()){
					log.warn("There are opening or closing times defined for the first and last activity. The correctness of the scoring function can thus not be guaranteed.");
					log.warn("first activity: " + firstActivity ) ;
					log.warn("last activity: " + lastActivity ) ;
					if (firstLastActOpeningTimesWarning == 10) {
						log.warn("Additional warnings of this type are suppressed.");
					}
					firstLastActOpeningTimesWarning++;
				}
			}

			Score calcActScore = calcActScore(lastActivity.getStartTime().seconds(),
					this.firstActivity.getEndTime().seconds() + 24 * 3600, lastActivitySource);
			this.score.add(calcActScore); // SCENARIO_DURATION
		} else {
			// the first Act and the last Act have NOT the same type:
			if (this.params.scoreActs) {
				int last=0 ;
				if (firstLastActWarning <= last) {
					log.warn("The first and the last activity do not have the same type. " ) ;
					log.warn( "Will score the first activity from midnight to its end, and the last activity from its start to midnight.") ;
					log.warn("Because of the nonlinear function, this is not the same as scoring from start to end.");
					log.warn("first activity: " + firstActivity ) ;
					log.warn("last activity: " + lastActivity ) ;
					log.warn("This may also happen when plans are not completed when the simulation ends.") ;
					if (firstLastActWarning == last) {
						log.warn("Additional warnings of this type are suppressed.");
					}
					firstLastActWarning++;
				}

				// score first activity
				this.score.add(calcActScore(0.0, this.firstActivity.getEndTime().seconds(), firstActivitySource));
				// score last activity
				this.score.add(calcActScore(lastActivity.getStartTime().seconds(),
						this.params.simulationPeriodInDays * 24 * 3600, lastActivitySource));
			}
		}
	}

	private void handleMorningActivity() {
		assert firstActivity != null;
		// score first activity
		this.score.add(calcActScore(0.0, this.firstActivity.getEndTime().seconds(), firstActivitySource));
	}

	@Override
	public void handleFirstActivity(Activity act) {
		assert act != null;
		this.firstActivity = act;
		this.firstActivitySource = attributeSource(act);
	}

	@Override
	public void handleActivity(Activity act) {
		this.score.add(calcActScore(act.getStartTime().seconds(), act.getEndTime().seconds(), attributeSource(act)));
	}

	@Override
	public void handleLastActivity(Activity act) {
		this.handleOvernightActivity(act, attributeSource(act));
		this.firstActivity = null;
	}


	private static final class Score {

		private double actPerforming_util = INITIAL_SCORE;
		private double actPerforming_s = INITIAL_SCORE;
		private double actWaiting_util = INITIAL_SCORE;
		private double actWaiting_s = INITIAL_SCORE;
		private double actLateArrival_util = INITIAL_SCORE;
		private double actLateArrival_s = INITIAL_SCORE;

		private double actEarlyDeparture_util = INITIAL_SCORE;
		private double actEarlyDeparture_s = INITIAL_SCORE;

		private void add(Score s) {
			actPerforming_util += s.actPerforming_util;
			actPerforming_s += s.actPerforming_s;
			actWaiting_util += s.actWaiting_util;
			actWaiting_s += s.actWaiting_s;
			actLateArrival_util += s.actLateArrival_util;
			actLateArrival_s += s.actLateArrival_s;
			actEarlyDeparture_util += s.actEarlyDeparture_util;
			actEarlyDeparture_s += s.actEarlyDeparture_s;
		}

	}

}
