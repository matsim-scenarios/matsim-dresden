package org.matsim.run;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.run.scenarios.DresdenModel;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.testcases.MatsimTestUtils;

import java.io.File;
import java.io.UncheckedIOException;


class RunIntegrationTest {
	@RegisterExtension
	private final MatsimTestUtils utils = new MatsimTestUtils();

	@Test
	void runScenario() {
		Config config = ConfigUtils.loadConfig(String.format("input/%s/dresden-%s-10pct.config.xml", DresdenModel.VERSION, DresdenModel.VERSION));
		ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class).setDefaultDashboards(SimWrapperConfigGroup.Mode.disabled);

		int code = MATSimApplication.execute(DresdenModel.class, config,
			"--1pct",
			"--iterations", "1",
			"--config:plans.inputPlansFile", "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/dresden/dresden-v1.0/input/dresden-v1.0-0.1pct.plans-initial.xml.gz",
			"--output", utils.getOutputDirectory(),
			"--config:controller.overwriteFiles=deleteDirectoryIfExists",
			"--config:global.numberOfThreads", "2",
			"--config:qsim.numberOfThreads", "2",
			"--emissions", "DISABLED");
		Assertions.assertEquals(0, code, "Must return non error code");

		Assertions.assertTrue(new File(utils.getOutputDirectory()).isDirectory());
		Assertions.assertTrue(new File(utils.getOutputDirectory()).exists());
	}

	@Test
	void runScenario_fails() {
		Config config = ConfigUtils.loadConfig(String.format("input/%s/dresden-%s-10pct.config.xml", DresdenModel.VERSION, DresdenModel.VERSION));
		ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class).setDefaultDashboards(SimWrapperConfigGroup.Mode.disabled);

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
