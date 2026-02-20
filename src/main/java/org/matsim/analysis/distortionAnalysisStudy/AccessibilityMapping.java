package org.matsim.analysis.distortionAnalysisStudy;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.core.utils.geometry.CoordUtils;

import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class AccessibilityMapping {
	static Logger log = LogManager.getLogger(AccessibilityMapping.class);

	public static void main(String[] args) throws IOException {

		// Accessibility file for any activity (we just need to read the measurement points id and coords)
		log.info("Reading data...");
		String accessibilityData = args.length == 3 ? args[0] : "/Users/luchengqi/Documents/MATSimScenarios/Dresden/distortion-study-analysis/output/1pct/analysis/accessibility/shop/accessibilities.csv";
		String homeLocationData = args.length == 3 ? args[1] : "/Users/luchengqi/Documents/MATSimScenarios/Dresden/distortion-study-analysis/analysis/home-locations.tsv";

		Map<String, Coord> measurementPointsCoords = new HashMap<>();
		try (BufferedReader reader = Files.newBufferedReader(Path.of(accessibilityData), StandardCharsets.UTF_8);
			 CSVParser parser = new CSVParser(reader, CSVFormat.DEFAULT.builder()
				 .setHeader()
				 .setSkipHeaderRecord(true)
				 .build())) {

			for (CSVRecord record : parser) {
				String measurePointId = record.get("id");
				if (measurementPointsCoords.containsKey(measurePointId)){
					continue;
				}
				double x = Double.parseDouble(record.get("xcoord"));
				double y = Double.parseDouble(record.get("ycoord"));
				Coord measurePointCoord = new Coord(x, y);
				measurementPointsCoords.put(measurePointId, measurePointCoord);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		log.info("Searching for nearest measurement point for each home location...");
		Map<String, Coord> homeCoords = new HashMap<>();
		try (BufferedReader reader = Files.newBufferedReader(Path.of(homeLocationData), StandardCharsets.UTF_8);
			 CSVParser parser = new CSVParser(reader, CSVFormat.TDF.builder()
				 .setHeader()
				 .setSkipHeaderRecord(true)
				 .build())) {

			for (CSVRecord record : parser) {
				String personId = record.get("person_id");
				double x = Double.parseDouble(record.get("x"));
				double y = Double.parseDouble(record.get("y"));
				Coord homeCoord = new Coord(x, y);
				homeCoords.put(personId, homeCoord);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		assert !measurementPointsCoords.isEmpty();
		assert !homeCoords.isEmpty();


		// write down home coord <-> nearest measurement point mapping
		String output = args.length == 3 ? args[2] : "/Users/luchengqi/Documents/MATSimScenarios/Dresden/distortion-study-analysis/output/1pct/analysis/accessibility/homelocation-measurement-points-mapping.tsv";
		CSVPrinter csvPrinter = new CSVPrinter(new FileWriter(output, false), CSVFormat.TDF);
		csvPrinter.printRecord("person_id", "home_x", "home_y", "nearest_measurement_point_id", "nearest_measurement_point_x", "nearest_measurement_point_y", "dist_to_nearest_measurement_point");

		for (Map.Entry<String, Coord> homeCoordEntry : homeCoords.entrySet()) {
			Coord homeCoord = homeCoordEntry.getValue();

			String nearestMeasurementPoint = null;
			double minDist = Double.MAX_VALUE;

			for (Map.Entry<String, Coord> measurementPointsEntry : measurementPointsCoords.entrySet()) {
				Coord measurementPointCoord = measurementPointsEntry.getValue();
				double dist = CoordUtils.calcEuclideanDistance(homeCoord, measurementPointCoord);
				if (dist < minDist) {
					minDist = dist;
					nearestMeasurementPoint = measurementPointsEntry.getKey();
				}
			}

			assert nearestMeasurementPoint != null;

			csvPrinter.printRecord(
				homeCoordEntry.getKey(),
				Double.toString(homeCoordEntry.getValue().getX()),
				Double.toString(homeCoordEntry.getValue().getY()),
				nearestMeasurementPoint,
				Double.toString(measurementPointsCoords.get(nearestMeasurementPoint).getX()),
				Double.toString(measurementPointsCoords.get(nearestMeasurementPoint).getY()),
				Double.toString(minDist)
			);
		}

		csvPrinter.close();
	}
}
