package org.matsim.prepare.digitalTwin;

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
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.io.IOUtils;
import picocli.CommandLine;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

@CommandLine.Command(name = "ScaleDigitalTwinWithSnzData", description = "read a configuration, read the population, read personStats per zipcode, adjust mobilityRate.")
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

	@CommandLine.Option(names = "--date", description = "the date of the processed day", required = true)
	private String date;

	@CommandLine.Option(names = "--experiment-id", description = "the the id of the experiment", required = true)
	private String experimentId;

	public static void main(String[] args) {
		new ScaleDigitalTwinWithSnzData().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		Config config = ConfigUtils.loadConfig(inputconfig);
		Scenario scenario = ScenarioUtils.loadScenario(config);

		String inputDemographicsFile = new File(outputpath, "inputDemographics.csv.gz").getAbsolutePath();
		Map<TreeMap<String, String>, Long> inputDemographics = DigitalTwinUtils.analyzePopulationClusters(scenario.getPopulation(), inputDemographicsFile);
		Map<String, Double> modelOutOfHomeRates = DigitalTwinUtils.dropNonMobilePersonAgentsAndCalcOutOfHomeRate(scenario);

		Map<String, Double> refOutOfHomeRate = loadOohStatsPerZipcode(refMobilityPersonStats);
		Map<String, Double> actualOutOfHomeRate = loadOohStatsPerZipcode(mobilityPersonStats);

		/*
		 * We calc outOfHomeRates from the mobile-phone-data for the actual day OOH_a and the reference-day OOH_r.
		 * We use this to calc the change of OOH and calc our new OOH-rate OOH' , i.e. OOH' = OOH * (OOH_a / OOH_r).
		 */
		double globalOutOfHomeRate = modelOutOfHomeRates.get(DigitalTwinUtils.GLOBAL);
		applyOutOfHomeRateChangeForPersonAgents(refOutOfHomeRate, actualOutOfHomeRate, modelOutOfHomeRates, scenario);
		String outputDemographicsFile = new File(outputpath, "outputDemographics.csv.gz").getAbsolutePath();
		Map<TreeMap<String, String>, Long> outputDemographics = DigitalTwinUtils.analyzePopulationClusters(scenario.getPopulation(), outputDemographicsFile);

		DigitalTwinUtils.scaleNonPersonAgents(scenario, globalOutOfHomeRate);
		DigitalTwinUtils.scaleConfigAndDumpResults(scenario, outputpath,globalOutOfHomeRate);
		return 0;
	}


	private void applyOutOfHomeRateChangeForPersonAgents(Map<String, Double> refRateMap, Map<String, Double> curRateMap, Map<String, Double> modelOutOfHomeRates, Scenario scenario) {
		Random rng = MatsimRandom.getLocalInstance(20260423);
		int mobileCount = 0;
		int stayHomeCount = 0;

		Map<String, List<Person>> personsByZipcode = new TreeMap<>();
		for (Person person : scenario.getPopulation().getPersons().values()) {
			if (!PopulationUtils.getSubpopulation(person).equals(DigitalTwinUtils.SUBPOPULATION_PERSON)) continue;
			String zipcode = person.getAttributes().getAsMap().getOrDefault(DigitalTwinUtils.PLZ, DigitalTwinUtils.GLOBAL).toString();
			personsByZipcode.computeIfAbsent(zipcode, k -> new ArrayList<>()).add(person);
		}

		String statsFile = new File(outputpath, "mobility-stats-per-zipcode.csv.gz").getAbsolutePath();
		try (CSVPrinter printer = new CSVPrinter(IOUtils.getBufferedWriter(statsFile), CSVFormat.DEFAULT)) {
			printer.printRecord("date", "experimentId", "zipCode", "total", "mobile", "stayHome", "expectedOutOfHomeRate", "realizedOutOfHomeRate");

			for (Map.Entry<String, List<Person>> entry : personsByZipcode.entrySet()) {
				String zipcode = entry.getKey();
				double curRate = curRateMap.getOrDefault(zipcode, curRateMap.get(DigitalTwinUtils.GLOBAL));
				double refRate = refRateMap.getOrDefault(zipcode, refRateMap.get(DigitalTwinUtils.GLOBAL));
				double modelRate = modelOutOfHomeRates.getOrDefault(zipcode, modelOutOfHomeRates.get(DigitalTwinUtils.GLOBAL));
				double expectedOutOfHomeRate = Math.clamp(modelRate * (curRate / refRate), 0.0, 1.0);

				List<Person> zipcodePersons = entry.getValue();
				int numberOfStayHomePersons = (int) Math.round((1 - expectedOutOfHomeRate) * zipcodePersons.size());
				int numberOfMobilePersons = zipcodePersons.size() - numberOfStayHomePersons;
				double realizedOutOfHomeRate = 1.0 * numberOfMobilePersons / zipcodePersons.size();
				Collections.shuffle(zipcodePersons, rng);

				for (int i = 0; i < numberOfStayHomePersons; i++) {
					Person person = zipcodePersons.get(i);
					Activity home = getHome(person);
					person.getPlans().clear();
					Plan plan = PopulationUtils.createPlan(person);
					plan.addActivity(home);
					person.addPlan(plan);
					person.setSelectedPlan(plan);
				}
				stayHomeCount += numberOfStayHomePersons;
				mobileCount += numberOfMobilePersons;

				printer.printRecord(date, experimentId, zipcode, zipcodePersons.size(), numberOfMobilePersons, numberOfStayHomePersons, expectedOutOfHomeRate, realizedOutOfHomeRate);
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		log.info("mobile: {}, stay-home: {}", mobileCount, stayHomeCount);
		log.info("wrote per-zipcode mobility stats to: {}", statsFile);
	}

	private Activity getHome(Person person) {
		Object homeX = person.getAttributes().getAttribute(DigitalTwinUtils.HOME_X);
		Object homeY = person.getAttributes().getAttribute(DigitalTwinUtils.HOME_Y);
		if (homeX != null && homeY != null) {
			return PopulationUtils.createActivityFromCoord(DigitalTwinUtils.HOME, new Coord((Double) homeX, (Double) homeY));
		} else {
			throw new RuntimeException("found a person without home-coord. This must not happen.");
		}
	}


	static Map<String, Double> loadOohStatsPerZipcode(String personStatsFile) {
		try {
			Map<String, Double> mobilityRate = new HashMap<>();
			log.info("parsing personstats from: {}", personStatsFile);
			CSVParser records = CSVFormat.Builder.create().setAllowMissingColumnNames(true).setDelimiter(',').setSkipHeaderRecord(true).setHeader().get().parse(IOUtils.getBufferedReader(personStatsFile));

			AtomicInteger nPersonsGlobal = new AtomicInteger(0);
			AtomicInteger nMobilePersonsGlobal = new AtomicInteger(0);
			StreamSupport.stream(records.spliterator(), false).forEach(r -> {
				String zipcode = r.get(DigitalTwinUtils.ZIP_CODE);
				int nPersons = Integer.parseInt(r.get(DigitalTwinUtils.N_PERSONS));
				nPersonsGlobal.addAndGet(nPersons);
				int nMobilePersons = Integer.parseInt(r.get(DigitalTwinUtils.N_MOBILE_PERSONS));
				nMobilePersonsGlobal.addAndGet(nMobilePersons);
				if ( nPersons > 0 ) {
					mobilityRate.put(zipcode, 1. * nMobilePersons / nPersons);
				}
			});
			double defaultMobilityRate = nPersonsGlobal.get() > 0 ? (1.* nMobilePersonsGlobal.get() / nPersonsGlobal.get()) : 0;
			mobilityRate.put(DigitalTwinUtils.GLOBAL, defaultMobilityRate);
			log.info("done parsing personstats from: {}, global out-of-home-rate is {}", personStatsFile, defaultMobilityRate);

			return mobilityRate;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

}
