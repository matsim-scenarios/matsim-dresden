package org.matsim.run.scenarios;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.TransitScheduleFactoryImpl;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.vehicles.*;

import java.util.*;

public class ReroutePtLinesCarolaBridge {
	private static final String stopNameWalpurgisstrasse = "Dresden Walpurgisstraße";
	private static final String stopNamePirnaischerPlatz = "Dresden Pirnaischer Platz";
	private static final String stopNameCarolaPlatz = "Dresden Carolaplatz";
	private static final String stopNameSynagoge = "Dresden Synagoge";
	private static final String stopNameAlbertplatz = "Dresden Albertplatz";
	private static final String stopNameHbf = "Dresden Hauptbahnhof";
	private static final String stopNameStrassburgerPlatz = "Dresden Straßburger Platz";
	private static final String stopNameBischofsweg = "Dresden Bischofsweg";

	// testing script for local runs
	public static void main(String[] args) {
		Config config = ConfigUtils.createConfig();
		config.transit().setTransitScheduleFile("https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/dresden/dresden-v1.0/input/dresden-v1.0-transitSchedule.xml.gz");
		config.transit().setVehiclesFile("https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/dresden/dresden-v1.0/input/dresden-v1.0-transitVehicles.xml.gz");
		config.network().setInputFile("https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/dresden/dresden-v1.0/input/dresden-v1.0-network-with-pt.xml.gz");
		Scenario scenario = ScenarioUtils.loadScenario(config);
		TransitSchedule transitSchedule = scenario.getTransitSchedule();
		Vehicles transitVehicles = scenario.getTransitVehicles();
		reroutePtLines(transitSchedule, transitVehicles);

		// write the modified transit schedule and transit vehicles
		transitSchedule.getAttributes().putAttribute("coordinateReferenceSystem", "EPSG:25832");
		new TransitScheduleWriter(transitSchedule).writeFile("/Users/luchengqi/Desktop/dresden-v1.0-transitSchedule-modified.xml.gz");
		VehicleUtils.writeVehicles(transitVehicles, "/Users/luchengqi/Desktop/dresden-v1.0-transitVehicles-modified.xml.gz");
	}

	/**
	 * In this script, we reroute the tram lines based on an simplified approach
	 * Step 1. Cut the tram lines / bus lines (routes) that cross the Carola bridge into 2 half
	 * Step 2. Add artificial reinforcement lines based on the actual rerouting of the tram lines
	 *
	 * @param transitSchedule transit schedule to be modified
	 */
	static void reroutePtLines(TransitSchedule transitSchedule, Vehicles vehicles) {
		TransitScheduleFactoryImpl factory = new TransitScheduleFactoryImpl();

		// Part 1: Break the impacted tram lines (routes) into two parts
		TransitLine tramLine3 = transitSchedule.getTransitLines().get(Id.create("regio_3---5113", TransitLine.class));
		TransitLine tramLine7 = transitSchedule.getTransitLines().get(Id.create("regio_7---4062", TransitLine.class));
		TransitLine tramLine12 = transitSchedule.getTransitLines().get(Id.create("regio_12---7013", TransitLine.class));
		TransitLine busLine261 = transitSchedule.getTransitLines().get(Id.create("regio_261---16895", TransitLine.class));

		breakLine(tramLine3, vehicles, stopNameWalpurgisstrasse, stopNameCarolaPlatz, factory, "tram");
		breakLine(tramLine7, vehicles, stopNameWalpurgisstrasse, stopNameCarolaPlatz, factory, "tram");
		// there are one route in line 12 that crosses the Carola bridge.
		breakLine(tramLine12, vehicles, stopNameSynagoge, stopNameCarolaPlatz, factory, "tram");
		breakLine(busLine261, vehicles, stopNamePirnaischerPlatz, stopNameAlbertplatz, factory, "bus");

		// Part 2: Add artificial reinforcement lines
		TransitLine tramLine8 = transitSchedule.getTransitLines().get(Id.create("regio_8---5866", TransitLine.class));
		TransitLine tramLine10 = transitSchedule.getTransitLines().get(Id.create("regio_10---2115", TransitLine.class));
		TransitLine tramLine13 = transitSchedule.getTransitLines().get(Id.create("regio_13---4789", TransitLine.class));

		// route 3 + 7
		// 2 portion to be reinforced, -1 portion due to route 8 re-routed to somewhere else.
		// We use the original route 8 as the template for reinforcement, end result --> factor = 1
		reinforceRoute(tramLine8, vehicles, stopNameWalpurgisstrasse, stopNameCarolaPlatz, factory, "tram", 1);

		// New route 8 (to be realized by two parts: line 10 Hbf to Strassburger Platz, line 13 from Strassburger Platz to Bischofsweg)
		// To avoid excessive transfers, we divert the reinforcement to Bischofsweg instead of Carolaplatz --> 2 parts is enough.
		reinforceRoute(tramLine10, vehicles, stopNameHbf, stopNameStrassburgerPlatz, factory, "tram", 1);
		reinforceRoute(tramLine13, vehicles, stopNameStrassburgerPlatz, stopNameBischofsweg, factory, "tram", 1);
	}

	static void breakLine(TransitLine transitLine, Vehicles vehicles, String stopSouth, String stopNorth, TransitScheduleFactoryImpl factory, String mode) {
		Set<Id<TransitRoute>> routesToRemove = new HashSet<>();
		List<TransitRoute> routesToAdd = new ArrayList<>();
		VehiclesFactory vehiclesFactory = vehicles.getFactory();
		VehicleType tramVehicleType = vehicles.getVehicleTypes().get(Id.create("Tram_veh_type", VehicleType.class));
		VehicleType busVehicleType = vehicles.getVehicleTypes().get(Id.create("Bus_veh_type", VehicleType.class));

		for (TransitRoute originalRoute : transitLine.getRoutes().values()) {
			// check if the route needs to be re-routed
			List<String> listOfStops = originalRoute.getStops().stream().map(stop -> stop.getStopFacility().getName()).toList();
			if (listOfStops.contains(stopSouth) && listOfStops.contains(stopNorth)) {
				// the route is to be broken into 2 pieces
				routesToRemove.add(originalRoute.getId());
				Map<Id<Departure>, Departure> originalDepartures = originalRoute.getDepartures();

				String subRoutePart1 = originalRoute.getId().toString() + "-part-1";
				String subRoutePart2 = originalRoute.getId().toString() + "-part-2";

				int idxSouth = listOfStops.indexOf(stopSouth);
				int idxNorth = listOfStops.indexOf(stopNorth);
				int beginOfDiversionIdx = Math.min(idxSouth, idxNorth);
				int endOfDiversionIdx = Math.max(idxSouth, idxNorth);

				// create the first part of the sub-route
				// make sure the begin of diversion is NOT the first stop
				if (beginOfDiversionIdx > 0) {
					// processing network route
					NetworkRoute networkRoute1 = originalRoute.getRoute().clone();
					Id<Link> startLinkId = networkRoute1.getStartLinkId();
					Id<Link> endLinkId = networkRoute1.getLinkIds().get(beginOfDiversionIdx - 1);
					List<Id<Link>> route1LinkIds = new ArrayList<>(networkRoute1.getLinkIds());
					route1LinkIds.subList(beginOfDiversionIdx - 1, route1LinkIds.size()).clear();
					networkRoute1.setLinkIds(startLinkId, route1LinkIds, endLinkId);

					// processing stops
					List<TransitRouteStop> stops1 = new ArrayList<>(originalRoute.getStops());
					stops1.subList(beginOfDiversionIdx + 1, stops1.size()).clear();

					// create the sub-route
					TransitRoute subRoute1 = factory.createTransitRoute(Id.create(subRoutePart1, TransitRoute.class), networkRoute1, stops1, mode);
					subRoute1.getAttributes().putAttribute("simple_route_type", mode);
					routesToAdd.add(subRoute1);

					// add departures
					for (Departure originalDeparture : originalDepartures.values()) {
						String originalDepartureIdString = originalDeparture.getId().toString();
						String originalDepartureVehicleIdString = originalDeparture.getVehicleId().toString();

						Departure departure1 = factory.createDeparture(Id.create(originalDepartureIdString + "-1", Departure.class), originalDeparture.getDepartureTime());
						departure1.setVehicleId(Id.create(originalDepartureVehicleIdString + "-1", Vehicle.class));
						subRoute1.addDeparture(departure1);

						switch (mode) {
							case "tram":
								vehicles.addVehicle(vehiclesFactory.createVehicle(Id.createVehicleId(originalDepartureVehicleIdString + "-1"), tramVehicleType));
								break;
							case "bus":
								vehicles.addVehicle(vehiclesFactory.createVehicle(Id.createVehicleId(originalDepartureVehicleIdString + "-1"), busVehicleType));
								break;
							default:
								throw new RuntimeException("Unknown mode: " + mode);
						}
					}
				}

				// create the second part of the sub-route
				// make sure the end of diversion is NOT the last stop
				if (endOfDiversionIdx < listOfStops.size() - 1) {
					// processing network route
					NetworkRoute networkRoute2 = originalRoute.getRoute().clone();
					List<Id<Link>> route2LinkIds = new ArrayList<>(networkRoute2.getLinkIds());
					route2LinkIds.subList(0, endOfDiversionIdx).clear();

					Id<Link> startLinkId = originalRoute.getRoute().getLinkIds().get(endOfDiversionIdx - 1);
					Id<Link> endLinkId = originalRoute.getStops().getLast().getStopFacility().getLinkId();
					networkRoute2.setLinkIds(startLinkId, route2LinkIds, endLinkId);

					// processing stops
					TransitRouteStop newStartingStop = originalRoute.getStops().get(endOfDiversionIdx);
					double offset = newStartingStop.getDepartureOffset().seconds();
					List<TransitRouteStop> stops2 = new ArrayList<>();
					for (int i = endOfDiversionIdx; i < originalRoute.getStops().size(); i++) {
						TransitRouteStop originalStop = originalRoute.getStops().get(i);
						TransitRouteStop newStop = factory.createTransitRouteStop(originalStop.getStopFacility(),
							Math.max(0, originalStop.getArrivalOffset().seconds() - offset), originalStop.getDepartureOffset().seconds() - offset);
						newStop.setAwaitDepartureTime(true);
						stops2.add(newStop);
					}

					// create the sub-route
					TransitRoute subRoute2 = factory.createTransitRoute(Id.create(subRoutePart2, TransitRoute.class), networkRoute2, stops2, mode);
					subRoute2.getAttributes().putAttribute("simple_route_type", mode);
					routesToAdd.add(subRoute2);

					// add departures
					for (Departure originalDeparture : originalDepartures.values()) {
						String originalDepartureIdString = originalDeparture.getId().toString();
						String originalDepartureVehicleIdString = originalDeparture.getVehicleId().toString();

						Departure departure2 = factory.createDeparture(Id.create(originalDepartureIdString + "-2", Departure.class), originalDeparture.getDepartureTime() + offset);
						departure2.setVehicleId(Id.create(originalDepartureVehicleIdString + "-2", Vehicle.class));
						subRoute2.addDeparture(departure2);

						switch (mode) {
							case "tram":
								vehicles.addVehicle(vehiclesFactory.createVehicle(Id.createVehicleId(originalDepartureVehicleIdString + "-2"), tramVehicleType));
								break;
							case "bus":
								vehicles.addVehicle(vehiclesFactory.createVehicle(Id.createVehicleId(originalDepartureVehicleIdString + "-2"), busVehicleType));
								break;
							default:
								throw new RuntimeException("Unknown mode: " + mode);
						}
					}
				}

			}
		}

		// add sub-routes to transit line
		routesToAdd.forEach(transitLine::addRoute);
		// remove the original routes that are split into 2 parts
		routesToRemove.forEach(transitRouteId -> transitLine.removeRoute(transitLine.getRoutes().get(transitRouteId)));
	}

	/**
	 *
	 * @param transitLine transit line to add reinforcement routes to
	 * @param stopSouth the stop on the south end
	 * @param stopNorth the stop on the north end
	 * @param factory transit schedule factory
	 * @param mode tram or bus
	 * @param reinforcementFactor currently only integer value is supported, i.e., 1, 2, 3, ...
	 */
	static void reinforceRoute(TransitLine transitLine, Vehicles vehicles, String stopSouth, String stopNorth, TransitScheduleFactoryImpl factory, String mode, int reinforcementFactor) {
		List<TransitRoute> routesToAdd = new ArrayList<>();
		VehiclesFactory vehiclesFactory = vehicles.getFactory();
		VehicleType tramVehicleType = vehicles.getVehicleTypes().get(Id.create("Tram_veh_type", VehicleType.class));
		VehicleType busVehicleType = vehicles.getVehicleTypes().get(Id.create("Bus_veh_type", VehicleType.class));

		for (TransitRoute originalRoute : transitLine.getRoutes().values()) {
			// check if the the route is relevant
			List<String> listOfStops = originalRoute.getStops().stream().map(stop -> stop.getStopFacility().getName()).toList();
			if (listOfStops.contains(stopSouth) && listOfStops.contains(stopNorth)) {
				Map<Id<Departure>, Departure> originalDepartures = originalRoute.getDepartures();

				int idx1 = listOfStops.indexOf(stopSouth);
				int idx2 = listOfStops.indexOf(stopNorth);
				int beginOfReinforceRoute = Math.min(idx1, idx2);
				int endOfReinforceRoute = Math.max(idx1, idx2);

				// stops
				List<TransitRouteStop> extractionOfTheStops = new ArrayList<>(originalRoute.getStops());
				List<TransitRouteStop> reinforceRouteStops = new ArrayList<>();
				extractionOfTheStops = extractionOfTheStops.subList(beginOfReinforceRoute, endOfReinforceRoute + 1);
				double offset = extractionOfTheStops.getFirst().getDepartureOffset().seconds();
				for (TransitRouteStop originalStop : extractionOfTheStops) {
					TransitRouteStop newStop = factory.createTransitRouteStop(originalStop.getStopFacility(), Math.max(0, originalStop.getArrivalOffset().seconds() - offset),
						originalStop.getDepartureOffset().seconds() - offset);
					newStop.setAwaitDepartureTime(true);
					reinforceRouteStops.add(newStop);
				}

				// network routes
				NetworkRoute reinforcedNetworkRoute = originalRoute.getRoute().clone();
				List<Id<Link>> networkRoute = new ArrayList<>(originalRoute.getRoute().getLinkIds());
				networkRoute = networkRoute.subList(beginOfReinforceRoute, endOfReinforceRoute - 1);
				Id<Link> startLinkId;
				if (beginOfReinforceRoute == 0) {
					startLinkId = originalRoute.getRoute().getStartLinkId();
				} else {
					startLinkId = originalRoute.getRoute().getLinkIds().get(beginOfReinforceRoute - 1);
				}

				Id<Link> endLinkId;
				if (originalRoute.getRoute().getLinkIds().size() > endOfReinforceRoute) {
					endLinkId = originalRoute.getRoute().getLinkIds().get(endOfReinforceRoute - 1);
				} else {
					endLinkId = originalRoute.getRoute().getEndLinkId();
				}
				reinforcedNetworkRoute.setLinkIds(startLinkId, networkRoute, endLinkId);

				// create the sub-route
				String reinforceRouteName = originalRoute.getId().toString() + "-reinforcement";
				TransitRoute reinforcementSubroute = factory.createTransitRoute(Id.create(reinforceRouteName, TransitRoute.class), reinforcedNetworkRoute, reinforceRouteStops, mode);

				// add departures
				double interval;
				double earliestDepartureTime = Double.MAX_VALUE;
				double latestDepartureTime = Double.MIN_VALUE;
				if (originalDepartures.isEmpty()) {
					continue;
				}
				for (Departure originalDeparture : originalDepartures.values()) {
					if (originalDeparture.getDepartureTime() < earliestDepartureTime) {
						earliestDepartureTime = originalDeparture.getDepartureTime();
					}
					if (originalDeparture.getDepartureTime() > latestDepartureTime) {
						latestDepartureTime = originalDeparture.getDepartureTime();
					}
				}
				interval = Math.min(3600, (latestDepartureTime - earliestDepartureTime) / originalDepartures.size());
				if (interval <= 0) {
					continue;
				}

				for (Departure originalDeparture : originalDepartures.values()) {
					for (int i = 0; i < reinforcementFactor; i++) {
						String originalDepartureVehicleIdString = originalDeparture.getVehicleId().toString();
						Departure newDeparture = factory.createDeparture(Id.create(originalDeparture.getId().toString() + "-reinforcement", Departure.class),
							originalDeparture.getDepartureTime() + interval / (1 + reinforcementFactor));
						newDeparture.setVehicleId(Id.createVehicleId(originalDepartureVehicleIdString + "-reinforcement"));
						reinforcementSubroute.addDeparture(newDeparture);

						switch (mode) {
							case "tram":
								vehicles.addVehicle(vehiclesFactory.createVehicle(Id.createVehicleId(originalDepartureVehicleIdString + "-reinforcement"), tramVehicleType));
								break;
							case "bus":
								vehicles.addVehicle(vehiclesFactory.createVehicle(Id.createVehicleId(originalDepartureVehicleIdString + "-reinforcement"), busVehicleType));
								break;
							default:
								throw new RuntimeException("Unknown mode: " + mode);
						}
					}
				}

				// store the reinforcement sub-route in a collection
				reinforcementSubroute.getAttributes().putAttribute("simple_route_type", mode);
				routesToAdd.add(reinforcementSubroute);
			}
		}

		// add sub-routes to transit line
		routesToAdd.forEach(transitLine::addRoute);
	}
}
