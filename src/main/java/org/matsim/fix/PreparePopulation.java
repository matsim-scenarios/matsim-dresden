package org.matsim.fix;

import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationWriter;
import org.matsim.application.prepare.population.CleanPopulation;
import org.matsim.core.population.PopulationUtils;

public class PreparePopulation {
	private PreparePopulation() {

	}

	public static void main(String[] args) {
		String output = args.length > 0 ? args[0] : "/Users/luchengqi/Documents/MATSimScenarios/Dresden/dresden-scenario/dresden-v1.0-10pct.without-route.plans.xml.gz";
		CleanPopulation cleanPopulation = new CleanPopulation.CleanPopulationBuilder()
			.setRmRoutes(true)
			.setRmUnselected(true)
			.createCleanPopulation();
		Population plans = PopulationUtils.readPopulation("https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/dresden/dresden-v1.0/input/dresden-v1.0-10pct.plans.xml.gz");
		for (Person person : plans.getPersons().values()) {
			cleanPopulation.run(person);
		}
		new PopulationWriter(plans).write(output);
	}
}
