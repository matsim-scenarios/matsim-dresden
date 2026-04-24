package org.matsim.run.scenarios;

import jakarta.annotation.Nullable;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.application.MATSimApplication;
import org.matsim.application.prepare.CreateLandUseShp;
import org.matsim.application.prepare.counts.CreateCountsFromBAStData;
import org.matsim.application.prepare.longDistanceFreightGER.tripExtraction.ExtractRelevantFreightTrips;
import org.matsim.application.prepare.network.CleanNetwork;
import org.matsim.application.prepare.network.CreateNetworkFromSumo;
import org.matsim.application.prepare.population.*;
import org.matsim.application.prepare.pt.CreateTransitScheduleFromGtfs;
import org.matsim.application.prepare.scenario.CreateScenarioCutOut;
import org.matsim.core.config.Config;
import org.matsim.core.controler.Controler;
import org.matsim.prepare.*;
import org.matsim.smallScaleCommercialTrafficGeneration.GenerateSmallScaleCommercialTrafficDemand;
import org.matsim.smallScaleCommercialTrafficGeneration.prepare.CreateDataDistributionOfStructureData;

import java.util.Set;
@MATSimApplication.Prepare({
	ScaleDigitalTwinWithSnzData.class, AssignPersonAttributeFromShapefile.class, DummyPopulationProcess.class
})
public final class DresdenModelDigitalTwin extends DresdenModel {

	public static void main(String[] args) {
		if ( args != null && args.length > 0 ) {
			// use the given args
		} else{
//			args = new String[]{
//				"--1pct",
//				"--iterations", "10",
//				"--output", "./output/bridge_more4/",
//				"--config:controller.overwriteFiles=deleteDirectoryIfExists",
//				"--config:global.numberOfThreads", "2",
//				"--config:qsim.numberOfThreads", "2",
//				"--config:simwrapper.defaultDashboards", "disabled",
//				"--emissions", "DISABLED"};
			args = new String[] { "prepare" };
		}

		MATSimApplication.execute(DresdenModelDigitalTwin.class, args);
	}

	@Nullable
	@Override
	protected Config prepareConfig(Config config) {
		super.prepareConfig( config );
		// add own config modifications here:

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
