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
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.facilities.FacilitiesUtils;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.utils.DresdenUtils;

import java.util.Set;

public final class DresdenModel4Moritz extends DresdenModel {
	// "final": for the time being, please try to avoid inheritance from inheritance.  kai, dec'25

	private static final Logger log = LogManager.getLogger( DresdenModel4Moritz.class );

//	public static final String VERSION = "v1.0";

	private static final String pct = "1";

	public static void main(String[] args) {
		Configurator.setLevel( ControllerUtils.class, Level.DEBUG );

		if ( args != null && args.length > 0 ) {
			// use the given args
		} else{
			final String nIterations = "0";
			args = new String[]{
				// CLI params processed by MATSimApplication:
				"--" + pct + "pct",
				"--iterations", nIterations,
				"--output", "./output/bridge_more4_c_kn_" + pct + "pct" + nIterations + "it",
				"--runId", "",
				"--emissions=false",
//				"--generate-dashboards=false",

				// CLI params processed by standard MATSim:
				"--config:global.numberOfThreads", "4",
				"--config:qsim.numberOfThreads", "4",
				"--config:simwrapper.defaultDashboards", SimWrapperConfigGroup.Mode.disabled.name() // yyyy make enum and config option of same name
			};
		}

		MATSimApplication.execute( DresdenModel4Moritz.class, args );
	}

	@Nullable
	@Override
	protected Config prepareConfig(Config config){
		super.prepareConfig( config );
		// add own config modifications here:

		config.controller().setWriteEventsInterval( 10 );
		config.controller().setWriteEventsUntilIteration( 0 );
		config.controller().setOverwriteFileSetting( OverwriteFileSetting.deleteDirectoryIfExists );

		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {
		super.prepareScenario( scenario );
		// add own scenario modifications here:
	}

	@Override
	protected void prepareControler(Controler controler) {
		super.prepareControler( controler );
		// add own Controller configurations here:
	}

}
