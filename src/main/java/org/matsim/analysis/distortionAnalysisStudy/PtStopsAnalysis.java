package org.matsim.analysis.distortionAnalysisStudy;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class PtStopsAnalysis {
	static Logger log = LogManager.getLogger(PtStopsAnalysis.class);

	public static void main(String[] args) throws IOException {
		Config config = ConfigUtils.createConfig();
		config.transit().setTransitScheduleFile("https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/dresden/dresden-v1.0/input/dresden-v1.0-transitSchedule.xml.gz");
		config.transit().setVehiclesFile("https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/dresden/dresden-v1.0/input/dresden-v1.0-transitVehicles.xml.gz");
		config.network().setInputFile("https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/dresden/dresden-v1.0/input/dresden-v1.0-network-with-pt.xml.gz");

		Scenario scenario = ScenarioUtils.loadScenario(config);
		ShpOptions shp = new ShpOptions("input/v1.0/additional_shp/dresden-plus-2km-offset.shp", "EPSG:25832", null);
		Geometry studyArea = shp.getGeometry("EPSG:25832");
		Set<TransitStopFacility> relevantStopFacilities = new HashSet<>();

		for (TransitStopFacility stopFacility : scenario.getTransitSchedule().getFacilities().values()) {
			Coord coord = stopFacility.getCoord();
			if (coord != null && MGC.coord2Point(coord).within(studyArea)) {
				relevantStopFacilities.add(stopFacility);
			}
		}

		// read home location of people
		log.info("Reading home location of the people...");
		Map<String, Coord> homeLocations = new HashMap<>();
		try (BufferedReader reader = Files.newBufferedReader(Path.of("/Users/luchengqi/Documents/MATSimScenarios/Dresden/distortion-study-analysis/analysis/home-locations.tsv"), StandardCharsets.UTF_8);
			 CSVParser parser = new CSVParser(reader, CSVFormat.TDF.builder()
				 .setHeader()
				 .setSkipHeaderRecord(true)
				 .build())) {

			for (CSVRecord record : parser) {
				String id = record.get("person_id");
				double x = Double.parseDouble(record.get("x"));
				double y = Double.parseDouble(record.get("y"));
				Coord homeCoord = new Coord(x, y);
				homeLocations.put(id, homeCoord);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		// find nearest transit stop for each person home location
		log.info("Calculating nearest transit stop for each home locations...");
		Map<String, Double> distToNearestTransitStopMap = new HashMap<>();
		Map<String, String> nearestTransitStopMap = new HashMap<>();
		for (Map.Entry<String, Coord> stringCoordEntry : homeLocations.entrySet()) {
			double minDist = Double.MAX_VALUE;
			TransitStopFacility nearestStop = null;
			Coord homeCoord = stringCoordEntry.getValue();
			for (TransitStopFacility stop : relevantStopFacilities) {
				double dist = CoordUtils.calcEuclideanDistance(homeCoord, stop.getCoord());
				if (dist < minDist) {
					minDist = dist;
					nearestStop = stop;
				}
			}

			assert nearestStop != null;
			distToNearestTransitStopMap.put(stringCoordEntry.getKey(), minDist);
			nearestTransitStopMap.put(stringCoordEntry.getKey(), nearestStop.getName());
		}


		// write down tsv file
		log.info("Writing down results...");
		CSVPrinter csvPrinter = new CSVPrinter(new FileWriter("/Users/luchengqi/Documents/MATSimScenarios/Dresden/distortion-study-analysis/analysis/home-locations-with-nearest-transit-stop.tsv", false), CSVFormat.TDF);
		csvPrinter.printRecord("person_id", "x", "y", "dist_to_nearest_transit_stop", "time_to_nearest_transit_stop", "nearest_transit_stop");
		for (String id : homeLocations.keySet()) {
			csvPrinter.printRecord(
				id,
				Double.toString(homeLocations.get(id).getX()),
				Double.toString(homeLocations.get(id).getY()),
				Double.toString(distToNearestTransitStopMap.get(id)),
				Double.toString(distToNearestTransitStopMap.get(id) * 1.3 / 1.23),
				nearestTransitStopMap.get(id)
			);
		}
		csvPrinter.close();
	}
}
