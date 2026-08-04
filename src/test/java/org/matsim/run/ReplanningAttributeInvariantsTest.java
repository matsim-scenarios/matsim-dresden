package org.matsim.run;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimApplication;
import org.matsim.contrib.common.conventions.vsp.SnzActivities;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.FacilitiesConfigGroup;
import org.matsim.core.config.groups.VspExperimentalConfigGroup.VspDefaultsCheckingLevel;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.prepare.EncodeTypicalDuration;
import org.matsim.run.scenarios.DresdenModel;
import org.matsim.run.scenarios.DresdenUtils;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.simwrapper.SimWrapperConfigGroup.DefaultDashboardsMode;
import org.matsim.testcases.MatsimTestUtils;

import java.io.File;
import java.util.List;

/**
 * The experiment's core invariant is that every person-subpopulation activity is scored against its survey-derived
 * per-activity typical duration -- which requires the typicalDuration attributes to survive several iterations of
 * replanning (plan copying, mode choice, rerouting, time mutation) and the scoring's plan cursor to stay aligned
 * across the replanning boundary (scoring functions are created BEFORE replanning; see DresdenActivityScoring).
 * <p>
 * This test runs the small IT scenario for two iterations (one live innovation round) with the attribute requirement
 * armed (the default): any lost attribute or cursor divergence aborts the run inside the scoring, so mere completion
 * already proves the runtime invariants. The output plans are then checked activity by activity, across every
 * person's whole plan memory, so attribute survival is verified in the persisted state too.
 */
class ReplanningAttributeInvariantsTest {
	@RegisterExtension
	private final MatsimTestUtils utils = new MatsimTestUtils();

	@Test
	void attributesSurviveReplanningAndScoringStaysAligned() throws Exception {
		// the v1.0 IT fixture is type-encoded; stamp per-activity typical durations the way the pipeline does,
		// including the minimum-typical-duration clamp (the fixture contains zero-duration activities, whose
		// unclamped typical of 0.0 the strict scoring rightly refuses)
		String attributedPlans = utils.getOutputDirectory() + "attributed-plans.xml.gz";
		new EncodeTypicalDuration().execute(
			"input/v1.0/dresden-v1.0-0.1pct.plans-initial.xml.gz", "--output", attributedPlans,
			"--min-typical-duration", "300");
		Assertions.assertTrue(new File(attributedPlans).exists(), "encode-typical-duration must succeed");

		Config config = ConfigUtils.loadConfig(String.format("input/%s/dresden-%s-IT.config.xml", DresdenModel.VERSION, DresdenModel.VERSION));
		config.plans().setInputFile(new File(attributedPlans).getAbsolutePath());
		// the v1.0 fixture uses duration-binned activity types (home_44400, ...); register their params (the scoring
		// still needs the type to exist, even though the typical duration comes from the stamped attribute)
		SnzActivities.addScoringParams(config);
		config.qsim().setStartTime(6. * 3600);
		config.qsim().setEndTime(10. * 3600);
		config.facilities().setInputFile(null);
		config.facilities().setFacilitiesSource(FacilitiesConfigGroup.FacilitiesSource.onePerActivityLinkInPlansFile);
		config.vspExperimental().setVspDefaultsCheckingLevel(VspDefaultsCheckingLevel.warn);
		ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class).setDefaultDashboards(DefaultDashboardsMode.disabled);

		String runOutput = utils.getOutputDirectory() + "run/";
		int code = MATSimApplication.execute(DresdenModel.class, config,
			"--iterations", "2", // iteration 1 is a live innovation round (mode choice, rerouting, time mutation)
			"--output", runOutput,
			"--config:global.numberOfThreads", "2",
			"--config:qsim.numberOfThreads", "2",
			// NOT passing --allow-config-typical-durations: a missing attribute or diverged cursor aborts the run.
			"--emissions", DresdenUtils.EmissionsAnalysisHandling.NO_EMISSIONS_ANALYSIS.name());
		Assertions.assertEquals(0, code, "run must complete: any lost typicalDuration attribute or plan-cursor " +
			"divergence throws inside the scoring");

		// attribute survival in the persisted state: every main activity of every plan in every person's memory
		Population output = PopulationUtils.readPopulation(runOutput + config.controller().getRunId() + ".output_plans.xml.zst");
		int checked = 0;
		for (Person person : output.getPersons().values()) {
			for (Plan plan : person.getPlans()) {
				List<Activity> activities = TripStructureUtils.getActivities(plan, TripStructureUtils.StageActivityHandling.ExcludeStageActivities);
				if (activities.size() < 2) {
					continue; // stay-home plans are not stamped (and never scored)
				}
				for (Activity activity : activities) {
					Object attribute = activity.getAttributes().getAttribute(EncodeTypicalDuration.TYPICAL_DURATION);
					Assertions.assertTrue(attribute instanceof Number number && number.doubleValue() > 0,
						"person " + person.getId() + ", activity " + activity.getType() + " lost its typicalDuration attribute in replanning");
					checked++;
				}
			}
		}
		Assertions.assertTrue(checked > 1000, "expected to check a substantial number of activities, got " + checked);
	}
}
