package org.matsim.replanning.bestresponse;

/**
 * Chooses a duration (seconds) for every activity of a {@link ScheduleProblem} -- by optimizing the linearized
 * penalty ({@link LpScheduleSolver}) or by sampling the feasible schedules ({@link DirichletScheduleSampler}).
 */
public interface ScheduleSolver {

	/**
	 * @param problem the extracted scheduling problem
	 * @return the chosen duration (seconds), one per activity, in the same order as {@link ScheduleProblem#activities};
	 *         every value is guaranteed {@code >= 0}. The last activity's entry is its implied duration up to the end
	 *         of the day; write-back leaves the last activity unscheduled.
	 */
	double[] solve( ScheduleProblem problem );
}
