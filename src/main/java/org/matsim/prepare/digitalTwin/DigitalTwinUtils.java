package org.matsim.prepare.digitalTwin;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationWriter;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.utils.io.IOUtils;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

public final class DigitalTwinUtils {

	static final String GLOBAL = "==GLOBAL==";
	private static final Logger log = LogManager.getLogger(DigitalTwinUtils.class);
	static final String PLZ = "PLZ";
	static final String HOME_X = "home_x";
	static final String HOME_Y = "home_y";
	static final String HOME = "home";
	static final String N_MOBILE_PERSONS = "nMobilePersons";
	static final String N_PERSONS = "nPersons";
	static final String ZIP_CODE = "zipCode";
	static final String SUBPOPULATION_PERSON = "person";
	static final String CONFIG = "config.xml";
	static final String POPULATIONFILE = "population.xml.gz";

	private DigitalTwinUtils() {}


	/**
	 * We assume, that the mobile persons depict our full population. For that we drop all stay-home-agents
	 * and calc the outOfHome-rates (OOH) per PLZ (taken from the attribute PLZ in person-attributes).
	 * <br>
	 * We use the global OOH to scale our model, i.e. we use a 10% model initally and have a OOH of 75%,
	 * we assume our resulting model has a scalingFactor of S = 0.1 * 0.75 = 0.075
	 *
	 * @param scenario
	 * @return
	 */
	static Map<String, Double> dropNonMobilePersonAgentsAndCalcOutOfHomeRate(Scenario scenario) {
		List<? extends Person> persons = scenario.getPopulation().getPersons().values().stream()
			.filter(p -> PopulationUtils.getSubpopulation(p).equals(SUBPOPULATION_PERSON))
			.toList();

		Map<String, int[]> countsByZipcode = new HashMap<>(); // [total, stayHome]
		List<Person> stayHomePersons = new ArrayList<>();
		for (Person person : persons) {
			String zipcode = person.getAttributes().getAsMap().getOrDefault(PLZ, GLOBAL).toString();
			int[] counts = countsByZipcode.computeIfAbsent(zipcode, k -> new int[2]);
			counts[0]++;
			if (person.getSelectedPlan().getPlanElements().size() == 1) {
				counts[1]++;
				stayHomePersons.add(person);
			}
		}
		stayHomePersons.forEach(p -> scenario.getPopulation().removePerson(p.getId()));

		Map<String, Double> outOfHomeRates = new HashMap<>();
		for (Map.Entry<String, int[]> entry : countsByZipcode.entrySet()) {
			int total = entry.getValue()[0];
			int stayHome = entry.getValue()[1];
			outOfHomeRates.put(entry.getKey(), 1 - 1. * stayHome / total);
		}
		double globalRate = 1 - 1. * stayHomePersons.size() / persons.size();
		outOfHomeRates.put(GLOBAL, globalRate);
		log.info("original model out of home rate of person-agents (global): {}", globalRate);
		return outOfHomeRates;
	}

	/**
	 *
	 * @param scenario
	 * @param originalOutOfHomeRate
	 */
	static void scaleNonPersonAgents(Scenario scenario, double originalOutOfHomeRate) {
		Random rng = MatsimRandom.getLocalInstance(20260423);

		// we use the original ooh-rate to scale the other parts of the populations
		Map<String, List<Person>> nonPersonAgentsBySubpopulation = scenario.getPopulation().getPersons().values().stream()
			.filter(p -> !PopulationUtils.getSubpopulation(p).equals(DigitalTwinUtils.SUBPOPULATION_PERSON))
			.collect(Collectors.groupingBy(PopulationUtils::getSubpopulation));

		for (Map.Entry<String, List<Person>> entry : nonPersonAgentsBySubpopulation.entrySet()) {
			String zipcode = entry.getKey();
			List<Person> subpopPersons = entry.getValue();
			Collections.shuffle(subpopPersons, rng);

			int numberOfStayHomePersons = (int) Math.round((1 - originalOutOfHomeRate) * subpopPersons.size());

			for (int i = 0; i < numberOfStayHomePersons; i++) {
				Person person = subpopPersons.get(i);
				scenario.getPopulation().removePerson(person.getId());
			}
		}
	}

	/**
	 *
	 * @param scenario
	 * @param outputpath
	 * @param originalOutOfHomeRate
	 */
	static void scaleConfigAndDumpResults(Scenario scenario, String outputpath, double originalOutOfHomeRate) {
		String outputplans = new File(outputpath, POPULATIONFILE).getAbsolutePath();
		new PopulationWriter(scenario.getPopulation()).write(outputplans);

		scenario.getConfig().plans().setInputFile(outputplans);
		String outputconfig = new File(outputpath, CONFIG).getAbsolutePath();

		scenario.getConfig().qsim().setFlowCapFactor(scenario.getConfig().qsim().getFlowCapFactor() * originalOutOfHomeRate);
		scenario.getConfig().qsim().setStorageCapFactor(scenario.getConfig().qsim().getStorageCapFactor() * originalOutOfHomeRate);
		scenario.getConfig().counts().setCountsScaleFactor(scenario.getConfig().counts().getCountsScaleFactor() / originalOutOfHomeRate);

		new ConfigWriter(scenario.getConfig()).write(outputconfig);
	}


	/**
	 * Groups persons of subpopulation "person" by their attributes (ignoring home_x and home_y)
	 * and returns the count per attribute combination. Also writes the results to a file.
	 */
	public static Map<TreeMap<String, String>, Long> analyzePopulationClusters(Population population, String outputfile) {
		final Set<String> IGNORED_ATTRIBUTES = Set.of("home_x", "home_y", "subpopulation", "vehicles");

		Map<TreeMap<String, String>, Long> clusters = new LinkedHashMap<>();

		for (Person person : population.getPersons().values()) {
			if (!SUBPOPULATION_PERSON.equals(PopulationUtils.getSubpopulation(person))) {
				continue;
			}

			TreeMap<String, String> key = new TreeMap<>();
			for (Map.Entry<String, Object> entry : person.getAttributes().getAsMap().entrySet()) {
				if (!IGNORED_ATTRIBUTES.contains(entry.getKey())) {
					key.put(entry.getKey(), String.valueOf(entry.getValue()));
				}
			}

			clusters.merge(key, 1L, Long::sum);
		}

		log.info("Found {} distinct clusters in subpopulation 'person'.", clusters.size());

		List<String> columns = clusters.keySet().stream().flatMap(k -> k.keySet().stream()).distinct().sorted().toList();

		try (CSVPrinter printer = new CSVPrinter(IOUtils.getBufferedWriter(outputfile), CSVFormat.DEFAULT)) {
			ArrayList<String> header = new ArrayList<>(columns);
			header.add("cnt");
			printer.printRecord(header);

			for (Map.Entry<TreeMap<String, String>, Long> e : clusters.entrySet()) {
				List<String> record = new ArrayList<>();
				for (String k : columns) {
					record.add(String.valueOf(e.getKey().getOrDefault(k, "NULL")));
				}
				record.add(String.valueOf(e.getValue()));
				printer.printRecord(record);
			}

		} catch (Exception e) {
			throw new RuntimeException(e);
		}

		clusters.entrySet().stream()
			.sorted(Map.Entry.<TreeMap<String, String>, Long>comparingByValue().reversed())
			.forEach(e -> log.info("count={} | {}", e.getValue(), e.getKey()));

		return clusters;
	}
}
