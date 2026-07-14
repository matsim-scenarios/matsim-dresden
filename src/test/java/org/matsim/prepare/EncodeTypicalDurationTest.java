package org.matsim.prepare;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.population.PopulationUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EncodeTypicalDurationTest {

	private static double typical( Activity act ) {
		return ( (Number) act.getAttributes().getAttribute( EncodeTypicalDuration.TYPICAL_DURATION ) ).doubleValue();
	}

	/**
	 * Plans that never went through a simulation carry no start times; the encoder must reconstruct them by walking
	 * the chain instead of silently assuming 0 (which used to turn every typical duration into the activity's end
	 * time -- 10h of "shopping" below).
	 */
	@Test
	void reconstructsMissingStartTimesFromTheChain() {
		Plan plan = PopulationUtils.createPlan();
		Activity home = PopulationUtils.createAndAddActivityFromCoord( plan, "home", new Coord( 0., 0. ) );
		home.setEndTime( 28800. );
		Leg leg1 = PopulationUtils.createAndAddLeg( plan, TransportMode.car );
		leg1.setTravelTime( 900. );
		Activity shop = PopulationUtils.createAndAddActivityFromCoord( plan, "shop", new Coord( 1000., 0. ) );
		shop.setEndTime( 36000. );
		Leg leg2 = PopulationUtils.createAndAddLeg( plan, TransportMode.car );
		leg2.setTravelTime( 900. );
		PopulationUtils.createAndAddActivityFromCoord( plan, "home_evening", new Coord( 0., 0. ) );

		Person person = PopulationUtils.getFactory().createPerson( Id.createPersonId( "p" ) );
		person.addPlan( plan );
		new EncodeTypicalDuration().encodeTypicalDuration( person );

		assertEquals( 28800., typical( home ), 1e-9 );
		assertEquals( 6300., typical( shop ), 1e-9 );  // 36000 - (28800 + 900), not 36000
		// non-wrap-around last activity: from its reconstructed start (36900) to the end of the (1-day) period.
		assertEquals( 86400. - 36900., typical( (Activity) plan.getPlanElements().get( 4 ) ), 1e-9 );
	}

	/** Recorded start times must keep winning over the chain reconstruction. */
	@Test
	void recordedStartTimesTakePrecedence() {
		Plan plan = PopulationUtils.createPlan();
		Activity home = PopulationUtils.createAndAddActivityFromCoord( plan, "home", new Coord( 0., 0. ) );
		home.setEndTime( 28800. );
		Leg leg1 = PopulationUtils.createAndAddLeg( plan, TransportMode.car );
		leg1.setTravelTime( 900. );
		Activity shop = PopulationUtils.createAndAddActivityFromCoord( plan, "shop", new Coord( 1000., 0. ) );
		shop.setStartTime( 30000. ); // recorded, differs from chain-derived 29700
		shop.setEndTime( 36000. );
		Leg leg2 = PopulationUtils.createAndAddLeg( plan, TransportMode.car );
		leg2.setTravelTime( 900. );
		Activity home2 = PopulationUtils.createAndAddActivityFromCoord( plan, "home", new Coord( 0., 0. ) );
		home2.setStartTime( 36900. );

		Person person = PopulationUtils.getFactory().createPerson( Id.createPersonId( "p" ) );
		person.addPlan( plan );
		new EncodeTypicalDuration().encodeTypicalDuration( person );

		assertEquals( 6000., typical( shop ), 1e-9 ); // 36000 - 30000
		// wrap-around (same type): morning part 28800 plus evening part 86400 - 36900.
		assertEquals( 28800. + 86400. - 36900., typical( home ), 1e-9 );
		assertEquals( typical( home ), typical( home2 ), 1e-9 );
	}
}
