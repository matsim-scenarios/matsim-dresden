package org.matsim.prepare;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.PopulationWriter;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;

public class CleanPopulation {
	public static void main(String[] args) {
		Config config = ConfigUtils.createConfig();
		config.global().setCoordinateSystem("EPSG:25832");
		config.plans().setInputFile("/Users/luchengqi/Desktop/012.output_plans.xml.gz");
		Scenario scenario = ScenarioUtils.loadScenario(config);
		PopulationUtils.cleanPopulation(scenario);
		new PopulationWriter(scenario.getPopulation()).write("/Users/luchengqi/Desktop/012.output_plans-cleaned.xml.gz");
	}
}
