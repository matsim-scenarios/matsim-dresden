package org.matsim.prepare;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.config.groups.FacilitiesConfigGroup;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.core.population.PopulationUtils;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.FacilitiesFromPopulation;
import org.matsim.facilities.FacilitiesUtils;
import org.matsim.facilities.FacilitiesWriter;
import picocli.CommandLine;

import java.util.Set;

@CommandLine.Command(
	name = "facilities",
	description = "Creates activity facilities out of a given plans file."
)
public class CreateFacilitiesFromPopulation implements MATSimAppCommand {

	@CommandLine.Option(names = "--input-population", description = "Input population from which the activity facilities should be generated.", required = true)
	private String inputPopulationPath;
	@CommandLine.Option(names = "--network", description = "Path to network file.", required = true)
	private String networkPath;
	@CommandLine.Option(names = "--output-population", description = "Path to which the population should be written. If not provided, input population is overwritten.")
	private String outputPopulationPath;
	@CommandLine.Option(names = "--output-facilities", description = "Path to which the facilities should be written.", required = true)
	private String outputFacilitiesPath;

	public static void main(String[] args) {
		new CreateFacilitiesFromPopulation().execute(args);
	}

	@Override
	public Integer call() {
//		load network and filter for car only. this also works for freight agents of all types as all freight modes are added whenever a link has car as allowed mode.
		Network fullNetwork = NetworkUtils.readNetwork(networkPath);
		TransportModeNetworkFilter filter = new TransportModeNetworkFilter(fullNetwork);
		Network carOnlyNetwork = NetworkUtils.createNetwork();
		filter.filter(carOnlyNetwork, Set.of(TransportMode.car));

		Population population = PopulationUtils.readPopulation(inputPopulationPath);

		ActivityFacilities facilities = FacilitiesUtils.createActivityFacilities();

		FacilitiesFromPopulation facilitiesFromPopulation = new FacilitiesFromPopulation(facilities);

		facilitiesFromPopulation.setRemoveLinksAndCoordinates(false);
		facilitiesFromPopulation.setAssignLinksToFacilitiesIfMissing(carOnlyNetwork);
//		we create one facility per coord here if I understand this correctly. Would be smarter per link, but the population might not have links assigned yet.
		facilitiesFromPopulation.setFacilitiesSource(FacilitiesConfigGroup.FacilitiesSource.onePerActivityLocationInPlansFile);

		facilitiesFromPopulation.run(population);

//		population might have changed so we write it
		if (outputPopulationPath != null) {
			PopulationUtils.writePopulation(population, outputPopulationPath);
		} else {
			PopulationUtils.writePopulation(population, inputPopulationPath);
		}

//		write facilities
		FacilitiesWriter writer = new FacilitiesWriter(facilities);
		writer.write(outputFacilitiesPath);

		return 0;
	}
}
