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
 * The activity order and the inter-activity travel times {@link Act#travelTimeBefore} are fixed, so the schedule is
 * determined by the durations {@code d_0 .. d_{n-2}} of all activities <em>except the last</em>. The last (overnight)
 * activity canonically has neither a duration nor an end -- we arrive when we arrive, and it never ends -- so it gets
 * no decision variable and nothing is written back to it. With {@code start_0 = dayStart} the chain is
 * <pre>
 *   start_i = end_{i-1} + travelTimeBefore_i,   end_i = start_i + d_i,
 * </pre>
 * so every {@code start_i} and {@code end_i} is an affine function of the {@code d_j}. The variables are therefore the
 * {@code d_i >= 0}; the non-negativity is what structurally rules out the zero/negative-duration garbling the random
 * time mutator produces. The last activity's duration is not a separate variable but follows from the others: the
 * affine expression {@code dayEnd - start_lastActivity} stands in for it wherever it is scored -- no variable, no
 * constraint, but a regular presence in the objective (see below).
 *
 * <h3>Objective (maximize approximate utility = minimize penalty)</h3>
 * A piecewise-linear penalty per activity, linear in the variables so the whole thing is an LP:
 * <ul>
 *   <li><b>Duration deviation</b> around the typical duration {@code t*}, for every activity: for the last activity
 *   of a non-wrap-around plan the implied duration {@code dayEnd - start_lastActivity} takes the place of {@code d_i}
 *   (it is just a regular, separate activity; only its duration happens to be determined by the others), so its
 *   preferred duration shapes the schedule like everyone else's. The penalty is
 *   {@code durShortSlope*(t* - d_i)} if shorter, {@code durLongSlope*(d_i - t*)} if longer -- with a second, steeper
 *   under-run segment: shortfall beyond half of {@code t*} costs {@code durVeryShortSlope} per second. This mirrors
 *   the increasing marginal utility of the concave Charypar-Nagel performing term at short durations (its marginal at
 *   a quarter of {@code t*} is four times the one at {@code t*}) and, chosen above the late-arrival slope, makes
 *   squeezing an activity toward zero a last resort instead of a free tie-breaking vertex. (Linearize with the
 *   standard split into non-negative under/over parts, the first under part capped at half of {@code t*}.)</li>
 *   <li><b>Wrap-around</b> ({@link #wrapAround}; first and last activity have the same type): MATSim scores the pair
 *   as one activity, so instead of individual first- and last-activity terms there is one joint duration term on
 *   {@code d_0 + (dayEnd - start_lastActivity)} around the combined wrapped typical duration (stamped on the first
 *   activity, see {@link org.matsim.prepare.EncodeTypicalDuration}). Note that {@code d_0} cancels out of this
 *   expression (a longer morning stay delays the evening return 1:1), so the joint term constrains only the middle
 *   durations; the first activity is held by its end-time anchor.</li>
 *   <li><b>End-time deviation</b> around a target end time {@code e*} (only where {@link Act#targetEndTime} is
 *   defined, and never for the last activity): {@code endEarlySlope*(e* - end_i)} if early,
 *   {@code endLateSlope*(end_i - e*)} if late. Because {@code end_i} couples to all upstream durations, this term is
 *   what makes the problem a genuine chain LP rather than n independent one-dimensional problems.</li>
 *   <li><b>Random error</b>: a per-activity, per-replanning perturbation {@code N(0, sigma)} (sigma in seconds) added
 *   to the <em>target end times</em> {@code e*}, i.e. to the kink positions of the anchor penalties. It is what makes
 *   the best response stochastic, replacing the random mutation as the source of exploration. (A Pougala-style
 *   additive error <em>term</em> per penalty would be a constant w.r.t. the decision variables and thus inert here:
 *   it only matters across discrete alternatives such as participation or mode, which this problem does not have.
 *   Perturbing the slopes instead would risk negative coefficients, which break the piecewise-linear split. The
 *   typical durations are not perturbed: an absolute sigma sized for end times dwarfs short activities' typicals and
 *   would clamp them to zero; a duration-taste randomization would need a relative scale.)</li>
 * </ul>
 * The penalty slopes are filled unperturbed from the configured Charypar-Nagel parameters (see
 * {@link BestResponseSchedulePlanAlgorithm}); the units are utils per second.
 * <p>
 * Note that with a single global performing rate, all duration terms share the same slopes, so trades between two
 * activities that are on the same side of their kinks tie exactly and the LP objective has flat plateaus; the solver
 * then returns an arbitrary vertex (a kink) of the plateau. This is an inherent fidelity loss of linearizing the
 * strictly concave Charypar-Nagel utility; the end-time anchors (whose slopes differ from the duration slopes) break
 * most such ties in practice.
 */
public final class ScheduleProblem {

	/** One activity's contribution to the schedule and the objective. All times in seconds, all slopes in utils/second. */
	public static final class Act {
		public final String type;
		/** true => the activity carries an end time and the solution is written back as an end time; false => a maximum duration. */
		public final boolean endTimeBased;
		/** true => the (open) last activity of the day; it has no decision variable and is left unscheduled. */
		public final boolean lastActivity;
		/** Fixed travel time (s) of the trip arriving at this activity; 0 for the first activity. */
		public final double travelTimeBefore;
		/** Preferred (typical) duration t*, seconds. For a wrap-around first activity this is the combined wrapped duration. */
		public final double typicalDuration;
		/** Penalty slopes (utils/s), unperturbed; see class javadoc. */
		public final double durShortSlope;
		/** Slope of the shortfall beyond half the typical duration; should exceed both durShortSlope and endLateSlope, see class javadoc. */
		public final double durVeryShortSlope;
		public final double durLongSlope;
		/** Desired end time e* including its random perturbation, or undefined if this activity has no scheduling anchor. */
		public final OptionalTime targetEndTime;
		public final double endEarlySlope;
		public final double endLateSlope;

		public Act( String type, boolean endTimeBased, boolean lastActivity, double travelTimeBefore, double typicalDuration,
					double durShortSlope, double durVeryShortSlope, double durLongSlope, OptionalTime targetEndTime,
					double endEarlySlope, double endLateSlope ) {
			this.type = type;
			this.endTimeBased = endTimeBased;
			this.lastActivity = lastActivity;
			this.travelTimeBefore = travelTimeBefore;
			this.typicalDuration = typicalDuration;
			this.durShortSlope = durShortSlope;
			this.durVeryShortSlope = durVeryShortSlope;
			this.durLongSlope = durLongSlope;
			this.targetEndTime = targetEndTime;
			this.endEarlySlope = endEarlySlope;
			this.endLateSlope = endLateSlope;
		}
	}

	/** Activities in plan order. */
	public final List<Act> activities;
	/** Start of the day (s); the first activity starts here. Usually 0. */
	public final double dayStart;
	/** End of the day (s), i.e. simulationPeriodInDays * 24h; reference point for the last activity's wrap-around share. */
	public final double dayEnd;
	/** true => first and last activity are one activity wrapping around midnight and share one joint duration term. */
	public final boolean wrapAround;

	public ScheduleProblem( List<Act> activities, double dayStart, double dayEnd, boolean wrapAround ) {
		this.activities = activities;
		this.dayStart = dayStart;
		this.dayEnd = dayEnd;
		this.wrapAround = wrapAround;
	}
}
