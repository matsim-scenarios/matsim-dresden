package org.matsim.run.scenarios;

import jakarta.annotation.Nullable;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;
import org.matsim.api.core.v01.Scenario;
import org.matsim.application.MATSimApplication;
import org.matsim.contrib.vsp.scenario.SnzActivities;
import org.matsim.core.config.Config;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.ControllerUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.controler.OutputDirectoryLogging;
import org.matsim.run.scenarios.DresdenUtils.EmissionsAnalysisHandling;
import org.matsim.simwrapper.SimWrapperConfigGroup.DefaultDashboardsMode;
import picocli.CommandLine;

public final class DresdenModelExperiments extends DresdenModel {
	// "final": for the time being, please try to avoid inheritance from inheritance.  kai, dec'25

	private static final Logger log = LogManager.getLogger( DresdenModelExperiments.class );

	private static final String VERSION="1.0";

	@CommandLine.Option(names = "--wrap-around-activities-handling", description = "Define if wrap-around activities should be split at midnight." )
	private WrapAroundActivitiesHandling wrapAroundActivitiesHandling = WrapAroundActivitiesHandling.none;

	public static void main(String[] args) {
		OutputDirectoryLogging.catchLogEntries();
		Configurator.setLevel( ControllerUtils.class, Level.DEBUG );

		if ( args != null && args.length > 0 ) {
			// use the given args
		} else{
			final String pct = "1";
			final long nIterations = 0;
			final String wrapAround = WrapAroundActivitiesHandling.splitAndRemoveOpeningTimes.name();
			// derived:
			final String runId = wrapAround + "_" + pct + "pct" + nIterations + "it";
			DefaultDashboardsMode dashboardsMode = DefaultDashboardsMode.disabled;
			if ( nIterations > 10 ) dashboardsMode = DefaultDashboardsMode.enabled;
			args = new String[]{
				// CLI params processed by MATSimApplication:
				"--config", String.format( "input/v%s/dresden-v%s-%spct.config.xml", VERSION, VERSION, pct ),
				"--" + pct + "pct",
				"--iterations", String.valueOf( nIterations ),
				"--output", "./output/experiments/" + runId,
				"--runId", runId,
				"--wrap-around-activities-handling", wrapAround,
				"--emissions", EmissionsAnalysisHandling.NO_EMISSIONS_ANALYSIS.name(),

				// CLI params processed by standard MATSim:
				"--config:controller.overwriteFiles", OverwriteFileSetting.deleteDirectoryIfExists.name(),
				"--config:global.numberOfThreads", "4",
				"--config:qsim.numberOfThreads", "4",
				"--config:simwrapper.defaultDashboards", dashboardsMode.name()
			};
		}

		MATSimApplication.execute( DresdenModelExperiments.class, args );
	}

	@Override protected void addScoringParams( Config config ) {
		// yyyy need to find a way to remove the existing scoring params; then this can be programmed without inheritance
		switch ( wrapAroundActivitiesHandling ) {
			case none -> {
				SnzActivities.addScoringParams( config );
			}
			case splitAndRemoveOpeningTimes -> {
				SnzActivities.addMorningEveningScoringParams( config );
			}
			default -> throw new IllegalStateException("Unexpected value: " + wrapAroundActivitiesHandling);
		}
	}

	@Nullable
	@Override
	protected Config prepareConfig(Config config){
		super.prepareConfig( config );
		// add own config modifications here:

		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {
		super.prepareScenario( scenario );
		// add own scenario modifications here:

		switch( wrapAroundActivitiesHandling ) {
			case none -> {
			}
			case splitAndRemoveOpeningTimes -> {
				org.matsim.contrib.vsp.scenario.Activities.changeWrapAroundActsIntoMorningAndEveningActs( scenario );
			}
			default -> throw new IllegalStateException("Unexpected value: " + wrapAroundActivitiesHandling);
		}

	}

	@Override
	protected void prepareControler(Controler controler) {
		super.prepareControler( controler );

		// add own Controller configurations here:
	}

	enum WrapAroundActivitiesHandling{none, splitAndRemoveOpeningTimes}

}
