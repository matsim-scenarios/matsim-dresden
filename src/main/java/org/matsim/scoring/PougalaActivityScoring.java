/* *********************************************************************** *
 * project: org.matsim.*
 * PougalaActivityScoring.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2026 by the members listed in the COPYING,        *
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

import org.matsim.api.core.v01.population.Activity;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.scoring.functions.ActivityUtilityParameters;
import org.matsim.core.scoring.functions.CharyparNagelActivityScoring;
import org.matsim.core.scoring.functions.ScoringParameters;
import org.matsim.core.utils.misc.OptionalTime;

/**
 * Activity scoring following the piecewise-linear formulation of
 *
 * <blockquote>
 * <p>Pougala, J., T. Hillel and M. Bierlaire (2022/2023)<br>
 * OASIS: Optimisation-based Activity Scheduling with Integrated Simultaneous choice dimensions,<br>
 * Transportation Research Part C.</p>
 * </blockquote>
 *
 * Instead of the logarithmic "performing" utility of {@link CharyparNagelActivityScoring}, each
 * performed activity is penalized linearly for deviating from its desired schedule:
 * <ul>
 *   <li>a penalty for being <i>shorter</i> than its typical duration,</li>
 *   <li>a penalty for being <i>longer</i> than its typical duration,</li>
 *   <li>a penalty for <i>starting before</i> its desired start time,</li>
 *   <li>a penalty for <i>starting after</i> its desired start time.</li>
 * </ul>
 *
 * The typical duration is re-used from the existing {@link ActivityUtilityParameters} (i.e. the
 * <code>typicalDuration</code> of the activity in the scoring config). The desired start time is
 * not a configurable parameter yet; here we (ab)use the center between the activity's
 * <code>openingTime</code> and <code>latestStartTime</code>. If those are not both defined, no
 * start-time penalty is applied.
 *
 * The four penalty coefficients are, for now, constants shared among all activity types (see the
 * {@code *_PENALTY_PER_HOUR} fields below). They are expressed as utility per hour of deviation and
 * are negative (i.e. deviations reduce the score).
 *
 * @author michaz with claude
 */
public final class PougalaActivityScoring implements org.matsim.core.scoring.SumScoringFunction.ActivityScoring {

	// --- Penalty coefficients (utils per hour of deviation), shared among all activity types. ---
	// These are placeholders / suggested initial values; they should eventually be estimated and
	// moved into the scoring config (per activity type). All values are negative: any deviation
	// from the desired duration / start time reduces the score.
	//
	// Suggested initial values, with a mild asymmetry reflecting typical behavioural findings:
	//  - cutting an activity short is worse than overstaying it,
	//  - arriving late is worse than arriving early.

	/** Penalty per hour by which an activity is shorter than its typical duration. */
	private static final double DURATION_TOO_SHORT_PENALTY_PER_HOUR = -1.0;
	/** Penalty per hour by which an activity is longer than its typical duration. */
	private static final double DURATION_TOO_LONG_PENALTY_PER_HOUR = -0.5;
	/** Penalty per hour by which an activity starts before its desired start time. */
	private static final double START_TOO_EARLY_PENALTY_PER_HOUR = -0.5;
	/** Penalty per hour by which an activity starts after its desired start time. */
	private static final double START_TOO_LATE_PENALTY_PER_HOUR = -1.0;

	private static final double INITIAL_SCORE = 0.0;

	private final Score score = new Score();

	private final ScoringParameters params;

	public PougalaActivityScoring(final ScoringParameters params) {
		this.params = params;
	}

	@Override
	public void finish() {
	}

	@Override
	public double getScore() {
		return this.score.actDurationTooShort_util + this.score.actDurationTooLong_util
				+ this.score.actStartTooEarly_util + this.score.actStartTooLate_util;
	}

	@Override
	public void explainScore(StringBuilder out) {
		out.append("actDurationTooShort_util=").append(this.score.actDurationTooShort_util).append(ScoringFunction.SCORE_DELIMITER);
		out.append("actDurationTooShort_s=").append(this.score.actDurationTooShort_s).append(ScoringFunction.SCORE_DELIMITER);
		out.append("actDurationTooLong_util=").append(this.score.actDurationTooLong_util).append(ScoringFunction.SCORE_DELIMITER);
		out.append("actDurationTooLong_s=").append(this.score.actDurationTooLong_s).append(ScoringFunction.SCORE_DELIMITER);
		out.append("actStartTooEarly_util=").append(this.score.actStartTooEarly_util).append(ScoringFunction.SCORE_DELIMITER);
		out.append("actStartTooEarly_s=").append(this.score.actStartTooEarly_s).append(ScoringFunction.SCORE_DELIMITER);
		out.append("actStartTooLate_util=").append(this.score.actStartTooLate_util).append(ScoringFunction.SCORE_DELIMITER);
		out.append("actStartTooLate_s=").append(this.score.actStartTooLate_s);
	}

	private Score calcActScore(final double startTime, final double endTime, final Activity act) {

		ActivityUtilityParameters actParams = this.params.actParams.get(act.getType());
		if (actParams == null) {
			throw new IllegalArgumentException("acttype \"" + act.getType() + "\" is not known in utility parameters " +
					"(module name=\"scoring\" in the config file).");
		}

		Score tmpScore = new Score();

		if (actParams.isScoreAtAll()) {

			// --- duration penalties (relative to the typical duration) ---
			double duration = endTime - startTime;
			double typicalDuration = actParams.getTypicalDuration();

			double durationTooShort = Math.max(0., typicalDuration - duration);
			if (durationTooShort > 0.) {
				tmpScore.actDurationTooShort_s += durationTooShort;
				tmpScore.actDurationTooShort_util += DURATION_TOO_SHORT_PENALTY_PER_HOUR * durationTooShort / 3600.;
			}

			double durationTooLong = Math.max(0., duration - typicalDuration);
			if (durationTooLong > 0.) {
				tmpScore.actDurationTooLong_s += durationTooLong;
				tmpScore.actDurationTooLong_util += DURATION_TOO_LONG_PENALTY_PER_HOUR * durationTooLong / 3600.;
			}

			// --- start-time penalties (relative to the desired start time) ---
			// Desired start time is not a config parameter (yet); abuse the center between
			// openingTime and latestStartTime. Only applied if both are defined.
			OptionalTime openingTime = actParams.getOpeningTime();
			OptionalTime latestStartTime = actParams.getLatestStartTime();
			if (openingTime.isDefined() && latestStartTime.isDefined()) {
				double desiredStartTime = 0.5 * (openingTime.seconds() + latestStartTime.seconds());

				double startTooEarly = Math.max(0., desiredStartTime - startTime);
				if (startTooEarly > 0.) {
					tmpScore.actStartTooEarly_s += startTooEarly;
					tmpScore.actStartTooEarly_util += START_TOO_EARLY_PENALTY_PER_HOUR * startTooEarly / 3600.;
				}

				double startTooLate = Math.max(0., startTime - desiredStartTime);
				if (startTooLate > 0.) {
					tmpScore.actStartTooLate_s += startTooLate;
					tmpScore.actStartTooLate_util += START_TOO_LATE_PENALTY_PER_HOUR * startTooLate / 3600.;
				}
			}
		}
		return tmpScore;
	}

	// We only score activities for which we observe both a start and an end (i.e. the "middle"
	// activities). The first and last (wrap-around) activity of a plan are deliberately not scored,
	// because their start resp. end is not observed within the simulation period.

	@Override
	public void handleFirstActivity(Activity act) {
		// not scored: no observed start
	}

	@Override
	public void handleActivity(Activity act) {
		this.score.add(calcActScore(act.getStartTime().seconds(), act.getEndTime().seconds(), act));
	}

	@Override
	public void handleLastActivity(Activity act) {
		// not scored: no observed end
	}

	private static final class Score {

		private double actDurationTooShort_util = INITIAL_SCORE;
		private double actDurationTooShort_s = INITIAL_SCORE;
		private double actDurationTooLong_util = INITIAL_SCORE;
		private double actDurationTooLong_s = INITIAL_SCORE;
		private double actStartTooEarly_util = INITIAL_SCORE;
		private double actStartTooEarly_s = INITIAL_SCORE;
		private double actStartTooLate_util = INITIAL_SCORE;
		private double actStartTooLate_s = INITIAL_SCORE;

		private void add(Score s) {
			actDurationTooShort_util += s.actDurationTooShort_util;
			actDurationTooShort_s += s.actDurationTooShort_s;
			actDurationTooLong_util += s.actDurationTooLong_util;
			actDurationTooLong_s += s.actDurationTooLong_s;
			actStartTooEarly_util += s.actStartTooEarly_util;
			actStartTooEarly_s += s.actStartTooEarly_s;
			actStartTooLate_util += s.actStartTooLate_util;
			actStartTooLate_s += s.actStartTooLate_s;
		}
	}
}
