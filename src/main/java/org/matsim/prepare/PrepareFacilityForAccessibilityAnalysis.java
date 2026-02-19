package org.matsim.prepare;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.accessibility.osm.OsmKeys;
import org.matsim.contrib.accessibility.osm.OsmPoiReader;
import org.matsim.contrib.accessibility.utils.AccessibilityFacilityUtils;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.facilities.*;

import java.io.FileNotFoundException;
import java.util.Map;

public class PrepareFacilityForAccessibilityAnalysis {
	public static void main(String[] args) {
		// Input and output
		String osmInputFile = "/Users/luchengqi/Documents/MATSimScenarios/Dresden/distortion-study-analysis/accessibility-data/dresden-plus-2km.osm";
		String facilityFile = "/Users/luchengqi/Documents/MATSimScenarios/Dresden/distortion-study-analysis/accessibility-data/dresden-accessibility-facilities.xml";

		// Parameters
		String crs = "EPSG:25832";

		CoordinateTransformation ct = TransformationFactory.getCoordinateTransformation("WGS84", crs);
		OsmPoiReader osmPoiReader = null;
		try {
			osmPoiReader = new OsmPoiReader(osmInputFile, ct);
		} catch (FileNotFoundException e1) {
			e1.printStackTrace();
		}

//		Map<String, String> osmAmenityToMatsimTypeMap = AccessibilityFacilityUtils.buildOsmAmenityToMatsimTypeMapV2FinerClassification();
		Map<String, String> osmAmenityToMatsimTypeMap = AccessibilityFacilityUtils.buildOsmAmenityToMatsimTypeMap();
//		Map<String, String> osmLeisureToMatsimTypeMap = AccessibilityFacilityUtils.buildOsmLeisureToMatsimTypeMapV2FinerClassification();
//		Map<String, String> osmTourismToMatsimTypeMap = AccessibilityFacilityUtils.buildOsmTourismToMatsimTypeMapV2FinerClassification();
//		Map<String, String> osmShopToMatsimTypeMap = AccessibilityFacilityUtils.buildOsmShopToMatsimTypeMapV2FinerClassification();
		Map<String, String> osmShopToMatsimTypeMap = AccessibilityFacilityUtils.buildOsmShopToMatsimTypeMapV2();

		osmPoiReader.parseOsmFileAndAddFacilities(osmAmenityToMatsimTypeMap, OsmKeys.AMENITY);
//		osmPoiReader.parseOsmFileAndAddFacilities(osmLeisureToMatsimTypeMap, OsmKeys.LEISURE);
//		osmPoiReader.parseOsmFileAndAddFacilities(osmTourismToMatsimTypeMap, OsmKeys.TOURISM);
//		osmPoiReader.setUseGeneralTypeIsSpecificTypeUnknown(true);
		osmPoiReader.parseOsmFileAndAddFacilities(osmShopToMatsimTypeMap, OsmKeys.SHOP);
		osmPoiReader.writeFacilities(facilityFile);

		// merge facilities files
		Config config = ConfigUtils.createConfig();
		config.facilities().setInputFile(facilityFile);
		Scenario scenario = ScenarioUtils.loadScenario(config);
		ActivityFacilities facilitiesToAdd = scenario.getActivityFacilities();

		Config config1 = ConfigUtils.createConfig();
		config1.facilities().setInputFile("https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/dresden/dresden-v1.0/input/dresden-v1.0-activity-facilities.xml.gz");
		Scenario scenario1 = ScenarioUtils.loadScenario(config1);
		ActivityFacilities originalFacilities = scenario1.getActivityFacilities();
		ActivityFacilitiesFactory facilityFactory = originalFacilities.getFactory();

		for (ActivityFacility facilityToAdd : facilitiesToAdd.getFacilities().values()) {
			if (!facilityToAdd.getActivityOptions().values().iterator().next().getType().equals("ignore")) {
				// to avoid duplication of the id, we generate a new facility for each accessibility facility, whose id starts with "accessibility_"
				// We then copy all the information to that new facility and add the new facility to the original facility
				ActivityFacility newFacility = facilityFactory.createActivityFacility(Id.create("accessibility_" + facilityToAdd.getId(), ActivityFacility.class), facilityToAdd.getCoord());
				newFacility.getActivityOptions().putAll(facilityToAdd.getActivityOptions());
				originalFacilities.addActivityFacility(newFacility);
			}
		}

		// todo improve the output path
		FacilitiesWriter facilitiesWriter = new FacilitiesWriter(originalFacilities);
		facilitiesWriter.write("/Users/luchengqi/Documents/MATSimScenarios/Dresden/distortion-study-analysis/dresden-facilities-merged.xml.gz");

	}
}
