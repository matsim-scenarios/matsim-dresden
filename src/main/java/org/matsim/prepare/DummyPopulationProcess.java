package org.matsim.prepare;

import java.io.File;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.PopulationWriter;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.scenario.ScenarioUtils;

import picocli.CommandLine;

/**
 * @author d-roeder (vsp)
 */
@CommandLine.Command(
		name = "DummyPopulationProcess",
		description = "read a configuration, read the population, maipulate the config and write an adapted config that pointers to the new population"
)
public class DummyPopulationProcess  implements MATSimAppCommand {


	@CommandLine.Option(names = "--inputconfig", description = "path to the input-config", required = true)
	private String inputconfig;
	
	@CommandLine.Option(names = "--outputpath", description = "outputpath", required = true)
	private String outputpath;
	
	private static final String CONFIG = "config.xml";
	private static String POPULATIONFILE = "population.xml.gz";

	@Override
	public Integer call() throws Exception {
		try {
			Config config = ConfigUtils.loadConfig(inputconfig);
			Scenario scenario = ScenarioUtils.loadScenario(config);
			
			String outputplans =  new File(outputpath, POPULATIONFILE).getAbsolutePath();
			new PopulationWriter(scenario.getPopulation()).write(outputplans);
			
			config.plans().setInputFile(outputplans);
			String outputconfig = new File(outputpath, CONFIG).getAbsolutePath();
			new ConfigWriter(config).write(outputconfig);
		} catch (Exception e) {
			return 1;
		}
		return 0;
	}

}
