package org.matsim.replanning.bestresponse;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.algorithms.MutateActivityTimeAllocation;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.prepare.EncodeTypicalDuration;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BestResponseSchedulePlanAlgorithmTest {

	private static final double DAY = 86400.;

	private static ScoringConfigGroup dresdenLikeScoring() {
		ScoringConfigGroup scoring = new ScoringConfigGroup();
		scoring.setPerforming_utils_hr( 6. );
		scoring.setLateArrival_utils_hr( -18. );
		scoring.setEarlyDeparture_utils_hr( 0. );
		return scoring;
	}

	private static BestResponseSchedulePlanAlgorithm algorithm( double sigma, long seed ) {
		return new BestResponseSchedulePlanAlgorithm( dresdenLikeScoring(), new LpScheduleSolver(), sigma, DAY, new Random( seed ) );
	}

	/**
	 * home(end 8:00) -- car 30min -- work -- car 30min -- home. The work end time is garbled to 7:00 (before its own
	 * arrival, the classic zero-duration pathology), but the stable initial-end-time anchor says 16:30.
	 */
	private static Plan garbledWrapAroundPlan() {
		Plan plan = PopulationUtils.createPlan();
		Activity home1 = PopulationUtils.createAndAddActivityFromCoord( plan, "home", new Coord( 0., 0. ) );
		home1.setEndTime( 28800. );
		home1.getAttributes().putAttribute( EncodeTypicalDuration.TYPICAL_DURATION, 46800. );
		Leg leg1 = PopulationUtils.createAndAddLeg( plan, TransportMode.car );
		leg1.setTravelTime( 1800. );
		Activity work = PopulationUtils.createAndAddActivityFromCoord( plan, "work", new Coord( 10000., 0. ) );
		work.setEndTime( 25200. );
		work.getAttributes().putAttribute( EncodeTypicalDuration.TYPICAL_DURATION, 28800. );
		work.getAttributes().putAttribute( MutateActivityTimeAllocation.INITIAL_END_TIME_ATTRIBUTE, 59400. );
		Leg leg2 = PopulationUtils.createAndAddLeg( plan, TransportMode.car );
		leg2.setTravelTime( 1800. );
		Activity home2 = PopulationUtils.createAndAddActivityFromCoord( plan, "home", new Coord( 0., 0. ) );
		home2.getAttributes().putAttribute( EncodeTypicalDuration.TYPICAL_DURATION, 46800. );
		return plan;
	}

	@Test
	void repairsGarbledEndTimeUsingTheInitialAnchor() {
		Plan plan = garbledWrapAroundPlan();

		algorithm( 0., 1 ).run( plan );

		Activity home1 = (Activity) plan.getPlanElements().get( 0 );
		Activity work = (Activity) plan.getPlanElements().get( 2 );
		Activity home2 = (Activity) plan.getPlanElements().get( 4 );

		// home is held by its anchor; work regains its typical 8h and ends on its initial anchor 16:30.
		assertEquals( 28800., home1.getEndTime().seconds(), 1e-6 );
		assertEquals( 59400., work.getEndTime().seconds(), 1e-6 );
		// the open last activity stays unscheduled.
		assertFalse( home2.getEndTime().isDefined() );
		assertFalse( home2.getMaximumDuration().isDefined() );
	}

	/**
	 * Travel time lives on the route for freshly routed legs (leg-level travel time undefined); the extractor must
	 * fall back to it instead of treating the leg as instantaneous. This bug made the optimizer schedule whole days
	 * with zero travel during a run: every replanned leg lost its leg-level travel time, and each activity whose
	 * anchor gap was smaller than the real cumulated travel realized as zero-duration.
	 */
	@Test
	void travelTimesFallBackToRouteTravelTime() {
		Plan plan = garbledWrapAroundPlan();
		for ( var element : plan.getPlanElements() ) {
			if ( element instanceof Leg leg ) {
				double t = leg.getTravelTime().seconds();
				leg.setTravelTimeUndefined();
				leg.setRoute( RouteUtils.createGenericRouteImpl( Id.createLinkId( "a" ), Id.createLinkId( "b" ) ) );
				leg.getRoute().setTravelTime( t );
			}
		}

		double[] travel = BestResponseSchedulePlanAlgorithm.travelTimesBeforeEachActivity( plan, 3 );

		assertEquals( 0., travel[0], 1e-9 );
		assertEquals( 1800., travel[1], 1e-9 );
		assertEquals( 1800., travel[2], 1e-9 );
	}

	/**
	 * Travel times are simulated state and start in disequilibrium (a missed pt connection can turn a trip into many
	 * hours). A day whose travel alone fills the simulation period has no feasible schedule; re-timing must leave the
	 * plan unchanged rather than throw (only configuration errors throw) or write a garbage schedule.
	 */
	@Test
	void travelFillingTheDayLeavesThePlanUnchanged() {
		Plan plan = garbledWrapAroundPlan();
		for ( var element : plan.getPlanElements() ) {
			if ( element instanceof Leg leg ) {
				leg.setTravelTime( 50000. ); // 2 x 50000s > 86400s
			}
		}

		algorithm( 0., 1 ).run( plan );

		Activity home1 = (Activity) plan.getPlanElements().get( 0 );
		Activity work = (Activity) plan.getPlanElements().get( 2 );
		assertEquals( 28800., home1.getEndTime().seconds(), 1e-9 );
		assertEquals( 25200., work.getEndTime().seconds(), 1e-9, "the garbled end time must survive untouched" );
	}

	@Test
	void sigmaZeroIsDeterministicAndSigmaPositiveExplores() {
		Plan a = garbledWrapAroundPlan(), b = garbledWrapAroundPlan(), c = garbledWrapAroundPlan();

		algorithm( 0., 1 ).run( a );
		algorithm( 0., 2 ).run( b );
		algorithm( 900., 3 ).run( c );

		Activity workA = (Activity) a.getPlanElements().get( 2 );
		Activity workB = (Activity) b.getPlanElements().get( 2 );
		Activity workC = (Activity) c.getPlanElements().get( 2 );

		// sigma 0: the seed must not matter.
		assertEquals( workA.getEndTime().seconds(), workB.getEndTime().seconds(), 1e-6 );
		// sigma > 0: the perturbed anchors move the optimum away from the deterministic solution...
		assertNotEquals( workA.getEndTime().seconds(), workC.getEndTime().seconds(), 1. );
		// ...but only by the order of sigma, and never below zero duration.
		assertTrue( Math.abs( workC.getEndTime().seconds() - 59400. ) < 6. * 900. );
	}
}
