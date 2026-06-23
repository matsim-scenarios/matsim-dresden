package org.matsim.analysis.population;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.utils.geometry.geotools.MGC;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PopulationAnalysis {
	private static final Logger log = LogManager.getLogger(PopulationAnalysis.class);

	public static void main(String[] args) throws IOException {
		String inputPlanPath = args.length > 0 ? args[0] : "/Users/luchengqi/Documents/MATSimScenarios/Dresden/dresden-scenario/v1.1/dresden-v1.1-10pct.plans-initial.xml.gz";
		String output = args.length > 1 ? args[1] : "/Users/luchengqi/Documents/MATSimScenarios/Dresden/dresden-scenario/v1.1/population-analysis.tsv";

		Population population = PopulationUtils.readPopulation(inputPlanPath);

		ShpOptions shp = new ShpOptions("input/v1.0/vvo_tarifzone_10_dresden/v1.0_vvo_tarifzone_10_dresden_utm32n.shp", "EPSG:25832", null);
		Geometry studyArea = shp.getGeometry("EPSG:25832");

		log.info("Identifying relevant persons...");
		List<Person> relevantPersons = new ArrayList<>();
		for (Person person : population.getPersons().values()) {
			if (!person.getAttributes().getAttribute("subpopulation").toString().equals("person")) {
				// not a normal person (e.g., freight person, commercial traffic person...)
				continue;
			}
			// identify home location
			Coord homeCoord = null;
			// try to get home location from the attribute
			String homeXString = person.getAttributes().getAttribute("home_x").toString();
			String homeYString = person.getAttributes().getAttribute("home_y").toString();
			if (homeXString != null && homeYString != null) {
				homeCoord = new Coord(Double.parseDouble(homeXString), Double.parseDouble(homeYString));
			} else {
				// otherwise, use first home activity as home location
				for (PlanElement planElement : person.getSelectedPlan().getPlanElements()) {
					if (planElement instanceof Activity activity) {
						if (activity.getType().contains("home")) {
							homeCoord = activity.getCoord();
							person.getAttributes().putAttribute("home_x", homeCoord.getX());
							person.getAttributes().putAttribute("home_y", homeCoord.getY());
							break;
						}
					}
				}
			}

			if (homeCoord == null) {
				// person does not have home location or home activity
				continue;
			}

			if (!MGC.coord2Point(homeCoord).within(studyArea)) {
				// person living outside the study area
				continue;
			}

			relevantPersons.add(person);
		}


		log.info("There are {} relevant persons", relevantPersons.size());
		log.info("Analyzing relevant persons...");
		CSVPrinter csvPrinter = new CSVPrinter(new FileWriter(output), CSVFormat.DEFAULT);
		csvPrinter.printRecord("person", "age", "gender", "car_availability", "home_x", "home_y", "home_type", "num_activities", "activity_chain");
		for (Person person : relevantPersons) {
			// collect the attributes of the relevant persons

			List<String> activities = new ArrayList<>();
			for (PlanElement planElement : person.getSelectedPlan().getPlanElements()) {
				if (planElement instanceof Activity activity) {
					activities.add(activity.getType());
				}
			}
			int numActivities = activities.size();
			activities.replaceAll(s -> {
				int idx = s.lastIndexOf('_');
				return idx >= 0 ? s.substring(0, idx) : s;
			});
			String activityChain = String.join("-", activities);

			csvPrinter.printRecord(
				person.getId().toString(),
				PersonUtils.getAge(person),
				PersonUtils.getSex(person),
				PersonUtils.getCarAvail(person),
				person.getAttributes().getAttribute("home_x"),
				person.getAttributes().getAttribute("home_y"),
				"todo",
				numActivities,
				activityChain
			);
		}
		csvPrinter.close();
	}
}
