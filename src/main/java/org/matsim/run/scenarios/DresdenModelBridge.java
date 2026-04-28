package org.matsim.run.scenarios;

import jakarta.annotation.Nullable;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;
import org.matsim.core.controler.Controler;

import java.util.Set;

public final class DresdenModelBridge extends DresdenModel {
	// "final": for the time being, please try to avoid inheritance from inheritance.  kai, dec'25

//	public static final String VERSION = "v1.0";

	public static void main(String[] args) {
		if ( args != null && args.length > 0 ) {
			// use the given args
		} else{
			args = new String[]{
				"--1pct",
				"--iterations", "10",
				"--output", "./output/bridge_more4/",
				"--config:controller.overwriteFiles=deleteDirectoryIfExists",// Refresh the output
				"--config:global.numberOfThreads", "2",
				"--config:qsim.numberOfThreads", "2",
				"--config:simwrapper.defaultDashboards", "disabled",
				"--emissions", "DISABLED"};
			// Compare the test output with the expected output
			// yy If we collaborate on code, could you please do comments in english?  Thanks.  kai, dec'25
		}

		MATSimApplication.execute(DresdenModelBridge.class, args);
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

		//-488766980
		//761288685
		//-264360396#1
		//505502627#0
		//14448952
		//-30129851
		//30129851
		//425728245
		//14448952

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

		for (Id<Link> linkId : closedLinks) {
			Link link = scenario.getNetwork().getLinks().get(linkId);
			if (link != null) {
				link.setCapacity(0.00001);
				link.setFreespeed( 0.000001/3.6 );
			} else {
				System.out.println("WARNING: link not found: " + linkId);
			}
		}
	}

	@Override
	protected void prepareControler(Controler controler) {
		super.prepareControler( controler );
		// add own Controller configurations here:
	}

}
