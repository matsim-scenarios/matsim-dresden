package org.matsim.run.scenarios;

import com.ibm.icu.impl.number.MacroProps;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.pt.transitSchedule.api.*;

import java.util.*;

public class ReroutePtLinesCarolaBridge {
	private static final String stopNameWalpurgisstrasse = "Dresden Walpurgisstraße";
	private static final String stopNamePirnaischerPlatz = "Dresden Pirnaischer Platz";
	private static final String stopNameCarolaPlatz = "Dresden Carolaplatz";
	private static final String stopNameSynagoge = "Dresden Synagoge";
	private static final String stopNamePragerStrasse = "Dresden Prager Straße";
	private static final String stopNamePostPlatz = "Dresden Postplatz";
	private static final String stopNameTheaterPlatz = "Dresden Theaterplatz";
	private static final String stopNameNeuStaedterMarkt = "Dresden Neustädter Markt";

	double walpurgisstrasseToPragerStrasse = 100;


	static Set<String> relevantStops = Set.of(stopNameWalpurgisstrasse, stopNamePirnaischerPlatz, stopNameCarolaPlatz, stopNameSynagoge,
		stopNamePragerStrasse, stopNamePostPlatz, stopNameTheaterPlatz, stopNameNeuStaedterMarkt);
	// transit stop name to stop ID map.
	static Map<String, Set<Id<TransitStopFacility>>> stopNameToStopIdMap = new HashMap<>();

	static void reroutePtLines(TransitSchedule transitSchedule) {
		// collect stop ID information
		for (TransitStopFacility transitStop : transitSchedule.getFacilities().values()) {
			String stopName = transitStop.getName();
			if (relevantStops.contains(stopName)) {
				stopNameToStopIdMap.computeIfAbsent(stopName, s -> new HashSet<>()).add(transitStop.getId());
			}
		}

		TransitLine tramLine3 = transitSchedule.getTransitLines().get(Id.create("regio_3---5113", TransitLine.class));
		for (TransitRoute route : tramLine3.getRoutes().values()) {
			// modify route on the network
			List<Id<Link>> originalRoute = route.getRoute().getLinkIds();
			// TODO modify route (sequence of links)
			List<Id<Link>> modifiedRoute = new ArrayList<>(originalRoute);
			route.getRoute().setLinkIds(modifiedRoute.getFirst(), modifiedRoute, modifiedRoute.getLast());

			// modify stop lists
			double arrivalTimeOfPreviousStop = 0;
			double delay = 0;
			for (TransitRouteStop stop : route.getStops()) {
				// check if the stop is the begin of diversion


				// keep track of the offset time
				arrivalTimeOfPreviousStop = stop.getArrivalOffset().seconds();

				// update arrival and departure time due to delay
				stop.getArrivalOffset().seconds(); // TODO this is not modifiable!!! Need to create a new stop!!!

			}

		}



	}
}
