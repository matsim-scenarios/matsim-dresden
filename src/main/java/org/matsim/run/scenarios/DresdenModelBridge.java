package org.matsim.run.scenarios;

import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import com.google.common.collect.Sets;
import com.google.inject.multibindings.Multibinder;
import jakarta.annotation.Nullable;
import org.matsim.analysis.CheckAndSummarizeLongDistanceFreightPopulation;
import org.matsim.analysis.CheckStayHomeAgents;
import org.matsim.analysis.personMoney.PersonMoneyEventsAnalysisModule;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.application.MATSimApplication;
import org.matsim.application.analysis.CheckPopulation;
import org.matsim.application.analysis.traffic.LinkStats;
import org.matsim.application.options.SampleOptions;
import org.matsim.application.prepare.CreateLandUseShp;
import org.matsim.application.prepare.counts.CreateCountsFromBAStData;
import org.matsim.application.prepare.longDistanceFreightGER.tripExtraction.ExtractRelevantFreightTrips;
import org.matsim.application.prepare.network.CleanNetwork;
import org.matsim.application.prepare.network.CreateNetworkFromSumo;
import org.matsim.application.prepare.population.*;
import org.matsim.application.prepare.pt.CreateTransitScheduleFromGtfs;
import org.matsim.contrib.vsp.pt.fare.DistanceBasedPtFareParams;
import org.matsim.contrib.vsp.pt.fare.FareZoneBasedPtFareParams;
import org.matsim.contrib.vsp.pt.fare.PtFareConfigGroup;
import org.matsim.contrib.vsp.pt.fare.PtFareModule;
import org.matsim.contrib.vsp.scenario.SnzActivities;
import org.matsim.contrib.vsp.scoring.RideScoringParamsFromCarParams;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.config.groups.VspExperimentalConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.turnRestrictions.DisallowedNextLinks;
import org.matsim.core.replanning.annealing.ReplanningAnnealerConfigGroup;
import org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.dashboards.DresdenDashboardProvider;
import org.matsim.prepare.*;
import org.matsim.simwrapper.DashboardProvider;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.simwrapper.SimWrapperModule;
import org.matsim.smallScaleCommercialTrafficGeneration.GenerateSmallScaleCommercialTrafficDemand;
import org.matsim.smallScaleCommercialTrafficGeneration.prepare.CreateDataDistributionOfStructureData;
import picocli.CommandLine;
import playground.vsp.scoring.IncomeDependentUtilityOfMoneyPersonScoringParameters;

import java.util.HashSet;
import java.util.Set;

import static org.matsim.utils.DresdenUtils.*;

@CommandLine.Command(header = ":: Dresden Scenario ::", version = DresdenModelBridge.VERSION, mixinStandardHelpOptions = true)
@MATSimApplication.Prepare({
		CreateNetworkFromSumo.class, CreateTransitScheduleFromGtfs.class, TrajectoryToPlans.class, GenerateShortDistanceTrips.class,
		MergePopulations.class, ExtractRelevantFreightTrips.class, DownSamplePopulation.class, ExtractHomeCoordinates.class,
		CreateLandUseShp.class, ResolveGridCoordinates.class, FixSubtourModes.class, AdjustActivityToLinkDistances.class, XYToLinks.class,
		CleanNetwork.class, PrepareNetwork.class, SplitActivityTypesDuration.class, CreateCountsFromBAStData.class,
		CutOutDresdenPopulation.class, CreateDataDistributionOfStructureData.class, GenerateSmallScaleCommercialTrafficDemand.class,
		PreparePopulation.class, CreateFacilitiesFromPopulation.class, CreateSingleTransportModePopulation.class, RemoveVehicleInformationFromPopulation.class
})
@MATSimApplication.Analysis({
		LinkStats.class, CheckPopulation.class, CheckAndSummarizeLongDistanceFreightPopulation.class, CheckStayHomeAgents.class
})
public class DresdenModelBridge extends DresdenModel {

	public static final String VERSION = "v1.0";

//	@CommandLine.Mixin
//	private final SampleOptions sample = new SampleOptions(100, 25, 10, 1);
//	@CommandLine.Option(names = "--emissions", defaultValue = "ENABLED", description = "Define if emission analysis should be performed or not.")
//	FunctionalityHandling emissions;
//	@CommandLine.Option(names = "--explicit-walk-intermodality", defaultValue = "ENABLED", description = "Define if explicit walk intermodality parameter to/from pt should be set or not (use default).")
//	static FunctionalityHandling explicitWalkIntermodality;


//	public DresdenModelBridge(@Nullable Config config) {
//		super(config);
//	}
//
//	public DresdenModelBridge() {
//		super(String.format("input/%s/dresden-%s-10pct.config.xml", VERSION, VERSION));
//	}

	public static void main(String[] args) {
		if ( args != null && args.length > 0 ) {
			throw new RuntimeException("there is something in args but this code is ignoring it. Clarify");
		}
//		String configPath = String.format("input/%s/dresden-%s-10pct.config.xml", DresdenModel.VERSION, DresdenModel.VERSION);
		String configPath = "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/dresden/dresden-v1.0/input/v1.0/dresden-v1.0-1pct.config.xml";
//		String configPath = "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/dresden/dresden-v1.0/output/1pct/009.output_config.xml";
		// it was the more flexible variant above but that did not work. kai/gregorL, dec'25

		args = new String[]{"--config", configPath,
			"--1pct",
			"--iterations", "1",
			"--output", "./output/bridge/",
			"--config:controller.overwriteFiles=deleteDirectoryIfExists",//刷新output
			"--config:global.numberOfThreads", "2",
			"--config:qsim.numberOfThreads", "2",
			"--config:simwrapper.defaultDashboards", "disabled",
			"--config:plans.inputPlansFile","../../output/1pct/009.output_plans.xml.gz",
			"--emissions", "DISABLED"};
		//对比test生成的output与预期output是否一致

		MATSimApplication.execute(DresdenModelBridge.class, args);
	}

	@Nullable
	@Override
	protected Config prepareConfig(Config config) {
		super.prepareConfig( config );

		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {
		super.prepareScenario( scenario );

		//-488766980
		//761288685
		//-264360396#1
		//505502627#0
		//		add freight modes of DresdenUtils to network.
//		this happens in the makefile pipeline already, but we do it here anyways, in case somebody uses a preliminary network.
		PrepareNetwork.prepareFreightNetwork(scenario.getNetwork());

		Set<Id<Link>> closedLinks = Set.of(
			Id.createLinkId("-488766980"),
			Id.createLinkId("761288685"),
			Id.createLinkId("-264360396#1"),
			Id.createLinkId("505502627#0")
		);

		for (Id<Link> linkId : closedLinks) {
			Link link = scenario.getNetwork().getLinks().get(linkId);
			if (link != null) {
				link.setCapacity(0.001);
			} else {
				System.out.println("WARNING: link not found: " + linkId);
			}
		}

//		remove disallowed links. The disallowed links cause many problems and (usually) are not useful in our rather macroscopic view on transport systems.
//		for (Link link : scenario.getNetwork().getLinks().values()) {
//			DisallowedNextLinks disallowed = NetworkUtils.getDisallowedNextLinks(link);
//			if (disallowed != null) {
//				link.getAllowedModes().forEach(disallowed::removeDisallowedLinkSequences);
//				if (disallowed.isEmpty()) {
//					NetworkUtils.removeDisallowedNextLinks(link);
//				}
//			}
//		}

	}

	@Override
	protected void prepareControler(Controler controler) {
		super.prepareControler( controler );
	}

}
