package org.matsim.analysis.distortionAnalysisStudy;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.utils.geometry.geotools.MGC;

import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class HomeLocationAnalysis {
	public static void main(String[] args) throws IOException {
		String output = args.length == 1 ? args[0] : "/Users/luchengqi/Documents/MATSimScenarios/Dresden/distortion-study-analysis/analysis/home-locations.tsv";

		ShpOptions shp = new ShpOptions("input/v1.0/vvo_tarifzone_10_dresden/v1.0_vvo_tarifzone_10_dresden_utm32n.shp", "EPSG:25832", null);
		Geometry studyArea = shp.getGeometry("EPSG:25832");
		Population population = PopulationUtils.readPopulation("https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/dresden/dresden-v1.0/input/dresden-v1.0-10pct.plans.xml.gz");
		Map<Id<Person>, Coord> personsLivingInDresden = new HashMap<>();

		for (Person person : population.getPersons().values()) {
			if (!person.getAttributes().getAttribute("subpopulation").toString().equals("person")) {
				// not a normal person (e.g., freight person, commercial traffic person...)
				continue;
			}
			// identify home location
			Coord homeCoord = null;
			for (PlanElement planElement : person.getSelectedPlan().getPlanElements()) {
				if (planElement instanceof Activity activity) {
					if (activity.getType().contains("home")) {
						homeCoord = activity.getCoord();
						break;
					}
				}
			}

			if (homeCoord == null) {
				// person does not have home activity / location
				continue;
			}

			if (!MGC.coord2Point(homeCoord).within(studyArea)) {
				// person living outside the study area
				continue;
			}

			// record the person
			personsLivingInDresden.put(person.getId(), homeCoord);
		}

		// write down the persons, with home coords
		CSVPrinter csvPrinter = new CSVPrinter(new FileWriter(output, false), CSVFormat.TDF);
		csvPrinter.printRecord("person_id", "x", "y");
		for (Map.Entry<Id<Person>, Coord> idCoordEntry : personsLivingInDresden.entrySet()) {
			csvPrinter.printRecord(idCoordEntry.getKey().toString(), Double.toString(idCoordEntry.getValue().getX()), Double.toString(idCoordEntry.getValue().getY()));
		}
		csvPrinter.close();
	}
}
