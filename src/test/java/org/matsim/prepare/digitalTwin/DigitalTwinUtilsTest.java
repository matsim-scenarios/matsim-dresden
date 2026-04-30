package org.matsim.prepare.digitalTwin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.testcases.MatsimTestUtils;

/**
 * @author d-roeder (vsp)
 */
class DigitalTwinUtilsTest {

	@RegisterExtension
	private MatsimTestUtils utils = new MatsimTestUtils();

	@Test
	void testPersonsWithSameAttributesAreGrouped() {
		Population population = emptyPopulation();

		addPerson(population, "p1", "01234", "male", 1000, 2000);
		addPerson(population, "p2", "01234", "male", 9000, 8000); // different home coords → same cluster
		addPerson(population, "p3", "01234", "female", 1000, 2000);

		Map<TreeMap<String, String>, Long> clusters =
			DigitalTwinUtils.analyzePopulationClusters(population, utils.getOutputDirectory() + "clusters.csv");

		assertEquals(2, clusters.size());
		assertEquals(2L, clusters.get(attrs("PLZ", "01234", "sex", "male")));
		assertEquals(1L, clusters.get(attrs("PLZ", "01234", "sex", "female")));
	}

	@Test
	void testHomeCoordinatesAreIgnoredWhenClustering() {
		Population population = emptyPopulation();

		addPerson(population, "p1", "01234", "male", 0, 0);
		addPerson(population, "p2", "01234", "male", 999999, 999999);

		Map<TreeMap<String, String>, Long> clusters =
			DigitalTwinUtils.analyzePopulationClusters(population, utils.getOutputDirectory() + "clusters.csv");

		assertEquals(1, clusters.size());
		assertEquals(2L, clusters.values().iterator().next());
	}

	@Test
	void testNonPersonAgentsAreExcluded() {
		Population population = emptyPopulation();

		addPerson(population, "p1", "01234", "male", 0, 0);
		addFreightAgent(population, "freight1");

		Map<TreeMap<String, String>, Long> clusters =
			DigitalTwinUtils.analyzePopulationClusters(population, utils.getOutputDirectory() + "clusters.csv");

		assertEquals(1, clusters.size());
		assertEquals(1L, clusters.values().iterator().next());
	}

	@Test
	void testEmptyPopulationReturnsEmptyClusters() {
		Population population = emptyPopulation();

		Map<TreeMap<String, String>, Long> clusters =
			DigitalTwinUtils.analyzePopulationClusters(population, utils.getOutputDirectory() + "clusters.csv");

		assertTrue(clusters.isEmpty());
	}

	@Test
	void testCsvIsWrittenCorrectly() throws Exception {
		Population population = emptyPopulation();

		addPerson(population, "p1", "01234", "male", 1000, 2000);
		addPerson(population, "p2", "01234", "male", 3000, 4000);
		addPerson(population, "p3", "01234", "female", 1000, 2000);

		String outputFile = utils.getOutputDirectory() + "clusters.csv";
		DigitalTwinUtils.analyzePopulationClusters(population, outputFile);

		List<String> lines = Files.readAllLines(Path.of(outputFile));
		assertEquals("PLZ,sex,cnt", lines.get(0));
		assertEquals(3, lines.size(), "header + one row per distinct cluster");
		assertTrue(lines.stream().anyMatch(l -> l.startsWith("01234,male,2")));
		assertTrue(lines.stream().anyMatch(l -> l.startsWith("01234,female,1")));
	}

	private static Population emptyPopulation() {
		return ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
	}

	private static TreeMap<String, String> attrs(String... pairs) {
		TreeMap<String, String> map = new TreeMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			map.put(pairs[i], pairs[i + 1]);
		}
		return map;
	}

	private static void addPerson(Population population, String id, String plz, String sex, double homeX, double homeY) {
		PopulationFactory f = population.getFactory();
		Person person = f.createPerson(Id.createPersonId(id));
		PopulationUtils.putSubpopulation(person, "person");
		person.getAttributes().putAttribute("PLZ", plz);
		person.getAttributes().putAttribute("sex", sex);
		person.getAttributes().putAttribute("home_x", homeX);
		person.getAttributes().putAttribute("home_y", homeY);
		Plan plan = f.createPlan();
		plan.addActivity(f.createActivityFromCoord("home", new Coord(homeX, homeY)));
		person.addPlan(plan);
		person.setSelectedPlan(plan);
		population.addPerson(person);
	}

	private static void addFreightAgent(Population population, String id) {
		PopulationFactory f = population.getFactory();
		Person person = f.createPerson(Id.createPersonId(id));
		PopulationUtils.putSubpopulation(person, "freight");
		Plan plan = f.createPlan();
		plan.addActivity(f.createActivityFromCoord("freight", new Coord(0, 0)));
		person.addPlan(plan);
		person.setSelectedPlan(plan);
		population.addPerson(person);
	}
}
