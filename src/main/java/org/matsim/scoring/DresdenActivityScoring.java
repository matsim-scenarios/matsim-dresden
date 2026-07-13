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
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.scoring.functions.ActivityTypeOpeningIntervalCalculator;
import org.matsim.core.scoring.functions.ActivityUtilityParameters;
import org.matsim.core.scoring.functions.OpeningIntervalCalculator;
import org.matsim.core.scoring.functions.ScoringParameters;
import org.matsim.core.utils.misc.OptionalTime;
import org.matsim.prepare.EncodeTypicalDuration;

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
 * @author rashid_waraich (original); adapted for per-activity typical duration
 */
public final class DresdenActivityScoring implements org.matsim.core.scoring.SumScoringFunction.ActivityScoring {

	private static final double INITIAL_SCORE = 0.0;

	private final Score score = new Score();

	private static int firstLastActWarning = 0;
	private static short firstLastActOpeningTimesWarning = 0;

	private final ScoringParameters params;
	/** Config scoring parameters for the person's subpopulation; source of the per-type {@code typicalDurationScoreComputation} and priority used to recompute the zero-utility duration for a per-activity typical duration. */
	private final ScoringConfigGroup.ScoringParameterSet scoringParameterSet;
	private final OpeningIntervalCalculator openingIntervalCalculator;

	private Activity firstActivity;

	private static final Logger log = LogManager.getLogger(DresdenActivityScoring.class);

	public DresdenActivityScoring(final ScoringParameters params, final ScoringConfigGroup.ScoringParameterSet scoringParameterSet) {
		this(params, scoringParameterSet, new ActivityTypeOpeningIntervalCalculator(params));
	}

	public DresdenActivityScoring(final ScoringParameters params, final ScoringConfigGroup.ScoringParameterSet scoringParameterSet, final OpeningIntervalCalculator openingIntervalCalculator) {
		this.params = params;
		this.scoringParameterSet = scoringParameterSet;
		this.openingIntervalCalculator = openingIntervalCalculator;
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

			// disutility if too late:
			OptionalTime latestStartTime = actParams.getLatestStartTime();
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

			// disutility if stopping too early
			OptionalTime earliestEndTime = actParams.getEarliestEndTime();
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

	private void handleOvernightActivity(Activity lastActivity) {
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
					this.firstActivity.getEndTime().seconds() + 24 * 3600, lastActivity);
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
				this.score.add(calcActScore(0.0, this.firstActivity.getEndTime().seconds(), firstActivity));
				// score last activity
				this.score.add(calcActScore(lastActivity.getStartTime().seconds(),
						this.params.simulationPeriodInDays * 24 * 3600, lastActivity));
			}
		}
	}

	private void handleMorningActivity() {
		assert firstActivity != null;
		// score first activity
		this.score.add(calcActScore(0.0, this.firstActivity.getEndTime().seconds(), firstActivity));
	}

	@Override
	public void handleFirstActivity(Activity act) {
		assert act != null;
		this.firstActivity = act;
	}

	@Override
	public void handleActivity(Activity act) {
		this.score.add(calcActScore(act.getStartTime().seconds(), act.getEndTime().seconds(), act));
	}

	@Override
	public void handleLastActivity(Activity act) {
		this.handleOvernightActivity(act);
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
