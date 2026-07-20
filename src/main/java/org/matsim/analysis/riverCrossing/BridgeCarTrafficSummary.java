package org.matsim.analysis.riverCrossing;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.lang.mutable.MutableInt;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.feature.simple.SimpleFeature;
import org.locationtech.jts.geom.Geometry;
import org.matsim.analysis.VolumesAnalyzer;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.router.DefaultAnalysisMainModeIdentifier;
import org.matsim.core.router.MainModeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.geometry.geotools.MGC;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.matsim.analysis.riverCrossing.BridgeCrossingAnalysisUtils.*;
import static org.matsim.contrib.drt.analysis.afterSimAnalysis.DrtVehicleStoppingTaskWriter.glob;

public class BridgeCarTrafficSummary {
	// Link ids (manual input, v1.0 and v1.1)
	static final Id<Link> linkIdCarolaBridgeSouthToNorth = Id.createLinkId("901959078");
	static final Id<Link> linkIdCarolaBridgeNorthToSouth = Id.createLinkId("4214231");

	static final Id<Link> motorwayA4SouthToNorth = Id.createLinkId("31059226");
	static final Id<Link> motorwayA4NorthToSouth = Id.createLinkId("318199257");

	static final Id<Link> linkIdFluegelwegBridgeSouthToNorth = Id.createLinkId("14448952");
	static final Id<Link> linkIdFluegelwegBridgeNorthToSouth = Id.createLinkId("425728245");

	static final Id<Link> linkIdMarienBridgeSouthToNorth = Id.createLinkId("761288685");
	static final Id<Link> linkIdMarienBridgeNorthToSouth = Id.createLinkId("-488766980");

	static final Id<Link> linkIdAugustusBridgeSouthToNorth = Id.createLinkId("1031454500");
	static final Id<Link> linkIdAugustusBridgeNorthToSouth = Id.createLinkId("-264360404");

	static final Id<Link> linkIdAlbertBridgeSouthToNorth = Id.createLinkId("505502627#0");
	static final Id<Link> linkIdAlbertBridgeNorthToSouth = Id.createLinkId("-264360396#1");

	static final Id<Link> linkIdWsbBridgeSouthToNorth = Id.createLinkId("132572494");
	static final Id<Link> linkIdWsbBridgeNorthToSouth = Id.createLinkId("277710971");

	static final Id<Link> linkIdLoschwitzerBridgeSouthToNorth = Id.createLinkId("30129851");
	static final Id<Link> linkIdLoschwitzerBridgeNorthToSouth = Id.createLinkId("-30129851");

	static final Id<Link> linkIdNiederwarthaerBridgeSouthToNorth = Id.createLinkId("419106272");
	static final Id<Link> linkIdNiederwarthaerBridgeNorthToSouth = Id.createLinkId("22112767");

	static final String UNKNOWN = "unknown";

	private static final Logger log = LogManager.getLogger(BridgeCarTrafficSummary.class);

	public static void main(String[] args) throws IOException {
		String outputFolderBefore = args.length >= 2 ? args[0] : "/Users/luchengqi/Documents/MATSimScenarios/Dresden/dresden-scenario/v1.1/output/10pct/before";
		String outputFolderAfter = args.length >= 2 ? args[1] : "/Users/luchengqi/Documents/MATSimScenarios/Dresden/dresden-scenario/v1.1/output/10pct/after";
		String shpFile = args.length >= 3 ? args[2] : "/Users/luchengqi/Documents/MATSimScenarios/Dresden/dresden-scenario/shp/ortsteile-dresden-only.shp";
		double sampleSize = args.length == 4 ? Double.parseDouble(args[3]) : 0.1;

		Network network = NetworkUtils.readNetwork(glob(Path.of(outputFolderBefore), "*output_network.xml.*").orElseThrow().toString());
		ShpOptions shp = new ShpOptions(Path.of(shpFile), "EPSG:25832", null);

		// analyze traffic volume on the bridges
		analyzeTrafficVolume(outputFolderBefore, network, sampleSize, "before");
		analyzeTrafficVolume(outputFolderAfter, network, sampleSize, "after");

		// analyze the behavior change due to the bridge collapse
		analyzeBehaviorChange(outputFolderBefore, outputFolderAfter, shp, sampleSize);
	}

	private static void analyzeBehaviorChange(String outputFolderBefore, String outputFolderAfter, ShpOptions shp, double sampleSize) throws IOException {
		Population experiencedPlansBefore = PopulationUtils.readPopulation(glob(Path.of(outputFolderBefore), "*output_experienced_plans.xml.*").orElseThrow().toString());
		Population experiencedPlansAfter = PopulationUtils.readPopulation(glob(Path.of(outputFolderAfter), "*output_experienced_plans.xml.*").orElseThrow().toString());
		List<SimpleFeature> features = shp.readFeatures();

		Map<String, MutableInt> departuresPerRegion = new HashMap<>();
		Map<String, MutableInt> arrivalsPerRegion = new HashMap<>();

		String behaviorChangeAnalysisOutput = outputFolderAfter + "/behavior-change-analysis.csv";
		CSVPrinter behaviorAnalysisPrinter = new CSVPrinter(new FileWriter(behaviorChangeAnalysisOutput), CSVFormat.DEFAULT);
		behaviorAnalysisPrinter.printRecord("person", "trip_id", "main_mode_before", "main_mode_after", "bridge_used_before", "alternative_bridge_used_when_no_mode_change", "from_region_id", "to_region_id");

		MainModeIdentifier mainModeIdentifier = new DefaultAnalysisMainModeIdentifier();
		for (Person person : experiencedPlansBefore.getPersons().values()) {
			List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());
			for (int i = 0; i < trips.size(); i++) {
				TripStructureUtils.Trip trip = trips.get(i);
				String mainModeBefore = mainModeIdentifier.identifyMainMode(trip.getTripElements());
				if (mainModeBefore.equals(TransportMode.car) || mainModeBefore.equals(TransportMode.bike) || mainModeBefore.contains(TransportMode.truck)) {
					for (Leg leg : trip.getLegsOnly()) {
						if (leg.getMode().equals(mainModeBefore)) {
							NetworkRoute routeBefore = (NetworkRoute) leg.getRoute();
							Set<Id<Link>> linksInTheRoute = new HashSet<>(routeBefore.getLinkIds());
							// to be consistent with the volumes analysis: start link in the route is not counted
							linksInTheRoute.add(routeBefore.getEndLinkId());

							String bridgeUsedBefore = identifyBridge(linksInTheRoute);
							if (!bridgeUsedBefore.equals(UNKNOWN)) {
								// the original trip uses one of the bridges in and around Dresden.
								int tripIdx = i + 1;
								String tripId = person.getId().toString() + "_" + tripIdx;

								// get od relation of the trip
								Coord fromCoord = trip.getOriginActivity().getCoord();
								String fromRegionId = getRegionId(features, fromCoord);
								departuresPerRegion.computeIfAbsent(fromRegionId, k -> new MutableInt(0)).increment();

								Coord toCoord = trip.getDestinationActivity().getCoord();
								String toRegionId = getRegionId(features, toCoord);
								arrivalsPerRegion.computeIfAbsent(toRegionId, k -> new MutableInt(0)).increment();

								Person personAfterChange = experiencedPlansAfter.getPersons().get(person.getId());
								List<TripStructureUtils.Trip> tripsAfter = TripStructureUtils.getTrips(personAfterChange.getSelectedPlan());
								if (i >= tripsAfter.size()) {
									log.warn("Person {} only has {} trips after the Carola bridge collapsed.", personAfterChange.getId().toString(), tripsAfter.size());
									continue;
								}
								TripStructureUtils.Trip tripAfter = tripsAfter.get(i);
								String mainModeAfter = mainModeIdentifier.identifyMainMode(tripAfter.getTripElements());

								String alternativeBridgeUsed = null;
								if (mainModeBefore.equals(mainModeAfter)) {
									// identify the alternative bridge used
									for (Leg legAfter : tripAfter.getLegsOnly()) {
										if (legAfter.getMode().equals(mainModeAfter)) {
											NetworkRoute routeAfter = (NetworkRoute) legAfter.getRoute();
											Set<Id<Link>> linksInTheRouteAfter = new HashSet<>(routeAfter.getLinkIds());
											linksInTheRouteAfter.add(routeAfter.getEndLinkId());
											alternativeBridgeUsed = identifyAlternativeBridgeUsed(linksInTheRouteAfter);
										}
									}
								}
								behaviorAnalysisPrinter.printRecord(person.getId().toString(), tripId, mainModeBefore, mainModeAfter, bridgeUsedBefore, alternativeBridgeUsed, fromRegionId, toRegionId);
							}
						}
					}
				}
			}
		}
		behaviorAnalysisPrinter.close();

		// print out od relations
		String odRelationsOutput = outputFolderAfter + "/carola-bridge-trips-od-relations.csv";
		CSVPrinter odRelationsPrinter = new CSVPrinter(new FileWriter(odRelationsOutput), CSVFormat.DEFAULT);
		odRelationsPrinter.printRecord("OBJECTID", "departures", "arrivals");
		for (SimpleFeature feature : features) {
			String regionId = feature.getAttribute("OBJECTID").toString();
			double departures = departuresPerRegion.getOrDefault(regionId, new MutableInt(0)).intValue() / sampleSize;
			double arrivals = arrivalsPerRegion.getOrDefault(regionId, new MutableInt(0)).intValue() / sampleSize;
			odRelationsPrinter.printRecord(regionId, departures, arrivals);
		}
		odRelationsPrinter.close();
	}

	private static void analyzeTrafficVolume(String outputFolder, Network network, double sampleSize, String caseName) throws IOException {
		VolumesAnalyzer volumes = new VolumesAnalyzer(3600, 36 * 3600, network, true);
		EventsManager manager = EventsUtils.createEventsManager();
		manager.addHandler(volumes);

		manager.initProcessing();
		String eventPathString = glob(Path.of(outputFolder), "*output_events*").orElseThrow().toString();
		EventsUtils.readEvents(manager, eventPathString);
		manager.finishProcessing();

		// write results to file
		String output = outputFolder + "/bridge-traffic-summary-" + caseName + ".csv";
		CSVPrinter csvPrinter = new CSVPrinter(new FileWriter(output), CSVFormat.DEFAULT);
		csvPrinter.printRecord("bridge", "kfz_total", "kfz_sourth_to_north", "kfz_north_to_south", "pkw_south_to_north", "pkw_north_to_south", "lkw_south_to_north", "lkw_north_to_south", "bike_south_to_north", "bike_north_to_south", "remark");

		csvPrinter.printRecord(
			prepareRow("Carolabrücke", getCountData(volumes, linkIdCarolaBridgeSouthToNorth, linkIdCarolaBridgeNorthToSouth, sampleSize), caseName)
		);
		csvPrinter.printRecord(
			prepareRow("Elbebrücke Dresden (A4)", getCountData(volumes, motorwayA4SouthToNorth, motorwayA4NorthToSouth, sampleSize), caseName)
		);
		csvPrinter.printRecord(
			prepareRow("Flügelwegbrücke", getCountData(volumes, linkIdFluegelwegBridgeSouthToNorth, linkIdFluegelwegBridgeNorthToSouth, sampleSize), caseName)
		);
		csvPrinter.printRecord(
			prepareRow("Marienbrücke", getCountData(volumes, linkIdMarienBridgeSouthToNorth, linkIdMarienBridgeNorthToSouth, sampleSize), caseName)
		);
		csvPrinter.printRecord(
			prepareRow("Augustusbrücke", getCountData(volumes, linkIdAugustusBridgeSouthToNorth, linkIdAugustusBridgeNorthToSouth, sampleSize), caseName)
		);
		csvPrinter.printRecord(
			prepareRow("Albertbrücke", getCountData(volumes, linkIdAlbertBridgeSouthToNorth, linkIdAlbertBridgeNorthToSouth, sampleSize), caseName)
		);
		csvPrinter.printRecord(
			prepareRow("Waldschlösschenbrücke", getCountData(volumes, linkIdWsbBridgeSouthToNorth, linkIdWsbBridgeNorthToSouth, sampleSize), caseName)
		);
		csvPrinter.printRecord(
			prepareRow("Loschwitzer Brücke", getCountData(volumes, linkIdLoschwitzerBridgeSouthToNorth, linkIdLoschwitzerBridgeNorthToSouth, sampleSize), caseName)
		);
		csvPrinter.printRecord(
			prepareRow("Niederwarthaer Brücke", getCountData(volumes, linkIdNiederwarthaerBridgeSouthToNorth, linkIdNiederwarthaerBridgeNorthToSouth, sampleSize), caseName)
		);
		csvPrinter.close();
	}

	private static List<Double> getCountData(VolumesAnalyzer volumes, Id<Link> southToNorthLinkId, Id<Link> northToSouthLinkId, double sampleSize) {
		List<Double> countData = new ArrayList<>();
		Set<String> modes = volumes.getModes();
		Set<String> trucks = modes.stream().filter(s -> s.contains(TransportMode.truck)).collect(Collectors.toSet());

		double pkwSouthToNorth = getDailyVolume(volumes, southToNorthLinkId, TransportMode.car) / sampleSize;
		double pkwNorthToSouth = getDailyVolume(volumes, northToSouthLinkId, TransportMode.car) / sampleSize;

		double lkwSouthToNorth = trucks.stream().mapToDouble(mode -> getDailyVolume(volumes, southToNorthLinkId, mode)).sum() / sampleSize;
		double lkwNorthToSouth = trucks.stream().mapToDouble(mode -> getDailyVolume(volumes, northToSouthLinkId, mode)).sum() / sampleSize;

		double bikeSouthToNorth = getDailyVolume(volumes, southToNorthLinkId, TransportMode.bike) / sampleSize;
		double bikeNorthToSouth = getDailyVolume(volumes, northToSouthLinkId, TransportMode.bike) / sampleSize;

		// total Kfz volume
		countData.add(pkwSouthToNorth + lkwSouthToNorth + pkwNorthToSouth + lkwNorthToSouth);

		// Kfz south to north
		countData.add(pkwSouthToNorth + lkwSouthToNorth);

		// Kfz north to south
		countData.add(pkwNorthToSouth + lkwNorthToSouth);

		// Pkw south to north
		countData.add(pkwSouthToNorth);

		// Pkw north to south
		countData.add(pkwNorthToSouth);

		// Lkw south to north
		countData.add(lkwSouthToNorth);

		// Lkw north to south
		countData.add(lkwNorthToSouth);

		// bike south to north
		countData.add(bikeSouthToNorth);

		// bike north to south
		countData.add(bikeNorthToSouth);

		return countData;
	}

	private static double getDailyVolume(VolumesAnalyzer volumes, Id<Link> linkId, String mode) {
		int[] v = volumes.getVolumesForLink(linkId, mode);
		return v == null ? 0 : Arrays.stream(v).sum();
	}

	private static List<Object> prepareRow(String bridgeName, List<Double> values, String caseName) {
		List<Object> row = new ArrayList<>();
		row.add(bridgeName);
		row.addAll(values);
		row.add(caseName);
		return row;
	}

	private static String identifyAlternativeBridgeUsed(Set<Id<Link>> linksInTheRouteAfter) {
		String identifiedBridge = identifyBridge(linksInTheRouteAfter);
		if (identifiedBridge.equals(CAROLA_BRIDGE)) {
			throw new RuntimeException("Carola bridge is used in the route after the Carola bridge collapsed. This should not happen.");
		}
		return identifiedBridge;
	}

	private static String identifyBridge(Set<Id<Link>> linksInTheRouteAfter) {
		if (linksInTheRouteAfter.contains(linkIdCarolaBridgeSouthToNorth) || linksInTheRouteAfter.contains(linkIdCarolaBridgeNorthToSouth)) {
			return CAROLA_BRIDGE;
		}

		if (linksInTheRouteAfter.contains(motorwayA4SouthToNorth) || linksInTheRouteAfter.contains(motorwayA4NorthToSouth)) {
			return ELBE_BRIDGE_A4;
		}

		if (linksInTheRouteAfter.contains(linkIdFluegelwegBridgeSouthToNorth) || linksInTheRouteAfter.contains(linkIdFluegelwegBridgeNorthToSouth)) {
			return FLUEGELWEG_BRIDGE;
		}

		if (linksInTheRouteAfter.contains(linkIdMarienBridgeSouthToNorth) || linksInTheRouteAfter.contains(linkIdMarienBridgeNorthToSouth)) {
			return MARIEN_BRIDGE;
		}

		if (linksInTheRouteAfter.contains(linkIdAugustusBridgeSouthToNorth) || linksInTheRouteAfter.contains(linkIdAugustusBridgeNorthToSouth)) {
			return AUGUSTUS_BRIDGE;
		}

		if (linksInTheRouteAfter.contains(linkIdAlbertBridgeSouthToNorth) || linksInTheRouteAfter.contains(linkIdAlbertBridgeNorthToSouth)) {
			return ALBERT_BRIDGE;
		}

		if (linksInTheRouteAfter.contains(linkIdWsbBridgeSouthToNorth) || linksInTheRouteAfter.contains(linkIdWsbBridgeNorthToSouth)) {
			return WSB;
		}

		if (linksInTheRouteAfter.contains(linkIdLoschwitzerBridgeSouthToNorth) || linksInTheRouteAfter.contains(linkIdLoschwitzerBridgeNorthToSouth)) {
			return BLAUES_WUNDER_BRIDGE;
		}

		if (linksInTheRouteAfter.contains(linkIdNiederwarthaerBridgeSouthToNorth) || linksInTheRouteAfter.contains(linkIdNiederwarthaerBridgeNorthToSouth)) {
			return "Niederwarthaer Brücke";
		}

		return UNKNOWN;
	}

	private static String getRegionId(List<SimpleFeature> features, Coord coord) {
		for (SimpleFeature feature : features) {
			Geometry geometry = (Geometry) feature.getDefaultGeometry();
			if (MGC.coord2Point(coord).within(geometry)) {
				return feature.getAttribute("OBJECTID").toString();
			}
		}
		return UNKNOWN;
	}
}
