package org.matsim.replanning.bestresponse;

import org.matsim.core.utils.misc.OptionalTime;

import java.util.List;

/**
 * The linearized daily-scheduling problem for a single agent -- the interface between
 * {@link BestResponseSchedulePlanAlgorithm} (which extracts it from a plan) and a {@link ScheduleSolver} (which solves
 * it). This is a drastically simplified, MATSim-flavoured version of the scheduling model of Pougala, Hillel &
 * Bierlaire, "Capturing trade-offs between daily scheduling choices" (2022): no participation choice (activities are
 * neither dropped nor added) and no sequence choice (the activity order is fixed). Only the timing is optimized.
 *
 * <h3>Decision variables</h3>
 * The activity order and the inter-activity travel times {@link Act#travelTimeBefore} are fixed, so the whole schedule
 * is determined by the activity durations {@code d_0 .. d_{n-1}} (n = number of activities). With {@code start_0 =
 * dayStart} the chain is
 * <pre>
 *   start_i = end_{i-1} + travelTimeBefore_i,   end_i = start_i + d_i,
 * </pre>
 * so every {@code start_i} and {@code end_i} is an affine function of the {@code d_j}. The variables are therefore the
 * {@code d_i >= 0}; the non-negativity is what structurally rules out the zero/negative-duration garbling the random
 * time mutator produces.
 *
 * <h3>Objective (maximize approximate utility = minimize penalty)</h3>
 * A separable, piecewise-linear penalty per activity, linear in the variables so the whole thing is an LP:
 * <ul>
 *   <li><b>Duration deviation</b> around the typical duration {@code t*}: {@code durShortSlope*(t* - d_i)} if shorter,
 *   {@code durLongSlope*(d_i - t*)} if longer. (Linearize with the standard split into non-negative under/over parts.)</li>
 *   <li><b>End-time deviation</b> around a target end time {@code e*} (only where {@link Act#targetEndTime} is defined):
 *   {@code endEarlySlope*(e* - end_i)} if early, {@code endLateSlope*(end_i - e*)} if late. Because {@code end_i}
 *   couples to all upstream durations, this term is what makes the problem a genuine chain LP rather than n independent
 *   one-dimensional problems.</li>
 *   <li><b>Random error</b> {@link Act#randomError} (seconds): a per-activity, per-replanning perturbation of the
 *   preferred duration, equivalent to a random linear term in the objective; it is what makes the best response
 *   stochastic (Pougala's additive error), replacing the random mutation as the source of exploration.</li>
 * </ul>
 * The penalty slopes are filled from the configured Charypar-Nagel parameters (see
 * {@link BestResponseSchedulePlanAlgorithm}); the units are utils per second.
 */
public final class ScheduleProblem {

	/** One activity's contribution to the schedule and the objective. All times in seconds, all slopes in utils/second. */
	public static final class Act {
		public final String type;
		/** true => the activity carries an end time and the solution is written back as an end time; false => a maximum duration. */
		public final boolean endTimeBased;
		/** true => the (open) last activity of the day; it absorbs the remaining time and is left unscheduled. */
		public final boolean lastActivity;
		/** Fixed travel time (s) of the trip arriving at this activity; 0 for the first activity. */
		public final double travelTimeBefore;
		/** Preferred (typical) duration t*, seconds. */
		public final double typicalDuration;
		public final double durShortSlope;
		public final double durLongSlope;
		/** Desired end time e*, or undefined if this activity has no scheduling anchor. */
		public final OptionalTime targetEndTime;
		public final double endEarlySlope;
		public final double endLateSlope;
		/** Random error on the preferred duration (s), drawn per replanning; see class javadoc. */
		public final double randomError;

		public Act( String type, boolean endTimeBased, boolean lastActivity, double travelTimeBefore, double typicalDuration,
					double durShortSlope, double durLongSlope, OptionalTime targetEndTime, double endEarlySlope,
					double endLateSlope, double randomError ) {
			this.type = type;
			this.endTimeBased = endTimeBased;
			this.lastActivity = lastActivity;
			this.travelTimeBefore = travelTimeBefore;
			this.typicalDuration = typicalDuration;
			this.durShortSlope = durShortSlope;
			this.durLongSlope = durLongSlope;
			this.targetEndTime = targetEndTime;
			this.endEarlySlope = endEarlySlope;
			this.endLateSlope = endLateSlope;
			this.randomError = randomError;
		}
	}

	/** Activities in plan order. */
	public final List<Act> activities;
	/** Start of the day (s); the first activity starts here. Usually 0. */
	public final double dayStart;

	public ScheduleProblem( List<Act> activities, double dayStart ) {
		this.activities = activities;
		this.dayStart = dayStart;
	}
}
