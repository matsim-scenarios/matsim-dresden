package org.matsim.analysis;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.population.PopulationUtils;
import picocli.CommandLine;

import java.io.FileWriter;
import java.util.List;

@CommandLine.Command(
	name = "check-summarize-freight",
	description = "Check long distance freight population for malfunctions and summarize if no malfunctions in population."
)
public class CheckAndSummarizeLongDistanceFreightPopulation implements MATSimAppCommand {
	Logger log = LogManager.getLogger(CheckAndSummarizeLongDistanceFreightPopulation.class);

	@CommandLine.Parameters(arity = "1", paramLabel = "INPUT", description = "Path to input population")
	private String input;


	public static void main(String[] args) {
		new CheckAndSummarizeLongDistanceFreightPopulation().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		Population population = PopulationUtils.readPopulation(input);

		int plansCount = 0;
		int planElementsCount = 0;
		int actCount = 0;
		int legCount = 0;
		int coordCount = 0;

		for (Person p : population.getPersons().values()) {
//			after long distance freight pop creation, the population should have:
//			1 plan per person
//			plans should have 3 plan elements: act0 - leg - act1
			if (p.getPlans().size() != 1) {
				log.info("Person {} has {} plans, but should only have one.", p.getId(), p.getPlans().size());
				plansCount++;
			}

			Plan plan = p.getPlans().getFirst();

			if (plan.getPlanElements().size() != 3) {
				log.info("Plan of person {} has {} plan elements, but should have exactly 3.",
					p.getId(), plan.getPlanElements().size());
				planElementsCount++;
			}

			PlanElement act0 = plan.getPlanElements().getFirst();
			PlanElement leg = plan.getPlanElements().get(1);
			PlanElement act1 = plan.getPlanElements().get(2);

			for (PlanElement el : List.of(act0, act1)) {
				if (!(el instanceof Activity act)) {
					log.info("Plan element at index {} for person {} should be of type activity, but it is not.",
						plan.getPlanElements().indexOf(el), p.getId());
					actCount++;
				} else {
					if (act.getCoord() == null) {
						log.info("Activity at plan element list index {} for person {} does not have a coordinate, but should have one.",
							plan.getPlanElements().indexOf(el), p.getId());
						coordCount++;
					}
				}
			}

			if (!(leg instanceof Leg)) {
				log.info("Plan element at index {} for person {} should be of type leg, but it is not.",
					plan.getPlanElements().indexOf(leg), p.getId());
				legCount++;
			}
		}

		log.info("{} persons out of {} possess != 1 plan(s).", plansCount, population.getPersons().size());
		log.info("{} plans have a number of plan elements != 3.", planElementsCount);
		log.info("{} plan elements should be of type activity, but are not.", actCount);
		log.info("{} plan elements should be of type leg, but are not.", legCount);
		log.info("{} activities do not have a coordinate assigned.", coordCount);

		if (planElementsCount == 0 && actCount == 0 && legCount == 0 && coordCount == 0) {
			String freightTripTsvPath = input.replace(".gz", "").replace(".xml", "")
				+ "-locations-summary.tsv";

//		the following is copied from class ExtractRelevantFreightTrips because the method with below code has private access in the class.
//		we need to create the following tsv in post-processing due to errors in ExtractRelevantFreightTrips,
//		which create activities for transit trips without coords. Should be fixed there. RE is looking at it. -sm1025
			try (CSVPrinter tsvWriter = new CSVPrinter(new FileWriter(freightTripTsvPath), CSVFormat.TDF)) {
				tsvWriter.printRecord("trip_id", "from_x", "from_y", "to_x", "to_y");
				for (Person person : population.getPersons().values()) {
					List<PlanElement> planElements = person.getSelectedPlan().getPlanElements();
					Activity act0 = (Activity) planElements.get(0);
					Activity act1 = (Activity) planElements.get(2);
					Coord fromCoord = act0.getCoord();
					Coord toCoord = act1.getCoord();
					tsvWriter.printRecord(person.getId().toString(), fromCoord.getX(), fromCoord.getY(), toCoord.getX(), toCoord.getY());
				}
				log.info("Summary of long distance freight trips written to {}", freightTripTsvPath);
			}
		}

		return 0;
	}
}
