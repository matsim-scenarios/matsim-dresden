package org.matsim.run.scenarios;

import jakarta.annotation.Nullable;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.ControllerUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.simwrapper.SimWrapperConfigGroup.DefaultDashboardsMode;
import org.matsim.run.scenarios.DresdenUtils.EmissionsAnalysisHandling;

import java.util.Set;

public final class DresdenModelBridgeKN extends DresdenModel {
	// "final": for the time being, please try to avoid inheritance from inheritance.  kai, dec'25

	private static final Logger log = LogManager.getLogger( DresdenModelBridgeKN.class );

//	public static final String VERSION = "v1.0";


	public static void main(String[] args) {

		Configurator.setLevel( ControllerUtils.class, Level.DEBUG );

		if ( args != null && args.length > 0 ) {
			// use the given args
		} else{
			final String pct = "1";
			final String nIterations = "0";
			args = new String[]{
				// CLI params processed by MATSimApplication:
				"--config=./input/v1.0/dresden-v1.0-1pct.config.xml",
				"--" + pct + "pct",
				"--iterations", nIterations,
				"--output", "./output/bridge_2026-02-05_kn_" + pct + "pct" + nIterations + "it",
				"--runId", "",
				"--emissions", EmissionsAnalysisHandling.NO_EMISSIONS_ANALYSIS.name(),

				// CLI params processed by standard MATSim:
				"--config:global.numberOfThreads", "4",
				"--config:qsim.numberOfThreads", "4",
				"--config:simwrapper.defaultDashboards", DefaultDashboardsMode.disabled.name()
			};
		}

		MATSimApplication.execute( DresdenModelBridgeKN.class, args );
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

		Set<Id<Link>> closedLinks = Set.of(
			Id.createLinkId(-488766980),
			Id.createLinkId(761288685),
			Id.createLinkId("-264360396#1"),
			Id.createLinkId("505502627#0"),
			Id.createLinkId( 132572494 ), // providing a number instead of a string is also ok. kai, dec'25
			Id.createLinkId( 277710971 ),
			Id.createLinkId( 4214231 ),
			Id.createLinkId( 901959078 ),
			Id.createLinkId( 1031454500 ),
			Id.createLinkId( -264360404 ),
			Id.createLinkId(30129851),
			Id.createLinkId(-30129851),
			Id.createLinkId(425728245),
			Id.createLinkId(14448952)
										  );
		for( Id<Link> closedLinkId : closedLinks ){
			scenario.getNetwork().removeLink( closedLinkId );
		}

		ScenarioUtils.cleanScenario( scenario );
	}

	@Override
	protected void prepareControler(Controler controler) {
		super.prepareControler( controler );
		// add own Controller configurations here:
	}

}
