package org.matsim.prepare;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
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
import picocli.CommandLine;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@CommandLine.Command(name = "ScaleDigitalTwinWithSnzData", description = "read a configuration, read the population, read personStats per plz, adjust mobilityRate.")
public class ScaleDigitalTwinWithSnzData implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(ScaleDigitalTwinWithSnzData.class);

	@CommandLine.Option(names = "--inputconfig", description = "path to the input-config", required = true)
	private String inputconfig;

	@CommandLine.Option(names = "--outputpath", description = "outputpath", required = true)
	private String outputpath;

	@CommandLine.Option(names = "--personstatsReference", description = "the snz personstats-file for the reference day", required = true)
	private String refMobilityPersonStats;

	@CommandLine.Option(names = "--personstats", description = "the snz personstats-file for the actual day", required = true)
	private String mobilityPersonStats;

	private static final String CONFIG = "config.xml";
	static final String POPULATIONFILE = "population.xml.gz";

	static final String GLOBAL = "==GLOBAL==";
	private static final String PLZ = "PLZ";
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
		Config config = ConfigUtils.loadConfig(inputconfig);
		Scenario scenario = ScenarioUtils.loadScenario(config);

		/*
		 * We assume, that the mobile persons depict our full population. For that we drop all stay-home-agents
		 * and calc the outOfHome-rates (OOH) per PLZ.
		 *
		 * We use the global OOH to scale our model, i.e. we use a 10% model initally and have a OOH of 75%,
		 * we assume our resulting model has a scalingFactor of S = 0.1 * 0.75 = 0.075
		 */
		Map<String, Double> modelOutOfHomeRates = dropNonMobilePersonAgentsAndCalcOutOfHomeRate(scenario);

		Map<String, Double> refOutOfHomeRate = loadOohStatsPerPLZ(refMobilityPersonStats);
		Map<String, Double> actualOutOfHomeRate = loadOohStatsPerPLZ(mobilityPersonStats);

		/*
		 * We calc outOfHomeRates from the mobile-phone-data for the actual day OOH_a and the reference-day OOH_r.
		 * We use this to calc the change of OOH and calc our new OOH-rate OOH' , i.e. OOH' = OOH * (OOH_a / OOH_r).
		 */
		double globalOutOfHomeRate = modelOutOfHomeRates.get(GLOBAL);
		applyOutOfHomeRateChangeForPersonAgents(refOutOfHomeRate, actualOutOfHomeRate, modelOutOfHomeRates, scenario);
		scaleNonPersonAgents(scenario, globalOutOfHomeRate);
		dumpResults(scenario, globalOutOfHomeRate);
		return 0;
	}

	private void scaleNonPersonAgents(Scenario scenario, double originalOutOfHomeRate) {
		Random rng = MatsimRandom.getLocalInstance(20260423);

		// we use the original ooh-rate to scale the other parts of the populations
		Map<String, List<Person>> nonPersonAgentsBySubpopulation = scenario.getPopulation().getPersons().values().stream()
				.filter(p -> !PopulationUtils.getSubpopulation(p).equals(SUBPOPULATION_PERSON))
				.collect(Collectors.groupingBy(PopulationUtils::getSubpopulation));

		for (Map.Entry<String, List<Person>> entry : nonPersonAgentsBySubpopulation.entrySet()) {
			String plz = entry.getKey();
			List<Person> subpopPersons = entry.getValue();
			Collections.shuffle(subpopPersons, rng);

			int numberOfStayHomePersons = (int) Math.round((1 - originalOutOfHomeRate) * subpopPersons.size());

			for (int i = 0; i < numberOfStayHomePersons; i++) {
				Person person = subpopPersons.get(i);
				scenario.getPopulation().removePerson(person.getId());
			}
		}
	}


	private Map<String, Double> dropNonMobilePersonAgentsAndCalcOutOfHomeRate(Scenario scenario) {
		List<? extends Person> persons = scenario.getPopulation().getPersons().values().stream()
			.filter(p -> PopulationUtils.getSubpopulation(p).equals(SUBPOPULATION_PERSON))
			.toList();

		Map<String, int[]> countsByPlz = new HashMap<>(); // [total, stayHome]
		List<Person> stayHomePersons = new ArrayList<>();
		for (Person person : persons) {
			String plz = person.getAttributes().getAsMap().getOrDefault(PLZ, GLOBAL).toString();
			int[] counts = countsByPlz.computeIfAbsent(plz, k -> new int[2]);
			counts[0]++;
			if (person.getSelectedPlan().getPlanElements().size() == 1) {
				counts[1]++;
				stayHomePersons.add(person);
			}
		}
		stayHomePersons.forEach(p -> scenario.getPopulation().removePerson(p.getId()));

		Map<String, Double> outOfHomeRates = new HashMap<>();
		for (Map.Entry<String, int[]> entry : countsByPlz.entrySet()) {
			int total = entry.getValue()[0];
			int stayHome = entry.getValue()[1];
			outOfHomeRates.put(entry.getKey(), 1 - 1. * stayHome / total);
		}
		double globalRate = 1 - 1. * stayHomePersons.size() / persons.size();
		outOfHomeRates.put(GLOBAL, globalRate);
		log.info("original model out of home rate of person-agents (global): {}", globalRate);
		return outOfHomeRates;
	}

	private void applyOutOfHomeRateChangeForPersonAgents(Map<String, Double> refRateMap, Map<String, Double> curRateMap, Map<String, Double> modelOutOfHomeRates, Scenario scenario) {
		Random rng = MatsimRandom.getLocalInstance(20260423);
		int mobileCount = 0;
		int stayHomeCount = 0;

		Map<String, List<Person>> personsByPlz = new TreeMap<>();
		for (Person person : scenario.getPopulation().getPersons().values()) {
			if (!PopulationUtils.getSubpopulation(person).equals(SUBPOPULATION_PERSON)) continue;
			String plz = person.getAttributes().getAsMap().getOrDefault(PLZ, GLOBAL).toString();
			personsByPlz.computeIfAbsent(plz, k -> new ArrayList<>()).add(person);
		}

		String statsFile = new File(outputpath, "mobility-stats-per-plz.csv.gz").getAbsolutePath();
		try (CSVPrinter printer = new CSVPrinter(IOUtils.getBufferedWriter(statsFile), CSVFormat.DEFAULT)) {
			printer.printRecord("PLZ", "total", "mobile", "stayHome", "expectedRate");

			for (Map.Entry<String, List<Person>> entry : personsByPlz.entrySet()) {
				String plz = entry.getKey();
				double curRate = curRateMap.getOrDefault(plz, curRateMap.get(GLOBAL));
				double refRate = refRateMap.getOrDefault(plz, refRateMap.get(GLOBAL));
				double modelRate = modelOutOfHomeRates.getOrDefault(plz, modelOutOfHomeRates.get(GLOBAL));
				double expectedRate = Math.clamp(modelRate * (curRate / refRate), 0.0, 1.0);

				List<Person> plzPersons = entry.getValue();
				int numberOfStayHomePersons = (int) Math.round((1 - expectedRate) * plzPersons.size());
				Collections.shuffle(plzPersons, rng);

				for (int i = 0; i < numberOfStayHomePersons; i++) {
					Person person = plzPersons.get(i);
					Activity home = getHome(person);
					person.getPlans().clear();
					Plan plan = PopulationUtils.createPlan(person);
					plan.addActivity(home);
					person.addPlan(plan);
					person.setSelectedPlan(plan);
				}
				stayHomeCount += numberOfStayHomePersons;
				mobileCount += plzPersons.size() - numberOfStayHomePersons;
				printer.printRecord(plz, plzPersons.size(), plzPersons.size() - numberOfStayHomePersons, numberOfStayHomePersons, expectedRate);
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		log.info("mobile: {}, stay-home: {}", mobileCount, stayHomeCount);
		log.info("wrote per-PLZ mobility stats to: {}", statsFile);
	}

	private Activity getHome(Person person) {
		Object homeX = person.getAttributes().getAttribute(HOME_X);
		Object homeY = person.getAttributes().getAttribute(HOME_Y);
		if (homeX != null && homeY != null) {
			return PopulationUtils.createActivityFromCoord(HOME, new Coord((Double) homeX, (Double) homeY));
		} else {
			throw new RuntimeException("found a person without home-coord. This must not happen.");
		}
	}

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

	static Map<String, Double> loadOohStatsPerPLZ(String personStatsFile) {
		try {
			Map<String, Double> mobilityRate = new HashMap<>();
			log.info("parsing personstats from: {}", personStatsFile);
			CSVParser records = CSVFormat.Builder.create().setAllowMissingColumnNames(true).setDelimiter(',').setSkipHeaderRecord(true).setHeader().get().parse(IOUtils.getBufferedReader(personStatsFile));

			AtomicInteger nPersonsGlobal = new AtomicInteger(0);
			AtomicInteger nMobilePersonsGlobal = new AtomicInteger(0);
			StreamSupport.stream(records.spliterator(), false).forEach(r -> {
				String plz = r.get(ZIP_CODE);
				int nPersons = Integer.parseInt(r.get(N_PERSONS));
				nPersonsGlobal.addAndGet(nPersons);
				int nMobilePersons = Integer.parseInt(r.get(N_MOBILE_PERSONS));
				nMobilePersonsGlobal.addAndGet(nMobilePersons);
				if ( nPersons > 0 ) {
					mobilityRate.put(plz, 1. * nMobilePersons / nPersons);
				}
			});
			double defaultMobilityRate = nPersonsGlobal.get() > 0 ? (1.* nMobilePersonsGlobal.get() / nPersonsGlobal.get()) : 0;
			mobilityRate.put(GLOBAL, defaultMobilityRate);
			log.info("done parsing personstats from: {}", personStatsFile);
			log.info("default mobilityRate is: {}", defaultMobilityRate);

			return mobilityRate;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

}
