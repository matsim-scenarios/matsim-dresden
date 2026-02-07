package org.matsim.run;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.ApplicationUtils;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.FacilitiesConfigGroup;
import org.matsim.core.config.groups.VspExperimentalConfigGroup;
import org.matsim.core.config.groups.VspExperimentalConfigGroup.VspDefaultsCheckingLevel;
import org.matsim.core.controler.Controler;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.run.scenarios.DresdenModel;
import org.matsim.run.scenarios.DresdenUtils;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.simwrapper.SimWrapperConfigGroup.DefaultDashboardsMode;
import org.matsim.testcases.MatsimTestUtils;
import org.matsim.utils.eventsfilecomparison.ComparisonResult;

import java.io.File;
import java.io.UncheckedIOException;

import static org.junit.jupiter.api.Assertions.assertEquals;


class RunIntegrationTest {
	@RegisterExtension
	private final MatsimTestUtils utils = new MatsimTestUtils();

	@Test
	@Disabled  // need to check if this tests something meaningful with the calibrated input plans file hickup.  kai, dec'25
	void runScenario() {
		Config config = ConfigUtils.loadConfig(String.format("input/%s/dresden-%s-0.1pct.config.xml", DresdenModel.VERSION, DresdenModel.VERSION));

//		config.controller().setWritePlansInterval( 0 );
//		config.controller().setWritePlansUntilIteration( -1 );

//		config.controller().setWriteEventsInterval( 0 );
//		config.controller().setWriteEventsUntilIteration( -1 );

		// das muss immer noch irgendwie mit der last iteration zusammen passen, sonst bekommt man keine output_*  :-(

		config.qsim().setStartTime( 6.*3600 ); // 5 is too early
		config.qsim().setEndTime( 10.*3600 ); // 10 is barely enough to be after the morning peak

		config.facilities().setInputFile( null );
		config.facilities().setFacilitiesSource( FacilitiesConfigGroup.FacilitiesSource.onePerActivityLinkInPlansFile );
		// this is different from the matsim-dresden default, but avoids committing 50MB into git.

		config.vspExperimental().setVspDefaultsCheckingLevel( VspDefaultsCheckingLevel.warn );
		// otherwise, we need, e.g., to write more plans files than we want to.

		ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class).setDefaultDashboards( DefaultDashboardsMode.disabled );

		int code = MATSimApplication.execute(DresdenModel.class, config,
			"--iterations", "0",
			"--output", utils.getOutputDirectory(),
//			"--config:controller.overwriteFiles=deleteDirectoryIfExists",
			"--config:global.numberOfThreads", "2",
			"--config:qsim.numberOfThreads", "2",
			"--emissions", DresdenUtils.EmissionsAnalysisHandling.NO_EMISSIONS_ANALYSIS.name() );
		Assertions.assertEquals(0, code, "Must return non error code");

		Assertions.assertTrue(new File(utils.getOutputDirectory()).isDirectory());
		Assertions.assertTrue(new File(utils.getOutputDirectory()).exists());

		String outputPlansFilename = config.controller().getRunId() + ".output_plans.xml.gz";

		{
			Population expected = PopulationUtils.createPopulation( ConfigUtils.createConfig() ) ;
			PopulationUtils.readPopulation( expected, utils.getInputDirectory() + "/" + outputPlansFilename );

			Population actual = PopulationUtils.createPopulation( ConfigUtils.createConfig() ) ;
			PopulationUtils.readPopulation( actual, utils.getOutputDirectory() + "/" + outputPlansFilename );

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

	}

	@Test
	@Disabled  // need to check if this tests something meaningful with the calibrated input plans file hickup.  kai, dec'25
	void runScenario_fails() {
		Config config = ConfigUtils.loadConfig(String.format("input/%s/dresden-%s-10pct.config.xml", DresdenModel.VERSION, DresdenModel.VERSION));
		ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class).setDefaultDashboards( DefaultDashboardsMode.disabled );

		// start with wrong plans file
		Assertions.assertThrows(UncheckedIOException.class, () -> MATSimApplication.execute(DresdenModel.class, config,
			"--1pct",
			"--iterations", "1",
			"--config:plans.inputPlansFile", "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/dresden/dresden-v1.0/input/yyy.xml.gz",
			"--output", utils.getOutputDirectory(),
			"--config:controller.overwriteFiles=deleteDirectoryIfExists",
			"--config:global.numberOfThreads", "2",
			"--config:qsim.numberOfThreads", "2",
			"--emissions", "DISABLED"), "Must throw FileNotFoundException");
	}

	@Test
	@Disabled  // need to check if this tests something meaningful with the calibrated input plans file hickup.  kai, dec'25
	void runScenario_main() {
		String configPath = String.format("input/%s/dresden-%s-10pct.config.xml", DresdenModel.VERSION, DresdenModel.VERSION);

		DresdenModel.main(new String[]{"--config", configPath,
			"--1pct",
			"--iterations", "1",
			"--config:plans.inputPlansFile", "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/dresden/dresden-v1.0/input/dresden-v1.0-0.1pct.plans-initial.xml.gz",//记录使用的样品数量
			"--output", utils.getOutputDirectory(),//记录output路径
			"--config:controller.overwriteFiles=deleteDirectoryIfExists",//刷新output
			"--config:global.numberOfThreads", "2",
			"--config:qsim.numberOfThreads", "2",
			"--config:simwrapper.defaultDashboards", "disabled",
			"--emissions", "DISABLED"});

		Assertions.assertTrue(new File(utils.getOutputDirectory()).isDirectory());
		Assertions.assertTrue(new File(utils.getOutputDirectory()).exists());
	}

	@Test
	@Disabled // need to check if this tests something meaningful with the calibrated input plans file hickup.  kai, dec'25
	void runScenario_main_fails() {
		String configPath = String.format("input/%s/dresden-%s-10pct.config.xml", DresdenModel.VERSION, DresdenModel.VERSION);

		// start with wrong plans file
		Assertions.assertThrows(UncheckedIOException.class, () ->//if fail go pass, if pass go fail
			DresdenModel.main(new String[]{"--config", configPath,
				"--1pct",
				"--iterations", "1",
				"--config:plans.inputPlansFile", "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/dresden/dresden-v1.0/input/yyy.xml.gz",
				"--output", utils.getOutputDirectory(),
				"--config:controller.overwriteFiles=deleteDirectoryIfExists",
				"--config:global.numberOfThreads", "2",
				"--config:qsim.numberOfThreads", "2",
				"--config:simwrapper.defaultDashboards", "disabled",
				"--emissions", "DISABLED"}));

		Assertions.assertTrue(new File(utils.getOutputDirectory()).isDirectory());
		Assertions.assertTrue(new File(utils.getOutputDirectory()).exists());
	}
}
