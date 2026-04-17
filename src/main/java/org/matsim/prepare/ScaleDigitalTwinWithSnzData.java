package org.matsim.prepare;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;
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

	static final String ALL = "==ALL==";
	private static final String PLZ = "PLZ";
	static final String MOBILE = "mobile";
	private static final String HOME_X = "home_x";
	private static final String HOME_Y = "home_y";
	private static final String HOME = "home";
	private static final String N_MOBILE_PERSONS = "nMobilePersons";
	private static final String N_PERSONS = "nPersons";
	private static final String ZIP_CODE = "zipCode";
	private static final String SUBPOPULATION_PERSON = "person";

	public static void main(String[] args) {
		new ScaleDigitalTwinWithSnzData().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		Scenario scenario = loadScenario();
		double originalOutOfHomeRate = dropNonMobilePersonAgentsAndCalcOutOfHomeRate(scenario);
		Map<String, Double> mobilityRate = loadPersonStatsPerPLZ(mobilityPersonStats, originalOutOfHomeRate);
		scaleNonPersonAgents(scenario, originalOutOfHomeRate);
		applyOutOfHomeRateForPersonAgents(mobilityRate, scenario);
		dumpResults(scenario, originalOutOfHomeRate);
		return 0;
	}

	/**
	 * use the originalOutOfHomeRate to scale all non person-agents
	 *
	 * @param scenario
	 * @param originalOutOfHomeRate
	 */
	private void scaleNonPersonAgents(Scenario scenario, double originalOutOfHomeRate) {
		Collection<? extends Person> notPersonAgents = scenario.getPopulation().getPersons().values().parallelStream()
				.filter(p -> !PopulationUtils.getSubpopulation(p).equals(SUBPOPULATION_PERSON)).collect(Collectors.toSet());
		
		Random rng = MatsimRandom.getLocalInstance();
		notPersonAgents.stream().filter(p -> rng.nextDouble() > originalOutOfHomeRate).forEach(p -> scenario.getPopulation().removePerson(p.getId()));
	}

	/**
	 * drop all non mobile person-agents and return the original mobility-rate
	 *
	 * @param scenario
	 * @return the original outOfHomeRate
	 */
	private double dropNonMobilePersonAgentsAndCalcOutOfHomeRate(Scenario scenario) {
		Collection<? extends Person> persons = scenario.getPopulation().getPersons().values().parallelStream()
			.filter(p -> PopulationUtils.getSubpopulation(p).equals(SUBPOPULATION_PERSON)).collect(Collectors.toSet());

		Set<? extends Person> stayHomePersons = persons.parallelStream()
			.filter(p -> p.getSelectedPlan().getPlanElements().size() == 1).collect(Collectors.toSet());

		stayHomePersons.forEach(p -> scenario.getPopulation().removePerson(p.getId()));

		double outOfHomeRate = 1 - 1. * stayHomePersons.size() / persons.size();
		log.info("original out of home rate of person-agents: {}", outOfHomeRate);
		return outOfHomeRate;
	}

	/**
	 * @param mobilityRateMap
	 * @param scenario
	 */
	private void applyOutOfHomeRateForPersonAgents(Map<String, Double> mobilityRateMap, Scenario scenario) {
		Random rng = MatsimRandom.getLocalInstance();
		Frequency stats = new Frequency();
		Collection<? extends Person> persons = scenario.getPopulation().getPersons().values().parallelStream()
			.filter(p -> PopulationUtils.getSubpopulation(p).equals(SUBPOPULATION_PERSON)).collect(Collectors.toSet());
		for (Person person : persons) {
			boolean mobile = true;
			
			String plz = person.getAttributes().getAsMap().getOrDefault(PLZ, ALL).toString();
			double mobilityRate = mobilityRateMap.get(plz);
			
			if (rng.nextDouble() > mobilityRate) {
				mobile = false;
				Activity firstActOrHome = getHome(person);
				person.getPlans().clear();
				Plan plan = PopulationUtils.createPlan(person);
				plan.addActivity(firstActOrHome);
				person.addPlan(plan);
				person.setSelectedPlan(plan);
			}
			stats.addValue(Boolean.toString(mobile));
			person.getAttributes().putAttribute(MOBILE, mobile);
		}
		log.info("stats:\t" + stats.toString());
	}

	/**
	 * @param person
	 * @return
	 */
	private Activity getHome(Person person) {
		Object homeX = person.getAttributes().getAttribute(HOME_X);
		Object homeY = person.getAttributes().getAttribute(HOME_Y);
		if (homeX != null && homeY != null) {
			return PopulationUtils.createActivityFromCoord(HOME, new Coord((Double) homeX, (Double) homeY));
		} else {
			throw new RuntimeException("found a person without home-coord. This must not happen.");
		}
	}

	/**
	 * 
	 * @param scenario
	 * @param originalOutOfHomeRate
	 */
	private void dumpResults(Scenario scenario, double originalOutOfHomeRate) {
		String outputplans = new File(outputpath, POPULATIONFILE).getAbsolutePath();
		new PopulationWriter(scenario.getPopulation()).write(outputplans);

		scenario.getConfig().plans().setInputFile(outputplans);
		String outputconfig = new File(outputpath, CONFIG).getAbsolutePath();
		
		scenario.getConfig().qsim().setFlowCapFactor(scenario.getConfig().qsim().getFlowCapFactor() * originalOutOfHomeRate);
		scenario.getConfig().qsim().setStorageCapFactor(scenario.getConfig().qsim().getStorageCapFactor() * originalOutOfHomeRate);
		scenario.getConfig().counts().setCountsScaleFactor(scenario.getConfig().counts().getCountsScaleFactor() / originalOutOfHomeRate);
		
		new ConfigWriter(scenario.getConfig()).write(outputconfig);
	}

	private Scenario loadScenario() {
		Config config = ConfigUtils.loadConfig(inputconfig);
		Scenario scenario = ScenarioUtils.loadScenario(config);
		return scenario;
	}

	/**
	 * load the mobility-rate from file
	 *
	 * @param personStatsFile
	 * @return
	 */
	static Map<String, Double> loadPersonStatsPerPLZ(String personStatsFile, double defaultMobilityRate) {
		try {
			Map<String, Double> mobilityRate = new HashMap<String, Double>();

			AtomicDouble nPersonsTotal = new AtomicDouble(0.);
			AtomicDouble nMobilePersonsTotal = new AtomicDouble(0.);
			if (personStatsFile != null) {
				log.info("parsing personstats from: " + personStatsFile);
				CSVParser records = CSVFormat.Builder.create().setAllowMissingColumnNames(true).setDelimiter(',').setSkipHeaderRecord(true).setHeader().get().parse(IOUtils.getBufferedReader(personStatsFile));
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
			log.info("default mobilityRate is: " + defaultMobilityRate);
			mobilityRate.put(ALL, defaultMobilityRate);
			return mobilityRate;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

}
