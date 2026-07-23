package org.matsim.replanning.bestresponse;

import java.util.Random;

/**
 * Samples a schedule instead of optimizing one: the durations of all n activities (including the last one's implied
 * share of the day) are drawn from a Dirichlet distribution over the feasible domain. For a duration-based,
 * non-wrap-around plan that domain is exactly the scaled standard simplex {@code {d >= 0, sum d_i = T}} with
 * {@code T = dayEnd - dayStart - totalTravel}, so the draw is always a feasible whole-day schedule -- the sampling
 * counterpart of the coordinated multi-activity move the {@link LpScheduleSolver} makes deterministically.
 *
 * <h3>Distribution</h3>
 * {@code d = T * Dirichlet(alpha)} with {@code alpha_i = 1 + c * t*_i / sum_j t*_j}. The concentration {@code c >= 0}
 * is a slider between exploration and preference:
 * <ul>
 *   <li>{@code c = 0}: {@code Dirichlet(1,..,1)}, i.e. the uniform distribution on the feasible simplex -- every
 *   feasible schedule is equally likely, typical durations are ignored;</li>
 *   <li>{@code c > 0}: mean share {@code (1 + c*p_i) / (n + c)} with {@code p_i = t*_i / sum t*}, i.e. {@code c}
 *   acts as a prior sample size pulling the shares toward the typical-duration proportions;</li>
 *   <li>{@code c -> infinity}: degenerates to the deterministic proportional fit of the typical durations into the
 *   available budget (which is the no-anchor best response whenever the typicals exactly fill the day).</li>
 * </ul>
 * Sampling is via normalized Gamma draws (Marsaglia-Tsang); {@code alpha_i >= 1} always, so no boosting is needed.
 *
 * <h3>Scope</h3>
 * Only non-wrap-around plans without end-time anchors. An anchor (the initialEndTime attribute -- a scheduling
 * <em>preference</em>, as opposed to the plan's own end times, which merely encode the current candidate plan and are
 * rewritten anyway) should shape the distribution, which this sampler cannot do; a wrap-around pair shares one joint
 * duration, which is not the plain simplex. {@link #solve} throws in both cases rather than silently ignoring the
 * preference; wrap-around plans are avoided by preprocessing with {@code SplitWrapAroundActivities}.
 * <p>
 * NOT stateless: carries its own {@link Random}, so use one instance per worker thread (see
 * {@link DirichletSamplingModule}).
 */
public final class DirichletScheduleSampler implements ScheduleSolver {

	private final double concentration;
	private final Random random;

	public DirichletScheduleSampler( double concentration, Random random ) {
		if ( concentration < 0. ) {
			throw new IllegalArgumentException( "Dirichlet concentration must be >= 0, got " + concentration );
		}
		this.concentration = concentration;
		this.random = random;
	}

	@Override
	public double[] solve( ScheduleProblem problem ) {
		if ( problem.wrapAround ) {
			throw new UnsupportedOperationException( "DirichletScheduleSampler supports only non-wrap-around plans; "
				+ "run SplitWrapAroundActivities in preprocessing." );
		}
		int n = problem.activities.size();
		double totalTravel = 0., totalTypical = 0.;
		for ( ScheduleProblem.Act act : problem.activities ) {
			if ( act.targetEndTime.isDefined() ) {
				throw new UnsupportedOperationException( "DirichletScheduleSampler supports only plans without "
					+ "end-time anchors; activity of type '" + act.type + "' carries one (the initialEndTime "
					+ "attribute, stamped for the schedule-delay corridor / anchored replanning modes)." );
			}
			totalTravel += act.travelTimeBefore;
			totalTypical += act.typicalDuration;
		}
		double budget = problem.dayEnd - problem.dayStart - totalTravel;
		if ( budget <= 0. ) {
			throw new IllegalStateException( "Travel time (" + totalTravel + "s) fills or exceeds the day ("
				+ ( problem.dayEnd - problem.dayStart ) + "s); no time budget left to sample a schedule from." );
		}

		double[] durations = new double[n];
		double sum = 0.;
		for ( int i = 0; i < n; i++ ) {
			double alpha = 1. + ( totalTypical > 0. ? concentration * problem.activities.get( i ).typicalDuration / totalTypical : 0. );
			durations[i] = gamma( alpha );
			sum += durations[i];
		}
		for ( int i = 0; i < n; i++ ) {
			durations[i] = sum > 0. ? budget * durations[i] / sum : budget / n;
		}
		return durations;
	}

	/** A Gamma(alpha, 1) draw for alpha >= 1 (Marsaglia &amp; Tsang 2000). */
	private double gamma( double alpha ) {
		double d = alpha - 1. / 3.;
		double c = 1. / Math.sqrt( 9. * d );
		while ( true ) {
			double x, v;
			do {
				x = random.nextGaussian();
				v = 1. + c * x;
			} while ( v <= 0. );
			v = v * v * v;
			double u = random.nextDouble();
			if ( u < 1. - 0.0331 * x * x * x * x ) {
				return d * v;
			}
			if ( Math.log( u ) < 0.5 * x * x + d * ( 1. - v + Math.log( v ) ) ) {
				return d * v;
			}
		}
	}
}
