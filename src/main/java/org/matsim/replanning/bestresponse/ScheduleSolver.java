package org.matsim.replanning.bestresponse;

/**
 * Solves a {@link ScheduleProblem}: chooses a duration (seconds) for every activity so as to minimize the total
 * linearized penalty (see {@link ScheduleProblem}).
 * <p>
 * The intended production implementation is a linear program over the durations with the chain constraints and the
 * piecewise-linear penalties (Apache Commons Math {@code SimplexSolver} is on the classpath, or a bespoke chain solver
 * exploiting the special structure). The mockup {@link SeparableDurationScheduleSolver} solves only the separable
 * duration term.
 */
public interface ScheduleSolver {

	/**
	 * @param problem the extracted scheduling problem
	 * @return the chosen duration (seconds), one per activity, in the same order as {@link ScheduleProblem#activities};
	 *         every value is guaranteed {@code >= 0}.
	 */
	double[] solve( ScheduleProblem problem );
}
