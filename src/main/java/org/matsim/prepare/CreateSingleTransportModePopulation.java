package org.matsim.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.prepare.population.CleanPopulation;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.algorithms.TripsToLegsAlgorithm;
import org.matsim.core.router.RoutingModeMainModeIdentifier;
import picocli.CommandLine;

@CommandLine.Command(
	name = "single-mode-population",
	description = "Create a population file, where every leg has the same mode. This is typically used as a starting point for ASC calibration."
)
public class CreateSingleTransportModePopulation implements MATSimAppCommand {
	private static final Logger log = LogManager.getLogger(CreateSingleTransportModePopulation.class);

	@CommandLine.Parameters(arity = "1", paramLabel = "INPUT", description = "Path to input population")
	private String input;
	@CommandLine.Option(names = "--transport-mode", description = "Transport mode to which all legs should be changed.", defaultValue = TransportMode.car)
	private String mode;
	@CommandLine.Option(names = "--output", description = "Path to output population.", required = true)
	private String output;

	public static void main(final String[] args) {
		new CreateSingleTransportModePopulation().execute(args);
	}


	@Override
	public Integer call() {
		Population population = PopulationUtils.readPopulation(input);

		convertTripsToSingleModeLegs(population, mode);

		PopulationUtils.writePopulation(population, output);
		logMessage(mode);
		log.info("Output population written to: {}", output);

		return 0;
	}

	private static void convertTripsToSingleModeLegs(Population population, String mode) {
		TripsToLegsAlgorithm trips2Legs = new TripsToLegsAlgorithm(new RoutingModeMainModeIdentifier());

		for (Person person : population.getPersons().values()) {
			if (!person.getAttributes().getAttribute("subpopulation").equals("person")) {
				continue;
			}

			for (Plan plan : person.getPlans()) {
//				transform all trips to single legs
				trips2Legs.run(plan);

				for (PlanElement el : plan.getPlanElements()) {
					if (el instanceof Leg leg) {
						CleanPopulation.removeRouteFromLeg(el);
						leg.setMode(mode);
					}
				}
			}
		}
		logMessage(mode);
	}

	private static void logMessage(String mode) {
		log.info("For all agents with subpopulation=person: Unselected plans have been removed. Trips were converted to legs. Routes have been removed." +
			" For every leg, the mode was changed to {}", mode);
	}
}
