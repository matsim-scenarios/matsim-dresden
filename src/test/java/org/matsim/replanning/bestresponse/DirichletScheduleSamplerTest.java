package org.matsim.replanning.bestresponse;

import org.junit.jupiter.api.Test;
import org.matsim.core.utils.misc.OptionalTime;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Distributional checks for {@link DirichletScheduleSampler}: every draw is a feasible schedule (non-negative, fills
 * the day exactly), c=0 is uniform on the simplex (equal mean shares regardless of typicals), large c concentrates on
 * the typical-duration proportions. Fixed seed; the tolerances are several standard errors wide.
 */
class DirichletScheduleSamplerTest {

	private static final double DAY = 86400.;

	private static ScheduleProblem.Act act( String type, boolean last, double travelBefore, double typical, OptionalTime target ) {
		double beta = 6. / 3600.;
		return new ScheduleProblem.Act( type, false, last, travelBefore, typical, beta, 4 * beta, beta, target, beta, 3 * beta );
	}

	private static ScheduleProblem threeActivities( OptionalTime firstAnchor ) {
		// typicals 4h / 2h / 2h (shares 0.5 / 0.25 / 0.25), 2x30min travel => budget 82800s
		return new ScheduleProblem( List.of(
			act( "home", false, 0., 14400., firstAnchor ),
			act( "work", false, 1800., 7200., OptionalTime.undefined() ),
			act( "other", true, 1800., 7200., OptionalTime.undefined() )
		), 0., DAY, false );
	}

	@Test
	void zeroConcentrationSamplesUniformly() {
		DirichletScheduleSampler sampler = new DirichletScheduleSampler( 0., new Random( 42 ) );
		ScheduleProblem problem = threeActivities( OptionalTime.undefined() );
		double budget = DAY - 3600.;

		int draws = 20000;
		double[] mean = new double[3];
		for ( int k = 0; k < draws; k++ ) {
			double[] d = sampler.solve( problem );
			double sum = 0.;
			for ( int i = 0; i < 3; i++ ) {
				assertTrue( d[i] >= 0., "negative duration " + d[i] );
				sum += d[i];
				mean[i] += d[i] / draws;
			}
			assertEquals( budget, sum, 1e-6, "draw must fill the day exactly" );
		}
		// uniform on the simplex => mean share 1/3 each, despite the unequal typicals; se ~ 140s, tolerance 500s
		for ( int i = 0; i < 3; i++ ) {
			assertEquals( budget / 3., mean[i], 500., "activity " + i );
		}
	}

	@Test
	void largeConcentrationApproachesTypicalShares() {
		DirichletScheduleSampler sampler = new DirichletScheduleSampler( 1e6, new Random( 42 ) );
		ScheduleProblem problem = threeActivities( OptionalTime.undefined() );
		double budget = DAY - 3600.;

		int draws = 100;
		double[] mean = new double[3];
		for ( int k = 0; k < draws; k++ ) {
			double[] d = sampler.solve( problem );
			for ( int i = 0; i < 3; i++ ) {
				mean[i] += d[i] / draws;
			}
		}
		// c -> infinity degenerates to the proportional fit of the typicals; per-draw sd ~ 40s here
		assertEquals( 0.50 * budget, mean[0], 200. );
		assertEquals( 0.25 * budget, mean[1], 200. );
		assertEquals( 0.25 * budget, mean[2], 200. );
	}

	@Test
	void throwsOnEndTimeAnchor() {
		DirichletScheduleSampler sampler = new DirichletScheduleSampler( 0., new Random( 42 ) );
		ScheduleProblem problem = threeActivities( OptionalTime.defined( 28800. ) );
		assertThrows( UnsupportedOperationException.class, () -> sampler.solve( problem ) );
	}

	@Test
	void throwsOnWrapAroundPlan() {
		DirichletScheduleSampler sampler = new DirichletScheduleSampler( 0., new Random( 42 ) );
		ScheduleProblem problem = new ScheduleProblem( List.of(
			act( "home", false, 0., 46800., OptionalTime.undefined() ),
			act( "work", false, 1800., 36000., OptionalTime.undefined() ),
			act( "home", true, 1800., 46800., OptionalTime.undefined() )
		), 0., DAY, true );
		assertThrows( UnsupportedOperationException.class, () -> sampler.solve( problem ) );
	}

	@Test
	void rejectsNegativeConcentration() {
		assertThrows( IllegalArgumentException.class, () -> new DirichletScheduleSampler( -1., new Random( 42 ) ) );
	}
}
