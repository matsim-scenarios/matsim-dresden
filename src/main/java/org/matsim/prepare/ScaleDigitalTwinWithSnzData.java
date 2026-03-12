package org.matsim.prepare;

import java.io.File;
import java.util.Map;
import java.util.Random;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PopulationWriter;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;

import picocli.CommandLine;

@CommandLine.Command(name = "ScaleDigitalTwinWithSnzData", description = "read a configuration, read the population, let random number of persons stay at home, dump pop and config.")
public class ScaleDigitalTwinWithSnzData implements MATSimAppCommand {

	@CommandLine.Option(names = "--inputconfig", description = "path to the input-config", required = true)
	private String inputconfig;

	@CommandLine.Option(names = "--outputpath", description = "outputpath", required = true)
	private String outputpath;

	private static final String CONFIG = "config.xml";
	private static final String POPULATIONFILE = "population.xml.gz";

	private static final String ALL = "ALL";
	private static final String PLZ = "PLZ";
	private static final String MOBILE = "mobile";
	private static final String HOME_X = "home_x";
	private static final String HOME_Y = "home_y";
	private static final String HOME = "home";

	@Override
	public Integer call() throws Exception {
		String senozonDataFile = null;
		Map<String, Double> mobilityRate = loadMobilityRatePerPLZ(
				senozonDataFile);

		Scenario scenario = loadScenario();
		applyMobilityRate(mobilityRate, scenario);

		dumpResults(scenario);

		return 0;
	}

	/**
	 * @param mobilityRateMap
	 * @param scenario
	 */
	private void applyMobilityRate(Map<String, Double> mobilityRateMap,
			Scenario scenario) {
		Random rng = MatsimRandom.getLocalInstance(System.currentTimeMillis());
		for (Person person : scenario.getPopulation().getPersons().values()) {
			boolean mobile = false;
			if (person.getSelectedPlan().getPlanElements().size() > 1) {
				mobile = true;
				String plz = person.getAttributes().getAsMap()
						.getOrDefault(PLZ, ALL).toString();
				double mobilityRate = mobilityRateMap.get(plz);
				if (rng.nextDouble() > mobilityRate) {
					mobile = false;
					Activity firstActOrHome = getFirstActOrHome(person);
					person.getPlans().clear();
					Plan plan = PopulationUtils.createPlan(person);
					plan.addActivity(firstActOrHome);
					person.addPlan(plan);
					person.setSelectedPlan(plan);
				}
			}
			person.getAttributes().putAttribute(MOBILE, mobile);
		}
	}

	/**
	 * @param person
	 * @return
	 */
	private Activity getFirstActOrHome(Person person) {
		Object homeX = person.getAttributes().getAttribute(HOME_X);
		Object homeY = person.getAttributes().getAttribute(HOME_Y);
		if (homeX != null && homeY != null) {
			return PopulationUtils.createActivityFromCoord(HOME,
					new Coord((Double) homeX, (Double) homeY));
		} else {
			return (Activity) person.getSelectedPlan().getPlanElements().get(0);
		}
	}

	private void dumpResults(Scenario scenario) {
		String outputplans = new File(outputpath, POPULATIONFILE)
				.getAbsolutePath();
		new PopulationWriter(scenario.getPopulation()).write(outputplans);

		scenario.getConfig().plans().setInputFile(outputplans);
		String outputconfig = new File(outputpath, CONFIG).getAbsolutePath();
		new ConfigWriter(scenario.getConfig()).write(outputconfig);
	}

	private Scenario loadScenario() {
		Config config = ConfigUtils.loadConfig(inputconfig);
		Scenario scenario = ScenarioUtils.loadScenario(config);
		return scenario;
	}

	private Map<String, Double> loadMobilityRatePerPLZ(
			String mobilityRateFile) {
		Random rng = MatsimRandom.getLocalInstance(System.currentTimeMillis());
		double defaultMobilityRate = 1 - (0.2 * rng.nextDouble());
		Map<String, Double> mobilityRate = Map.of(ALL, defaultMobilityRate);
		return mobilityRate;
	}

}
