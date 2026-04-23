package org.matsim.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
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
		Map<String, Double> personStatsPerPLZ = ScaleDigitalTwinWithSnzData.loadOohStatsPerPLZ(personStats);
		assertEquals(0.6905697445972495, personStatsPerPLZ.get("01067"), MatsimTestUtils.EPSILON);
		assertEquals(0.6767335199004975, personStatsPerPLZ.get("01069"), MatsimTestUtils.EPSILON);
		assertEquals(0.6811880666244992, personStatsPerPLZ.get(ScaleDigitalTwinWithSnzData.GLOBAL), MatsimTestUtils.EPSILON);
	}

//	@Test
//	void testModifyPopulation() {
//		String[] args = new String[] {
//				"--outputpath", utils.getOutputDirectory(),
//				"--inputconfig", IOUtils.extendUrl(ExamplesUtils.getTestScenarioURL("equil"), "config.xml").toString(),
//				"--personstats", utils.getClassInputDirectory() + "/testdata.csv"
//
//		};
//		ScaleDigitalTwinWithSnzData.main(args);
//		Population population = PopulationUtils.readPopulation(utils.getOutputDirectory() + "/" + ScaleDigitalTwinWithSnzData.POPULATIONFILE);
//		long mobile = population.getPersons().values().parallelStream().filter(p -> (Boolean) p.getAttributes().getAttribute(ScaleDigitalTwinWithSnzData.MOBILE) == true).count();
//		assertEquals(mobile, 66);
//		assertEquals(population.getPersons().size(), 100);
//	}

//	@Test
//	void testOriginalPopulation() {
//		String[] args = new String[] {
//				"--outputpath", utils.getOutputDirectory(),
//				"--inputconfig", IOUtils.extendUrl(ExamplesUtils.getTestScenarioURL("equil"), "config.xml").toString()
//		};
//		ScaleDigitalTwinWithSnzData.main(args);
//		Population population = PopulationUtils.readPopulation(utils.getOutputDirectory() + "/" + ScaleDigitalTwinWithSnzData.POPULATIONFILE);
//		long mobile = population.getPersons().values().parallelStream().filter(p -> (Boolean) p.getAttributes().getAttribute(ScaleDigitalTwinWithSnzData.MOBILE) == true).count();
//		assertEquals(mobile, 100);
//		assertEquals(population.getPersons().size(), 100);
//	}

}
