package org.matsim.replanning.bestresponse;

import org.junit.jupiter.api.Test;
import org.matsim.core.utils.misc.OptionalTime;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hand-computed LP cases for {@link LpScheduleSolver}. Slopes follow the Dresden setup: performing 6/h (duration
 * slopes and the early anchor side; 24/h for shortfall beyond half typical), |lateArrival| 18/h (late anchor side).
 */
class LpScheduleSolverTest {

	private static final double BETA = 6. / 3600.;      // performing, utils/s
	private static final double STEEP = 24. / 3600.;    // shortfall beyond t*/2, utils/s
	private static final double LATE = 18. / 3600.;     // |lateArrival|, utils/s
	private static final double DAY = 86400.;
	private static final double EPS = 1e-6;

	private final LpScheduleSolver solver = new LpScheduleSolver();

	private static ScheduleProblem.Act act( String type, boolean last, double travelBefore, double typical, OptionalTime target ) {
		return new ScheduleProblem.Act( type, true, last, travelBefore, typical, BETA, STEEP, BETA, target, BETA, LATE );
	}

	@Test
	void consistentScheduleIsReproduced() {
		// home(t*=8h, anchor 8:00) -- 30min -- work(t*=8h, anchor 16:30) -- 30min -- other(last, t*=7h). The last
		// duration is not a separate variable; it follows from the others as 86400 - (28800+1800+28800+1800) = 25200,
		// which here equals its typical duration, so all penalty terms (including the last activity's duration term)
		// sit exactly on their kinks and the consistent schedule is the unique zero-penalty optimum.
		ScheduleProblem problem = new ScheduleProblem( List.of(
			act( "home", false, 0., 28800., OptionalTime.defined( 28800. ) ),
			act( "work", false, 1800., 28800., OptionalTime.defined( 59400. ) ),
			act( "other", true, 1800., 25200., OptionalTime.undefined() )
		), 0., DAY, false );

		double[] durations = solver.solve( problem );

		assertEquals( 28800., durations[0], EPS );
		assertEquals( 28800., durations[1], EPS );
		assertEquals( 25200., durations[2], EPS );
	}

	@Test
	void lateAnchorOutweighsDurationShortfall() {
		// Work would naturally run until 17:30 (t*=9h from a 8:30 arrival), but its anchor says 16:00. Being late
		// costs 18/h against 6/h for cutting the duration, so the LP cuts work to end exactly on the anchor. The
		// last activity ends up 1.5h over its typical duration; that overrun (6/h) is tolerated because relieving
		// it would push work past its anchor (18/h).
		ScheduleProblem problem = new ScheduleProblem( List.of(
			act( "home", false, 0., 28800., OptionalTime.defined( 28800. ) ),
			act( "work", false, 1800., 32400., OptionalTime.defined( 57600. ) ),
			act( "other", true, 1800., 21600., OptionalTime.undefined() )
		), 0., DAY, false );

		double[] durations = solver.solve( problem );

		assertEquals( 28800., durations[0], EPS );
		assertEquals( 27000., durations[1], EPS ); // ends at 28800 + 1800 + 27000 = 57600, exactly on the anchor
	}

	@Test
	void wrapAroundJointTermSchedulesTheHomeShare() {
		// home -- work -- home (wrap-around). The joint home term wants 13h of total home time; with 2x30min travel
		// that leaves exactly 10h for work, which is also work's typical duration, so the optimum is unique.
		ScheduleProblem problem = new ScheduleProblem( List.of(
			act( "home", false, 0., 46800., OptionalTime.defined( 28800. ) ),
			act( "work", false, 1800., 36000., OptionalTime.undefined() ),
			act( "home", true, 1800., 46800., OptionalTime.undefined() )
		), 0., DAY, true );

		double[] durations = solver.solve( problem );

		assertEquals( 28800., durations[0], EPS ); // held by its anchor (d_0 cancels out of the joint term)
		assertEquals( 36000., durations[1], EPS );
		assertEquals( 18000., durations[2], EPS ); // evening share; morning 28800 + evening 18000 = wrapped typical 46800
	}

	@Test
	void infeasibleAnchorSqueezesOnlyToHalfTypical() {
		// The work anchor (6:00) lies hours before work could plausibly happen (a garbled schedule). Shortening trades
		// against lateness: above half typical the shortfall costs 6/h against 18/h lateness, so both home and work
		// shorten -- but only until they hit the steep second under-run segment (24/h > 18/h) at half their typical
		// duration. Neither is squeezed to zero (the vertex a single-slope under-run penalty would pick). The last
		// activity's typical duration (15h) equals its resulting share of the day, keeping its duration term neutral
		// so the test isolates the half-typical bound.
		ScheduleProblem problem = new ScheduleProblem( List.of(
			act( "home", false, 0., 28800., OptionalTime.defined( 28800. ) ),
			act( "work", false, 1800., 28800., OptionalTime.defined( 21600. ) ),
			act( "other", true, 1800., 54000., OptionalTime.undefined() )
		), 0., DAY, false );

		double[] durations = solver.solve( problem );

		assertEquals( 14400., durations[0], EPS );
		assertEquals( 14400., durations[1], EPS );
	}

	@Test
	void unanchoredConsistentPlanIsReproduced() {
		// No target end times anywhere -- for a non-wrap-around plan that is just a regular case, not a special one.
		// The typical durations plus travel exactly fill the day, so reproducing them (including the last activity's
		// implied share of the day) is the unique zero-penalty optimum: any shift creates a matching shortfall/overrun
		// pair, and both sides cost.
		ScheduleProblem problem = new ScheduleProblem( List.of(
			act( "home", false, 0., 28800., OptionalTime.undefined() ),
			act( "work", false, 1800., 28800., OptionalTime.undefined() ),
			act( "other", true, 1800., 25200., OptionalTime.undefined() )
		), 0., DAY, false );

		double[] durations = solver.solve( problem );

		assertEquals( 28800., durations[0], EPS );
		assertEquals( 28800., durations[1], EPS );
		assertEquals( 25200., durations[2], EPS );
	}

	@Test
	void unanchoredOverfullDaySharesTheShortage() {
		// No target end times, and the typical durations (10h + 10h + 6h) plus 1h travel exceed the day by 3h. With
		// identical slopes the split of the 3h shortage is arbitrary among optima (a plateau), but at every optimum
		// no activity is extended beyond its typical duration and none -- including the last one's implied share --
		// is squeezed below half its typical duration (the first under-run segments have plenty of room for 3h).
		ScheduleProblem problem = new ScheduleProblem( List.of(
			act( "home", false, 0., 36000., OptionalTime.undefined() ),
			act( "work", false, 1800., 36000., OptionalTime.undefined() ),
			act( "other", true, 1800., 21600., OptionalTime.undefined() )
		), 0., DAY, false );

		double[] durations = solver.solve( problem );

		assertEquals( DAY - 3600., durations[0] + durations[1] + durations[2], EPS );
		assertTrue( durations[0] >= 18000. - EPS && durations[0] <= 36000. + EPS, "home: " + durations[0] );
		assertTrue( durations[1] >= 18000. - EPS && durations[1] <= 36000. + EPS, "work: " + durations[1] );
		assertTrue( durations[2] >= 10800. - EPS && durations[2] <= 21600. + EPS, "other: " + durations[2] );
	}

	@Test
	void squeezedLastActivityShortensTheDayBeforeIt() {
		// At its anchors and typicals the day would leave the last activity only 2h against a 6h typical -- squeezed
		// below half typical, where the shortfall costs 24/h. Ending work early costs only 6/h (anchor early side)
		// plus 6/h (its own shortfall), so the LP cuts work -- but exactly until the last activity reaches half its
		// typical duration (3h), where the saving drops to 6/h and no longer pays for the cut. Without a duration
		// term for the last activity, work would simply sit on its anchor and the last activity would keep its 2h.
		ScheduleProblem problem = new ScheduleProblem( List.of(
			act( "home", false, 0., 28800., OptionalTime.defined( 28800. ) ),
			act( "work", false, 1800., 46800., OptionalTime.defined( 77400. ) ),
			act( "other", true, 1800., 21600., OptionalTime.undefined() )
		), 0., DAY, false );

		double[] durations = solver.solve( problem );

		assertEquals( 28800., durations[0], EPS );
		assertEquals( 43200., durations[1], EPS );
		assertEquals( 10800., durations[2], EPS );
	}

	@Test
	void tinyStopIsNeverSqueezedBelowHalfTypical() {
		// A tiny (5min typical) duration-based stop sits before an anchored activity that is 300s short of its typical
		// -- the constellation the min-typical-duration preprocessing bakes into the Dresden plans. With a single
		// under-run slope the LP is indifferent between zeroing the stop and shortening the long activity, and the
		// simplex vertex zeroes the stop. The steep second segment bounds that: within the first segment the slopes
		// still tie (the split of the 300s shortage between the two activities is arbitrary among optima), but no
		// activity can be pushed below half its typical duration by anchor pressure (18/h) alone.
		ScheduleProblem problem = new ScheduleProblem( List.of(
			act( "home", false, 0., 28800., OptionalTime.defined( 28800. ) ),
			act( "errand", false, 600., 300., OptionalTime.undefined() ),
			act( "business", false, 600., 3600., OptionalTime.defined( 33600. ) ),
			act( "other", true, 600., 21600., OptionalTime.undefined() )
		), 0., DAY, false );

		double[] durations = solver.solve( problem );

		assertEquals( 28800., durations[0], EPS );
		assertTrue( durations[1] >= 150. - EPS, "stop must keep at least half its typical duration, got " + durations[1] );
		// the shortage stays exactly 300s in total: the business activity still ends on its anchor.
		assertEquals( 33600., 28800. + 600. + durations[1] + 600. + durations[2], EPS );
	}
}
