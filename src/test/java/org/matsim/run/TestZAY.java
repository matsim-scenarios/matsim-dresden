package org.matsim.run;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.run.scenarios.DresdenModel;
import org.matsim.testcases.MatsimTestUtils;
import org.matsim.utils.eventsfilecomparison.ComparisonResult;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestZAY {
	@RegisterExtension
	private final MatsimTestUtils utils = new MatsimTestUtils();

	@Test
	void runScenario_main() {
		String configPath = String.format("input/%s/dresden-%s-10pct.config.xml", DresdenModel.VERSION, DresdenModel.VERSION);

		DresdenModel.main(new String[]{"--config", configPath,
			"--1pct",
			"--iterations", "1",
			"--output", utils.getOutputDirectory(),//记录output路径
			"--config:controller.overwriteFiles=deleteDirectoryIfExists",//刷新output
			"--config:global.numberOfThreads", "2",
			"--config:qsim.numberOfThreads", "2",
			"--config:simwrapper.defaultDashboards", "disabled",
			"--emissions", "DISABLED"});
		//对比test生成的output与预期output是否一致
		{
			Population expected = PopulationUtils.createPopulation( ConfigUtils.createConfig() ) ;
			PopulationUtils.readPopulation( expected, utils.getInputDirectory() + "/dresden-1pct.output_plans.xml.gz" );

			Population actual = PopulationUtils.createPopulation( ConfigUtils.createConfig() ) ;
			PopulationUtils.readPopulation( actual, utils.getOutputDirectory() + "/dresden-1pct.output_plans.xml.gz" );

			for ( Id<Person> personId : expected.getPersons().keySet()) {
				double scoreReference = expected.getPersons().get(personId).getSelectedPlan().getScore();
				double scoreCurrent = actual.getPersons().get(personId).getSelectedPlan().getScore();
				assertEquals(scoreReference, scoreCurrent, 0.001, "Scores of person=" + personId + " are different");
			}


//				boolean result = PopulationUtils.comparePopulations( expected, actual );
//				Assert.assertTrue( result );
			// (There are small differences in the score.  Seems that there were some floating point changes in Java 17, and the
			// differ by JDK (e.g. oracle vs. ...).   So not testing this any more for the time being.  kai, jul'23
		}
		{
			String expected = utils.getInputDirectory() + "/dresden-1pct.output_events.xml.gz" ;
			String actual = utils.getOutputDirectory() + "/dresden-1pct.output_events.xml.gz" ;
			ComparisonResult result = EventsUtils.compareEventsFiles( expected, actual );
			assertEquals( ComparisonResult.FILES_ARE_EQUAL, result );
		}

		Assertions.assertTrue(new File(utils.getOutputDirectory()).isDirectory());
		Assertions.assertTrue(new File(utils.getOutputDirectory()).exists());
	}
}
