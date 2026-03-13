package org.matsim.prepare;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.stream.StreamSupport;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.math3.stat.Frequency;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
import org.matsim.core.utils.io.IOUtils;

import com.google.common.util.concurrent.AtomicDouble;

import picocli.CommandLine;

@CommandLine.Command(name = "ScaleDigitalTwinWithSnzData", description = "read a configuration, read the population, read personStats per plz, adjust mobilityRate.")
public class ScaleDigitalTwinWithSnzData implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(ScaleDigitalTwinWithSnzData.class);

	@CommandLine.Option(names = "--inputconfig", description = "path to the input-config", required = true)
	private String inputconfig;

	@CommandLine.Option(names = "--outputpath", description = "outputpath", required = true)
	private String outputpath;

	@CommandLine.Option(names = "--personstats", description = "the snz personstats-file", required = false)
	private String mobilityPersonStats;

	private static final String CONFIG = "config.xml";
	static final String POPULATIONFILE = "population.xml.gz";

	static final String ALL = "ALL";
	private static final String PLZ = "PLZ";
	static final String MOBILE = "mobile";
	private static final String HOME_X = "home_x";
	private static final String HOME_Y = "home_y";
	private static final String HOME = "home";
	private static final String N_MOBILE_PERSONS = "nMobilePersons";
	private static final String N_PERSONS = "nPersons";
	private static final String ZIP_CODE = "zipCode";

	public static void main(String[] args) {
		new ScaleDigitalTwinWithSnzData().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		Map<String, Double> mobilityRate = loadPersonStatsPerPLZ(mobilityPersonStats);

		Scenario scenario = loadScenario();
		applyMobilityRate(mobilityRate, scenario);

		dumpResults(scenario);

		return 0;
	}

	/**
	 * @param mobilityRateMap
	 * @param scenario
	 */
	private void applyMobilityRate(Map<String, Double> mobilityRateMap, Scenario scenario) {
		Random rng = MatsimRandom.getLocalInstance(System.currentTimeMillis());
		Frequency stats = new Frequency();
		for (Person person : scenario.getPopulation().getPersons().values()) {
			boolean mobile = false;
			if (person.getSelectedPlan().getPlanElements().size() > 1) {
				mobile = true;
				String plz = person.getAttributes().getAsMap().getOrDefault(PLZ, ALL).toString();
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
			stats.addValue(Boolean.toString(mobile));
			person.getAttributes().putAttribute(MOBILE, mobile);
		}
		
		log.info("stats:\t"+stats.toString());
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
		String outputplans = new File(outputpath, POPULATIONFILE).getAbsolutePath();
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

	static Map<String, Double> loadPersonStatsPerPLZ(String personStatsFile) {
		try {
			Map<String, Double> mobilityRate = new HashMap<String, Double>();

			AtomicDouble nPersonsTotal = new AtomicDouble(0.);
			AtomicDouble nMobilePersonsTotal = new AtomicDouble(0.);
			if (personStatsFile != null) {
				log.info("parsing personstats from: " + personStatsFile);
				CSVParser records = CSVFormat.Builder.create().setAllowMissingColumnNames(true)
						.setDelimiter(',').setSkipHeaderRecord(true).setHeader().get()
						.parse(IOUtils.getBufferedReader(personStatsFile));
				StreamSupport.stream(records.spliterator(), false).forEach(r -> {
					String plz = r.get(ZIP_CODE);

					Double nPersons = Double.parseDouble(r.get(N_PERSONS));
					nPersonsTotal.addAndGet(nPersons);

					Double nMobilePersons = Double.parseDouble(r.get(N_MOBILE_PERSONS));
					nMobilePersonsTotal.addAndGet(nMobilePersons);

					// if there are no persons in the plz we assume the
					// mobility-rate is unchanged, i.e. 1
					mobilityRate.put(plz, nPersons > 0 ? (nMobilePersons / nPersons) : 1);
				});
				log.info("done (parsing personstats from: " + personStatsFile + ").");

			}
			double defaultMobilityRate = nPersonsTotal.get() > 0
					? nMobilePersonsTotal.get() / nPersonsTotal.get()
					: 1;
			log.info("default mobilityRate is: " + defaultMobilityRate);
			mobilityRate.put(ALL, defaultMobilityRate);
			return mobilityRate;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

}
