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
import org.matsim.core.router.DefaultAnalysisMainModeIdentifier;
import org.matsim.core.router.MainModeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.geometry.geotools.MGC;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class PopulationAnalysis {
	private static final Logger log = LogManager.getLogger(PopulationAnalysis.class);

	public static void main(String[] args) throws IOException {
		String inputPlanPath = args.length > 0 ? args[0] : "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/dresden/dresden-v1.1/calibrated-10pct/output/012.output_plans.xml.gz";
		String output = args.length > 1 ? args[1] : "/Users/luchengqi/Documents/MATSimScenarios/Dresden/dresden-scenario/v1.1/population-analysis-new.tsv";
		Random random = new Random(1234);

		Population population = PopulationUtils.readPopulation(inputPlanPath);

		ShpOptions shp = new ShpOptions("input/v1.0/vvo_tarifzone_10_dresden/v1.0_vvo_tarifzone_10_dresden_utm32n.shp", "EPSG:25832", null);
		Geometry studyArea = shp.getGeometry("EPSG:25832");

		ShpOptions ortsteile = new ShpOptions("/Users/luchengqi/Documents/MATSimScenarios/Dresden/dresden-scenario/shp/ortsteile-dresden-only.shp", "EPSG:25832", null);
		ShpOptions.Index ortsteileShpIndex = ortsteile.createIndex("OT_S");

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

		// assign reduced mobility persons based on survey results
		double sum = 0;
		for (Person person : relevantPersons) {
			double age = Double.parseDouble(person.getAttributes().getAttribute("SNZ_age").toString());
			Coord homeCoord = new Coord(Double.parseDouble(person.getAttributes().getAttribute("home_x").toString()),
				Double.parseDouble(person.getAttributes().getAttribute("home_y").toString()));
			String regionId = ortsteileShpIndex.query(homeCoord);
			assert regionId != null;

			double ageFactor = PopulationAnalysisUtils.getAgeFactor(age) / PopulationAnalysisUtils.overall;
			double homeLocationFactor = PopulationAnalysisUtils.getHomeLocationFactor(regionId) / PopulationAnalysisUtils.overall;
			double probabilityOfReducedMobility = ageFactor * homeLocationFactor * PopulationAnalysisUtils.overall;
			person.getAttributes().putAttribute("reduced_mobility_probability", probabilityOfReducedMobility);
			sum += probabilityOfReducedMobility;
		}
		double correctionFactor = relevantPersons.size() * PopulationAnalysisUtils.overall / sum;


		log.info("There are {} relevant persons", relevantPersons.size());
		log.info("Analyzing relevant persons...");
		MainModeIdentifier mainModeIdentifier = new DefaultAnalysisMainModeIdentifier();
		CSVPrinter csvPrinter = new CSVPrinter(new FileWriter(output), CSVFormat.DEFAULT);
		csvPrinter.printRecord("person_id", "age", "gender", "with_reduced_mobility", "SNZ_car_availability", "SNZ_pt_subscription",
			"home_location_type", "household_size", "household_income_group", "income",
			"num_activities", "activity_chain", "num_trips", "num_car_trips", "num_pt_trips", "num_walk_trip", "num_bike_trip", "num_ride_trip",
			"home_x", "home_y");
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
			String activityChain = String.join("---", activities);

			List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());
			int numCarTrips = 0;
			int numPtTrips = 0;
			int numWalkTrips = 0;
			int numBikeTrips = 0;
			int numRideTrips = 0;

			for (TripStructureUtils.Trip trip : trips) {
				String mode = mainModeIdentifier.identifyMainMode(trip.getTripElements());
				switch (mode) {
					case "walk":
						numWalkTrips++;
						break;
					case "bike":
						numBikeTrips++;
						break;
					case "car":
						numCarTrips++;
						break;
					case "pt":
						numPtTrips++;
						break;
					case "ride":
						numRideTrips++;
						break;
				}
			}

			boolean withReducedMobility = false;
			double probabilityOfReducedMobility = Double.parseDouble(person.getAttributes().getAttribute("reduced_mobility_probability").toString()) * correctionFactor;
			if (random.nextDouble() < probabilityOfReducedMobility) {
				withReducedMobility = true;
			}

			csvPrinter.printRecord(
				person.getId().toString(),
				person.getAttributes().getAttribute("SNZ_age"),
				PersonUtils.getSex(person),
				withReducedMobility,
				person.getAttributes().getAttribute("SNZ_carAvailability"),
				person.getAttributes().getAttribute("SNZ_ptTicket"),

				person.getAttributes().getAttribute("SNZ_homeRegioStaR17"),
				person.getAttributes().getAttribute("SNZ_hhSize"),
				person.getAttributes().getAttribute("SNZ_hhIncome"),
				person.getAttributes().getAttribute("income"),

				numActivities,
				activityChain,
				trips.size(),
				numCarTrips,
				numPtTrips,
				numWalkTrips,
				numBikeTrips,
				numRideTrips,

				person.getAttributes().getAttribute("home_x"),
				person.getAttributes().getAttribute("home_y")
			);
		}
		csvPrinter.close();
	}


}
