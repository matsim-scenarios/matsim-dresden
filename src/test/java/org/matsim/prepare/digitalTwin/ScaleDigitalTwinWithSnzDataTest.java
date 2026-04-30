package org.matsim.prepare.digitalTwin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.testcases.MatsimTestUtils;

/**
 * @author d-roeder (vsp)
 */
class ScaleDigitalTwinWithSnzDataTest {

	@RegisterExtension
	private MatsimTestUtils utils = new MatsimTestUtils();

	@Test
	void testParseData() {
		String personStats = utils.getClassInputDirectory() + "/testdata.csv";
		Map<String, Double> personStatsPerPLZ = ScaleDigitalTwinWithSnzData.loadOohStatsPerZipcode(personStats);
		assertEquals(0.6905697445972495, personStatsPerPLZ.get("01067"), MatsimTestUtils.EPSILON);
		assertEquals(0.6767335199004975, personStatsPerPLZ.get("01069"), MatsimTestUtils.EPSILON);
		assertEquals(0.6811880666244992, personStatsPerPLZ.get(DigitalTwinUtils.GLOBAL), MatsimTestUtils.EPSILON);
	}

	/**
	 * Non-person agents (freight) are removed proportionally to globalOOH.
	 * 10 mobile + 1 stay-home → globalOOH = 10/11 ≈ 0.91 → some freight agents are removed.
	 */
	@Test
	void testNonPersonAgentsAreScaled() throws Exception {
		String dir = utils.getOutputDirectory();

		Config config = ConfigUtils.createConfig();
		Scenario scenario = ScenarioUtils.createScenario(config);
		Population population = scenario.getPopulation();

		// 10 mobile persons + 1 stay-home → globalOOH = 10/11
		for (int i = 0; i < 10; i++) {
			addMobilePerson(population, "person_" + i, "01234");
		}

		// 1 stay-home person (single-activity plan, will be removed by dropNonMobile)
		addStayHomePerson(population, "stayhome_0", "01234");

		// 30 freight agents
		for (int i = 0; i < 30; i++) {
			addFreightAgent(population, "freight_" + i);
		}

		String plansFile = dir + "plans.xml.gz";
		new PopulationWriter(population).write(plansFile);
		config.plans().setInputFile("plans.xml.gz");
		String configFile = dir + "config.xml";
		new ConfigWriter(config).write(configFile);

		String refStats = dir + "ref-stats.csv";
		Files.writeString(Path.of(refStats), "zipCode,nPersons,nMobilePersons\n01234,100,80\n");
		String actualStats = dir + "actual-stats.csv";
		Files.writeString(Path.of(actualStats), "zipCode,nPersons,nMobilePersons\n01234,100,60\n");

		ScaleDigitalTwinWithSnzData.main(new String[]{
			"--outputpath", dir,
			"--inputconfig", configFile,
			"--personstatsReference", refStats,
			"--personstats", actualStats,
			"--date", "2026-04-27",
			"--experiment-id", "test-non-person"
		});

		Population result = PopulationUtils.readPopulation(new File(dir, ScaleDigitalTwinWithSnzData.POPULATIONFILE).getAbsolutePath());

		// globalOOH = 10/11 → rng.nextDouble() > 10/11 removes ~1/11 of freight agents
		long freightCount = result.getPersons().values().stream()
			.filter(p -> "freight".equals(PopulationUtils.getSubpopulation(p)))
			.count();
		assertEquals(27, freightCount, "some freight agents should be removed with globalOOH < 1.0");

		// stay-home removed → 10 persons remain; modelOOH=10/11, expectedRate=(10/11)*(0.60/0.80)≈0.682 → round(0.318*10)=3
		long personStayHome = result.getPersons().values().stream()
			.filter(p -> "person".equals(PopulationUtils.getSubpopulation(p)))
			.filter(p -> p.getSelectedPlan().getPlanElements().size() == 1)
			.count();
		assertEquals(3, personStayHome);
	}

	/**
	 * Persons whose PLZ is absent from the SNZ files (ref only, actual only, or both) and persons
	 * without any PLZ attribute all fall back to the GLOBAL rate — no NPE, correct counts.
	 *
	 * Population:
	 *   "01234"      – 4 mobile  (in both ref and actual)
	 *   "NOTINREF"   – 4 mobile  (case a: only in actual)
	 *   "NOTINACTUAL"– 4 mobile  (case b: only in ref)
	 *   "NOTINBOTH"  – 4 mobile  (case c: absent from both)
	 *   no PLZ       – 4 mobile  (falls back to GLOBAL)
	 *   "01234"      – 2 stay-home (removed by dropNonMobile)
	 *
	 * ref:    01234=80%, NOTINACTUAL=80%   →  globalRef  = 0.80
	 * actual: 01234=60%, NOTINREF=60%      →  globalAct  = 0.60
	 *
	 * Formula: expectedRate = modelRate × (curRate / refRate)
	 *          stayHome    = round((1 − expectedRate) × groupSize)
	 * Missing PLZ keys fall back to the GLOBAL entry in the respective map.
	 *
	 * Expected stay-home per group after rate change:
	 *   "01234"         modelRate=4/6  curRate=0.60  refRate=0.80  → expectedRate=(4/6)×0.75=0.50  → round(0.50×4)=2
	 *   "NOTINREF"      modelRate=1.0  curRate=0.60  refRate=0.80* → expectedRate=1.0×0.75 =0.75  → round(0.25×4)=1  (*GLOBAL fallback)
	 *   "NOTINACTUAL"   modelRate=1.0  curRate=0.60* refRate=0.80  → expectedRate=1.0×0.75 =0.75  → round(0.25×4)=1  (*GLOBAL fallback)
	 *   "NOTINBOTH"     modelRate=1.0  curRate=0.60* refRate=0.80* → expectedRate=1.0×0.75 =0.75  → round(0.25×4)=1  (*GLOBAL fallback)
	 *   no PLZ (GLOBAL) modelRate=20/22 curRate=0.60* refRate=0.80* → expectedRate≈0.682        → round(0.318×4)=1
	 *   Total stay-home = 2+1+1+1+1 = 6
	 */
	@Test
	void testFallbackForUnknownAndMissingPlz() throws Exception {
		String dir = utils.getOutputDirectory();

		Config config = ConfigUtils.createConfig();
		Scenario scenario = ScenarioUtils.createScenario(config);
		Population population = scenario.getPopulation();

		String[] plzGroups = {"01234", "NOTINREF", "NOTINACTUAL", "NOTINBOTH"};
		for (String plz : plzGroups) {
			for (int i = 0; i < 4; i++) {
			addMobilePerson(population, plz + "_mobile_" + i, plz);
			}
		}

		// 4 mobile persons without PLZ attribute
		for (int i = 0; i < 4; i++) {
			addMobilePerson(population, "noplz_mobile_" + i, null);
		}

		// 2 stay-home persons (will be removed by dropNonMobile)
		for (int i = 0; i < 2; i++) {
			addStayHomePerson(population, "stayhome_" + i, "01234");
		}

		String plansFile = dir + "plans.xml.gz";
		new PopulationWriter(population).write(plansFile);
		config.plans().setInputFile("plans.xml.gz");
		String configFile = dir + "config.xml";
		new ConfigWriter(config).write(configFile);

		// ref: 01234 + NOTINACTUAL present; actual: 01234 + NOTINREF present
		String refStats = dir + "ref-stats.csv";
		Files.writeString(Path.of(refStats), "zipCode,nPersons,nMobilePersons\n01234,100,80\nNOTINACTUAL,100,80\n");
		String actualStats = dir + "actual-stats.csv";
		Files.writeString(Path.of(actualStats), "zipCode,nPersons,nMobilePersons\n01234,100,60\nNOTINREF,100,60\n");

		ScaleDigitalTwinWithSnzData.main(new String[]{
			"--outputpath", dir,
			"--inputconfig", configFile,
			"--personstatsReference", refStats,
			"--personstats", actualStats,
			"--date", "2026-04-27",
			"--experiment-id", "test-fallback"
		});

		Population result = PopulationUtils.readPopulation(new File(dir, ScaleDigitalTwinWithSnzData.POPULATIONFILE).getAbsolutePath());

		// 22 total − 2 stay-home removed = 20 remain
		assertEquals(20, result.getPersons().size());

		// total stay-home = 2 + 1 + 1 + 1 + 1 = 6 (see class javadoc)
		long stayHomeCount = result.getPersons().values().stream()
			.filter(p -> p.getSelectedPlan().getPlanElements().size() == 1)
			.count();
		assertEquals(6, stayHomeCount);
	}

	/**
	 * Full pipeline test:
	 * - 10 mobile persons (multi-activity plan) + 2 original stay-home (single-activity), all PLZ "01234"
	 * - reference OOH = 80%, actual OOH = 60%
	 * - modelOOH = 10/12  →  expectedRate = (10/12) * (0.60/0.80) = 0.625
	 * - numberOfStayHome = round((1 - 0.625) * 10) = round(3.75) = 4
	 */
	@Test
	void testFullPipeline() throws Exception {
		String dir = utils.getOutputDirectory();

		Config config = ConfigUtils.createConfig();
		Scenario scenario = ScenarioUtils.createScenario(config);
		Population population = scenario.getPopulation();

		for (int i = 0; i < 10; i++) {
			addMobilePerson(population, "mobile_" + i, "01234");
		}

		for (int i = 0; i < 2; i++) {
			addStayHomePerson(population, "stayhome_" + i, "01234");
		}

		String plansFile = dir + "plans.xml.gz";
		new PopulationWriter(population).write(plansFile);
		config.plans().setInputFile("plans.xml.gz");
		String configFile = dir + "config.xml";
		new ConfigWriter(config).write(configFile);

		// SNZ CSV files
		String refStats = dir + "ref-stats.csv";
		Files.writeString(Path.of(refStats), "zipCode,nPersons,nMobilePersons\n01234,100,80\n");

		String actualStats = dir + "actual-stats.csv";
		Files.writeString(Path.of(actualStats), "zipCode,nPersons,nMobilePersons\n01234,100,60\n");

		// run
		ScaleDigitalTwinWithSnzData.main(new String[]{
			"--outputpath", dir,
			"--inputconfig", configFile,
			"--personstatsReference", refStats,
			"--personstats", actualStats,
			"--date", "2026-04-27",
			"--experiment-id", "test-full"
		});

		// 2 original stay-home removed → 10 remain
		Population result = PopulationUtils.readPopulation(new File(dir, ScaleDigitalTwinWithSnzData.POPULATIONFILE).getAbsolutePath());
		assertEquals(10, result.getPersons().size());

		// 4 persons made stay-home by rate change
		long stayHome = result.getPersons().values().stream()
			.filter(p -> p.getSelectedPlan().getPlanElements().size() == 1)
			.count();
		assertEquals(4, stayHome);

		assertTrue(new File(dir, "mobility-stats-per-zipcode.csv.gz").exists());
	}

	private static void addMobilePerson(Population population, String id, String plz) {
		PopulationFactory f = population.getFactory();
		Person person = f.createPerson(Id.createPersonId(id));
		PopulationUtils.putSubpopulation(person, "person");
		if (plz != null) person.getAttributes().putAttribute("PLZ", plz);
		person.getAttributes().putAttribute("home_x", 1000.0);
		person.getAttributes().putAttribute("home_y", 2000.0);
		Plan plan = f.createPlan();
		Activity home = f.createActivityFromCoord("home", new Coord(1000, 2000));
		home.setEndTime(8 * 3600);
		plan.addActivity(home);
		plan.addLeg(f.createLeg("car"));
		plan.addActivity(f.createActivityFromCoord("work", new Coord(5000, 5000)));
		plan.addLeg(f.createLeg("car"));
		plan.addActivity(f.createActivityFromCoord("home", new Coord(1000, 2000)));
		person.addPlan(plan);
		person.setSelectedPlan(plan);
		population.addPerson(person);
	}

	private static void addStayHomePerson(Population population, String id, String plz) {
		PopulationFactory f = population.getFactory();
		Person person = f.createPerson(Id.createPersonId(id));
		PopulationUtils.putSubpopulation(person, "person");
		if (plz != null) person.getAttributes().putAttribute("PLZ", plz);
		person.getAttributes().putAttribute("home_x", 1000.0);
		person.getAttributes().putAttribute("home_y", 2000.0);
		Plan plan = f.createPlan();
		plan.addActivity(f.createActivityFromCoord("home", new Coord(1000, 2000)));
		person.addPlan(plan);
		person.setSelectedPlan(plan);
		population.addPerson(person);
	}

	private static void addFreightAgent(Population population, String id) {
		PopulationFactory f = population.getFactory();
		Person freight = f.createPerson(Id.createPersonId(id));
		PopulationUtils.putSubpopulation(freight, "freight");
		Plan plan = f.createPlan();
		plan.addActivity(f.createActivityFromCoord("freight", new Coord(0, 0)));
		freight.addPlan(plan);
		freight.setSelectedPlan(plan);
		population.addPerson(freight);
	}

}
