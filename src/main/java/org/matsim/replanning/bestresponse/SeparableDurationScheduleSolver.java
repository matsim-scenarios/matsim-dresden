package org.matsim.replanning.bestresponse;

/**
 * MOCKUP solver. It optimizes only the separable <em>duration</em> term of the {@link ScheduleProblem} and ignores the
 * end-time scheduling penalties (which couple the activities through the chain and are what actually require a linear
 * program). With a symmetric duration penalty the separable optimum of each activity is simply its preferred duration,
 * so the best response reduces to the (randomly perturbed) typical duration clamped to be non-negative:
 * <pre>
 *   d_i = max(0, typicalDuration_i + randomError_i)
 * </pre>
 * That already delivers the headline property we are after -- durations are non-negative and near typical, so no
 * activity is squeezed to zero -- while leaving the genuine optimization (the coupled end-time term) as the project
 * work.
 * <p>
 * TODO(project): replace with a real {@link ScheduleSolver} that minimizes the full objective, e.g. an LP over the
 * durations with the chain constraints {@code start_i = end_{i-1} + travelTimeBefore_i}, {@code end_i = start_i + d_i},
 * {@code d_i >= 0}, and the piecewise-linear duration + end-time penalties (split each deviation into non-negative
 * under/over parts). The end-time penalties reference {@code end_i}, which is affine in the upstream durations, so the
 * problem stays linear.
 */
public final class SeparableDurationScheduleSolver implements ScheduleSolver {

	@Override
	public double[] solve( ScheduleProblem problem ) {
		double[] durations = new double[problem.activities.size()];
		for ( int i = 0; i < durations.length; i++ ) {
			ScheduleProblem.Act act = problem.activities.get( i );
			durations[i] = Math.max( 0.0, act.typicalDuration + act.randomError );
		}
		return durations;
	}
}
