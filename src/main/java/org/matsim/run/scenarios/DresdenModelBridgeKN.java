package org.matsim.run.scenarios;

import jakarta.annotation.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.utils.DresdenUtils;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

import static org.matsim.core.config.groups.ReplanningConfigGroup.StrategySettings;
import static org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule.DefaultStrategy;

public final class DresdenModelBridgeKN extends DresdenModel {
	// "final": for the time being, please try to avoid inheritance from inheritance.  kai, dec'25

	private static final Logger log = LogManager.getLogger( DresdenModelBridgeKN.class );

//	public static final String VERSION = "v1.0";

	private static final String pct = "1";

	public static void main(String[] args) {

		if ( args != null && args.length > 0 ) {
			// use the given args
		} else{
			final String nIterations = "0";
			args = new String[]{
				"--" + pct + "pct",
				"--iterations", nIterations,
				"--output", "./output/bridge_more4_kn_" + pct + "pct" + nIterations + "it",
				"--runId", "",
				"--config:controller.overwriteFiles", OverwriteFileSetting.deleteDirectoryIfExists.name(),
				"--config:global.numberOfThreads", "2",
				"--config:qsim.numberOfThreads", "2",
				"--config:simwrapper.defaultDashboards", SimWrapperConfigGroup.Mode.disabled.name(), // yyyy make enum and config option of same name
				"--emissions", DresdenUtils.FunctionalityHandling.DISABLED.name()
			};
		}

		MATSimApplication.execute( DresdenModelBridgeKN.class, args );
	}

	@Nullable
	@Override
	protected Config prepareConfig(Config config){
		super.prepareConfig( config );
		// add own config modifications here:

		config.controller().setWriteEventsInterval( 10 );
		config.controller().setWriteEventsUntilIteration( 0 );

		for( StrategySettings strategySetting : config.replanning().getStrategySettings() ){
			if( strategySetting.getStrategyName().contains( DefaultStrategy.TimeAllocationMutator ) ){
				strategySetting.setWeight( 0.0 );
			}
			if( strategySetting.getStrategyName().contains( DefaultStrategy.SubtourModeChoice ) ){
				strategySetting.setWeight( 0.0 );
			}
		}

//		config.vspExperimental().setVspDefaultsCheckingLevel( VspExperimentalConfigGroup.VspDefaultsCheckingLevel.warn );

		// The following does not work since the files are not in the same dir.  :-(
//		URL currentDir;
//		try{
//			currentDir = Paths.get( "/Users/kainagel/public-svn/matsim/scenarios/countries/de/dresden/dresden-v1.0/output/" + pct + "pct/").toUri().toURL();
//		} catch( MalformedURLException e ){
//			throw new RuntimeException( e );
//		}
//		config.setContext( currentDir );
//		// convert https filenames to stripped filenames.  Should be possible to write a more general method for that.
//		{
//			Path filename = Path.of( URI.create( config.network().getInputFile() ).getPath() ).getFileName();
//			config.network().setInputFile( filename.toString() );
//		}
//		{
//			Path filename = Path.of( URI.create( config.plans().getInputFile() ).getPath() ).getFileName();
//			config.plans().setInputFile( filename.toString() );
//		}
//		{
//			Path filename = Path.of( URI.create( config.facilities().getInputFile() ).getPath() ).getFileName();
//			config.facilities().setInputFile( filename.toString() );
//		}

		log.info( "at end of prepareConfig" );
		StringWriter writer = new StringWriter();
		new ConfigWriter( config, ConfigWriter.Verbosity.minimalAndWOActivities ).writeStream( new PrintWriter( writer ), System.lineSeparator() );
		log.info( System.lineSeparator() + System.lineSeparator() + writer.getBuffer() );
		log.info( "Complete config dump done." );
		log.info( "Checking consistency of config..." );
		config.checkConsistency();
		log.info( "Checking consistency of config done." );

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

//		for (Id<Link> linkId : closedLinks) {
//			Link link = scenario.getNetwork().getLinks().get(linkId);
//			if (link != null) {
//				link.setCapacity(0.00001);
//				link.setFreespeed( 0.000001/3.6 );
//			} else {
//				System.out.println("WARNING: link not found: " + linkId);
//			}
//		}

		for( Id<Link> closedLinkId : closedLinks ){
			scenario.getNetwork().removeLink( closedLinkId );
		}

	}

	@Override
	protected void prepareControler(Controler controler) {
		super.prepareControler( controler );
		// add own Controller configurations here:
	}

}
