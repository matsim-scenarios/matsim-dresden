package org.matsim.replanning.bestresponse;

/**
 * Solves a {@link ScheduleProblem}: chooses a duration (seconds) for every activity so as to minimize the total
 * linearized penalty (see {@link ScheduleProblem}). The production implementation is {@link LpScheduleSolver}.
 */
public interface ScheduleSolver {

	/**
	 * @param problem the extracted scheduling problem
	 * @return the chosen duration (seconds), one per activity, in the same order as {@link ScheduleProblem#activities};
	 *         every value is guaranteed {@code >= 0}. The last activity's entry is informational only (its wrap-around
	 *         share of the day); write-back leaves the last activity unscheduled.
	 */
	double[] solve( ScheduleProblem problem );
}
