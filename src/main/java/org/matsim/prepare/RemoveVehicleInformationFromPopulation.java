package org.matsim.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.population.PopulationUtils;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@CommandLine.Command(
	name = "remove-vehicles",
	description = "Remove person attributes for vehicles and vehicle types from population."
)
public class RemoveVehicleInformationFromPopulation implements MATSimAppCommand {
	private static final Logger log = LogManager.getLogger(RemoveVehicleInformationFromPopulation.class);

	@CommandLine.Parameters(arity = "1", paramLabel = "INPUT", description = "Path to input population")
	private String input;
	@CommandLine.Option(names = "--output", description = "Path to output population", required = true)
	private String output;
	@CommandLine.Option(names = "--skip", description = "List of modes to skip for vehicle type deletion. Separated by ,", split = ",")
	private List<String> modesToSkip = new ArrayList<>();

	public static void main(String[] args) {
		new RemoveVehicleInformationFromPopulation().execute(args);
	}

	@Override
	public Integer call() {
		Population population = PopulationUtils.readPopulation(input);

		int vehicleCount = 0;
		int vehicleTypeCount = 0;

		for (Person person : population.getPersons().values()) {

			Map<String, Id<VehicleType>> types = VehicleUtils.getVehicleTypes(person);

			boolean skipPerson = false;
			if (types != null) {
				for (String mode : modesToSkip) {
					if (types.containsKey(mode)) {
						skipPerson = true;
						break;
					}
				}
			}

			if (types != null && !skipPerson) {
				person.getAttributes().removeAttribute("vehicleTypes");
				vehicleTypeCount++;
			}

//			if attrs are present, delete them from person.
			if (person.getAttributes().getAttribute("vehicles") != null) {
				person.getAttributes().removeAttribute("vehicles");
				vehicleCount++;
			}
		}
		PopulationUtils.writePopulation(population, output);

		log.info("For {} of {} persons the attribute vehicles has been removed.", vehicleCount, population.getPersons().size());
		log.info("For {} of {} persons the attribute vehicleTypes has been removed.", vehicleTypeCount, population.getPersons().size());
		log.info("Output population written to: {}", output);

		return 0;
	}
}
