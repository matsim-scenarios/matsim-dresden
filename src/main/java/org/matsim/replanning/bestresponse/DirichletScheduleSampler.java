package org.matsim.replanning.bestresponse;

import java.util.Random;

/**
 * Samples a schedule instead of optimizing one: the durations of all n activities (including the last one's implied
 * share of the day) are drawn from a Dirichlet distribution over the feasible domain. For a non-wrap-around plan
 * without end-time anchors that domain is exactly the scaled standard simplex {@code {d >= 0, sum d_i = T}} with
 * {@code T = dayEnd - dayStart - totalTravel}, so the draw is always a feasible whole-day schedule -- the sampling
 * counterpart of the coordinated multi-activity move the {@link LpScheduleSolver} makes deterministically.
 *
 * <h3>Distribution: Boltzmann sampling of the Charypar-Nagel performing utility</h3>
 * {@code d = T * Dirichlet(alpha)} with {@code alpha_i = 1 + inverseTemperature * durShortSlope_i * t*_i}. The
 * Dirichlet density on the simplex is {@code exp(sum (alpha_i - 1) * ln d_i)}, and the Charypar-Nagel performing
 * utility has exactly that log shape, {@code sum beta_perf * t*_i * ln(d_i / ..)}, so this samples the exact
 * Boltzmann distribution {@code exp(U_perf / mu)} of the true (concave, unlinearized) performing term at temperature
 * {@code mu = 1 / inverseTemperature} [utils]. The coefficient {@code beta_perf * t*_i} is taken from the problem
 * itself: {@link ScheduleProblem.Act#durShortSlope} is the performing marginal at the typical duration (utils/s), as
 * derived from the scoring config by the extraction -- the same source the LP slopes come from.
 * <p>
 * The inverse temperature [1/utils] is a slider between exploration and preference:
 * <ul>
 *   <li>{@code 0}: {@code Dirichlet(1,..,1)}, the uniform distribution on the feasible simplex -- every feasible
 *   schedule is equally likely, typical durations are ignored;</li>
 *   <li>{@code 1}: the temperature at which {@code ChangeExpBeta} plan selection (default beta = 1/util) operates,
 *   i.e. proposals matched to the choice dynamics;</li>
 *   <li>{@code -> infinity}: degenerates to the utility-maximizing proportional fit {@code d_i = t*_i / sum t* * T}
 *   (the distribution's mode for any positive inverse temperature).</li>
 * </ul>
 * Because each {@code alpha_i} depends only on the activity's own typical duration -- not on how crowded the day is
 * -- an activity's <em>relative</em> spread ({@code ~ 1/sqrt(alpha_i)}) and its risk of being drawn far below its
 * proportional share are independent of the number of activities in the plan; crowding is absorbed by the common
 * scale factor, i.e. proportionally by everyone. (A globally normalized concentration would instead dilute toward
 * uniform as the day fills.) For any positive inverse temperature the density vanishes at zero duration.
 * <p>
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

	private final double inverseTemperature;
	private final Random random;

	public DirichletScheduleSampler( double inverseTemperature, Random random ) {
		if ( inverseTemperature < 0. ) {
			throw new IllegalArgumentException( "Dirichlet inverse temperature must be >= 0, got " + inverseTemperature );
		}
		this.inverseTemperature = inverseTemperature;
		this.random = random;
	}

	@Override
	public double[] solve( ScheduleProblem problem ) {
		if ( problem.wrapAround ) {
			throw new UnsupportedOperationException( "DirichletScheduleSampler supports only non-wrap-around plans; "
				+ "run SplitWrapAroundActivities in preprocessing." );
		}
		int n = problem.activities.size();
		double totalTravel = 0.;
		for ( ScheduleProblem.Act act : problem.activities ) {
			if ( act.targetEndTime.isDefined() ) {
				throw new UnsupportedOperationException( "DirichletScheduleSampler supports only plans without "
					+ "end-time anchors; activity of type '" + act.type + "' carries one (the initialEndTime "
					+ "attribute, stamped for the schedule-delay corridor / anchored replanning modes)." );
			}
			totalTravel += act.travelTimeBefore;
		}
		double budget = problem.dayEnd - problem.dayStart - totalTravel;
		if ( budget <= 0. ) {
			throw new IllegalStateException( "Travel time (" + totalTravel + "s) fills or exceeds the day ("
				+ ( problem.dayEnd - problem.dayStart ) + "s); no time budget left to sample a schedule from." );
		}

		double[] durations = new double[n];
		double sum = 0.;
		for ( int i = 0; i < n; i++ ) {
			ScheduleProblem.Act act = problem.activities.get( i );
			double alpha = 1. + inverseTemperature * act.durShortSlope * act.typicalDuration;
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
