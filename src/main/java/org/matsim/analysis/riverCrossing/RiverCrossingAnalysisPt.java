package org.matsim.analysis.riverCrossing;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static org.matsim.analysis.riverCrossing.BridgeCrossingAnalysisUtils.*;


public class RiverCrossingAnalysisPt {

	public static void main(String[] args) throws IOException {
		String ptSchedulePath = "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/dresden/dresden-v1.0/input/dresden-v1.0-transitSchedule.xml.gz";
		String ptVolumesAnalysis = "/Users/luchengqi/Desktop/pt_pax_volumes.csv.gz";
		String remark = "before";
		String output = "/Users/luchengqi/Desktop/bridges_pt_pax_volumes_" + remark + ".csv";

		// identify the bridge crossing places
		// load transit schedule
		Config config = ConfigUtils.createConfig();
		config.transit().setTransitScheduleFile(ptSchedulePath);

		Scenario scenario = ScenarioUtils.loadScenario(config);
		TransitSchedule transitSchedule = scenario.getTransitSchedule();

		Set<String> relevantStops = getRelevantStops();
		Map<Id<TransitStopFacility>, String> stopIdToNameMap = new HashMap<>();

		// extract relevant stop ids
		for (TransitStopFacility stop : transitSchedule.getFacilities().values()) {
			String stopName = stop.getName();
			if (relevantStops.contains(stopName)) {
				stopIdToNameMap.put(stop.getId(), stopName);
			}
		}

		// unzip gz file
		Reader reader = new BufferedReader(
			new InputStreamReader(
				new GZIPInputStream(
					Files.newInputStream(Path.of(ptVolumesAnalysis))
				)
			)
		);

		CSVParser parser = CSVParser.builder()
			.setReader(reader)
			.setFormat(CSVFormat.Builder.create().setHeader().setSkipHeaderRecord(true).get())
			.get();


		// analyze traffic flow
		BridgePtPassengerVolumesData data = new BridgePtPassengerVolumesData();
		for (CSVRecord record : parser) {
			Id<TransitStopFacility> currentStopId = Id.create(record.get("stop"), TransitStopFacility.class);
			Id<TransitStopFacility> previousStopId = Id.create(record.get("stopPrevious"), TransitStopFacility.class);
			if (stopIdToNameMap.containsKey(currentStopId) && stopIdToNameMap.containsKey(previousStopId)) {
				String currentStop = stopIdToNameMap.get(currentStopId);
				String previousStop = stopIdToNameMap.get(previousStopId);

				double passengerOnboard = Double.parseDouble(record.get("passengersAtArrival"));
				//	double scheduledArrivalTime = Double.parseDouble(record.get("arrivalTimeScheduled"));
				//	double arrivalDelay = Double.parseDouble(record.get("arrivalDelay"));
				//	double arrivalTime = scheduledArrivalTime + arrivalDelay;

				if (isCarolaBridge(previousStop, currentStop)) {
					data.carolaBridgeCountData.total += passengerOnboard;
					if (isCarolaBridgeSouthToNorth(previousStop, currentStop)) {
						data.carolaBridgeCountData.southToNorth += passengerOnboard;
					} else {
						data.carolaBridgeCountData.northToSouth += passengerOnboard;
					}
				}

				if (isAugustusBridge(previousStop, currentStop)) {
					data.augustusBridgeCountData.total += passengerOnboard;
					if (isAugustusBridgeSouthToNorth(previousStop, currentStop)) {
						data.augustusBridgeCountData.southToNorth += passengerOnboard;
					} else {
						data.augustusBridgeCountData.northToSouth += passengerOnboard;
					}
				}

				if (isMarienBridge(previousStop, currentStop)) {
					data.marienBridgeCountData.total += passengerOnboard;
					if (isMarienBridgeSouthToNorth(previousStop, currentStop)) {
						data.marienBridgeCountData.southToNorth += passengerOnboard;
					} else {
						data.marienBridgeCountData.northToSouth += passengerOnboard;
					}
				}

				if (isFluegelwegBridge(previousStop, currentStop)) {
					data.fluegelwegBridgeCountData.total += passengerOnboard;
					if (isFluegelwegBridgeSouthToNorth(previousStop, currentStop)) {
						data.fluegelwegBridgeCountData.southToNorth += passengerOnboard;
					} else {
						data.fluegelwegBridgeCountData.northToSouth += passengerOnboard;
					}
				}

				if (isAlbertBridge(previousStop, currentStop)) {
					data.albertBridgeCountData.total += passengerOnboard;
					if (isAlbertBridgeSouthToNorth(previousStop, currentStop)) {
						data.albertBridgeCountData.southToNorth += passengerOnboard;
					} else {
						data.albertBridgeCountData.northToSouth += passengerOnboard;
					}
				}

				if (isWsbBridge(previousStop, currentStop)) {
					data.wsbBridgeCountData.total += passengerOnboard;
					if (isWsbBridgeSouthToNorth(previousStop, currentStop)) {
						data.wsbBridgeCountData.southToNorth += passengerOnboard;
					} else {
						data.wsbBridgeCountData.northToSouth += passengerOnboard;
					}
				}

				if (isBlauesWunderBridge(previousStop, currentStop)) {
					data.blauesWunderBridgeCountData.total += passengerOnboard;
					if (isBlauesWunderBridgeSouthToNorth(previousStop, currentStop)) {
						data.blauesWunderBridgeCountData.southToNorth += passengerOnboard;
					} else {
						data.blauesWunderBridgeCountData.northToSouth += passengerOnboard;
					}
				}

				if (isTrainMarienBridge(previousStop, currentStop)) {
					data.trainMarienBridge.total += passengerOnboard;
					if (isTrainMarienBridgeSouthToNorth(previousStop, currentStop)) {
						data.trainMarienBridge.southToNorth += passengerOnboard;
					} else {
						data.trainMarienBridge.northToSouth += passengerOnboard;
					}
				}

			}
		}

		// write out results
		CSVPrinter csvPrinter = new CSVPrinter(new FileWriter(output), CSVFormat.DEFAULT);
		csvPrinter.printRecord("bridge", "south_to_north", "north_to_south", "total",
			"north_end_x", "north_end_y", "south_end_x", "south_end_y", "remark");
		csvPrinter.printRecord(data.carolaBridgeCountData.bridgeName, data.carolaBridgeCountData.southToNorth,
			data.carolaBridgeCountData.northToSouth, data.carolaBridgeCountData.total,
			data.carolaBridgeCountData.northEnd.getX(), data.carolaBridgeCountData.northEnd.getY(),
			data.carolaBridgeCountData.southEnd.getX(), data.carolaBridgeCountData.southEnd.getY(), remark);
		csvPrinter.printRecord(data.augustusBridgeCountData.bridgeName, data.augustusBridgeCountData.southToNorth,
			data.augustusBridgeCountData.northToSouth, data.augustusBridgeCountData.total,
			data.augustusBridgeCountData.northEnd.getX(), data.augustusBridgeCountData.northEnd.getY(),
			data.augustusBridgeCountData.southEnd.getX(), data.augustusBridgeCountData.southEnd.getY(), remark);
		csvPrinter.printRecord(data.marienBridgeCountData.bridgeName, data.marienBridgeCountData.southToNorth,
			data.marienBridgeCountData.northToSouth, data.marienBridgeCountData.total,
			data.marienBridgeCountData.northEnd.getX(), data.marienBridgeCountData.northEnd.getY(),
			data.marienBridgeCountData.southEnd.getX(), data.marienBridgeCountData.southEnd.getY(), remark);
		csvPrinter.printRecord(data.fluegelwegBridgeCountData.bridgeName, data.fluegelwegBridgeCountData.southToNorth,
			data.fluegelwegBridgeCountData.northToSouth, data.fluegelwegBridgeCountData.total,
			data.fluegelwegBridgeCountData.northEnd.getX(), data.fluegelwegBridgeCountData.northEnd.getY(),
			data.fluegelwegBridgeCountData.southEnd.getX(), data.fluegelwegBridgeCountData.southEnd.getY(), remark);
		csvPrinter.printRecord(data.albertBridgeCountData.bridgeName, data.albertBridgeCountData.southToNorth,
			data.albertBridgeCountData.northToSouth, data.albertBridgeCountData.total,
			data.albertBridgeCountData.northEnd.getX(), data.albertBridgeCountData.northEnd.getY(),
			data.albertBridgeCountData.southEnd.getX(), data.albertBridgeCountData.southEnd.getY(), remark);
		csvPrinter.printRecord(data.wsbBridgeCountData.bridgeName, data.wsbBridgeCountData.southToNorth,
			data.wsbBridgeCountData.northToSouth, data.wsbBridgeCountData.total,
			data.wsbBridgeCountData.northEnd.getX(), data.wsbBridgeCountData.northEnd.getY(),
			data.wsbBridgeCountData.southEnd.getX(), data.wsbBridgeCountData.southEnd.getY(), remark);
		csvPrinter.printRecord(data.blauesWunderBridgeCountData.bridgeName, data.blauesWunderBridgeCountData.southToNorth,
			data.blauesWunderBridgeCountData.northToSouth, data.blauesWunderBridgeCountData.total,
			data.blauesWunderBridgeCountData.northEnd.getX(), data.blauesWunderBridgeCountData.northEnd.getY(),
			data.blauesWunderBridgeCountData.southEnd.getX(), data.blauesWunderBridgeCountData.southEnd.getY(), remark);
		csvPrinter.printRecord(data.trainMarienBridge.bridgeName, data.trainMarienBridge.southToNorth,
			data.trainMarienBridge.northToSouth, data.trainMarienBridge.total,
			data.trainMarienBridge.northEnd.getX(), data.trainMarienBridge.northEnd.getY(),
			data.trainMarienBridge.southEnd.getX(), data.trainMarienBridge.southEnd.getY(), remark);
		csvPrinter.close();
	}
}
