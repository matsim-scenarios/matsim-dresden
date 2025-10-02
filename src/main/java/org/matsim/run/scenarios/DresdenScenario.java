package org.matsim.run.scenarios;

import jakarta.annotation.Nullable;
import org.matsim.analysis.CheckAndSummarizeLongDistanceFreightPopulation;
import org.matsim.api.core.v01.Scenario;
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
import org.matsim.contrib.vsp.scenario.SnzActivities;
import org.matsim.contrib.vsp.scoring.RideScoringParamsFromCarParams;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.config.groups.VspExperimentalConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.replanning.annealing.ReplanningAnnealerConfigGroup;
import org.matsim.prepare.CutOutDresdenPopulation;
import org.matsim.prepare.PrepareNetwork;
import org.matsim.prepare.PreparePopulation;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.smallScaleCommercialTrafficGeneration.GenerateSmallScaleCommercialTrafficDemand;
import org.matsim.smallScaleCommercialTrafficGeneration.prepare.CreateDataDistributionOfStructureData;
import org.matsim.utils.DresdenUtils;
import picocli.CommandLine;

@CommandLine.Command(header = ":: Dresden Scenario ::", version = DresdenScenario.VERSION, mixinStandardHelpOptions = true)
@MATSimApplication.Prepare({
		CreateNetworkFromSumo.class, CreateTransitScheduleFromGtfs.class, TrajectoryToPlans.class, GenerateShortDistanceTrips.class,
		MergePopulations.class, ExtractRelevantFreightTrips.class, DownSamplePopulation.class, ExtractHomeCoordinates.class,
		CreateLandUseShp.class, ResolveGridCoordinates.class, FixSubtourModes.class, AdjustActivityToLinkDistances.class, XYToLinks.class,
		CleanNetwork.class, PrepareNetwork.class, SplitActivityTypesDuration.class, CreateCountsFromBAStData.class,
		CutOutDresdenPopulation.class, CreateDataDistributionOfStructureData.class, GenerateSmallScaleCommercialTrafficDemand.class,
		PreparePopulation.class
})
@MATSimApplication.Analysis({
		LinkStats.class, CheckPopulation.class, CheckAndSummarizeLongDistanceFreightPopulation.class
})
public class DresdenScenario extends MATSimApplication {

	static final String VERSION = "v1.0";

	@CommandLine.Mixin
	private final SampleOptions sample = new SampleOptions(100, 25, 10, 1);

	@CommandLine.Option(names = "--emissions", defaultValue = "ENABLED", description = "Define if emission analysis should be performed or not.")
	DresdenUtils.FunctionalityHandling emissions;


	public DresdenScenario(@Nullable Config config) {
		super(config);
	}

	public DresdenScenario() {
		super(String.format("input/%s/dresden-%s-10pct.config.xml", VERSION, VERSION));
	}

	public static void main(String[] args) {
		MATSimApplication.run(DresdenScenario.class, args);
	}

	@Nullable
	@Override
	protected Config prepareConfig(Config config) {

		// Add all activity types with time bins
		SnzActivities.addScoringParams(config);

		//		add simwrapper config module
		SimWrapperConfigGroup simWrapper = ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class);

		simWrapper.defaultParams().setContext("");
		simWrapper.defaultParams().setMapCenter("14.5,51.53");
		simWrapper.defaultParams().setMapZoomLevel(6.8);
		simWrapper.defaultParams().setShp("../shp/dresden-utm32n.shp");

		if (sample.isSet()){
			config.controller().setOutputDirectory(sample.adjustName(config.controller().getOutputDirectory()));
			config.plans().setInputFile(sample.adjustName(config.plans().getInputFile()));
			config.controller().setRunId(sample.adjustName(config.controller().getRunId()));

			config.qsim().setFlowCapFactor(sample.getSample());
			config.qsim().setStorageCapFactor(sample.getSample());
			config.counts().setCountsScaleFactor(sample.getSample());
			simWrapper.setSampleSize(sample.getSample());
		}

		config.vspExperimental().setVspDefaultsCheckingLevel(VspExperimentalConfigGroup.VspDefaultsCheckingLevel.abort);

		//		performing set to 6.0 after calibration task force july 24
		double performing = 6.0;
		ScoringConfigGroup scoringConfigGroup = config.scoring();
		scoringConfigGroup.setPerforming_utils_hr(performing);
		scoringConfigGroup.setWriteExperiencedPlans(true);
		scoringConfigGroup.setPathSizeLogitBeta(0.);
//		TODO: check if the following 2 are needed.
//		scoringConfigGroup.addActivityParams(new ScoringConfigGroup.ActivityParams("freight_start").setTypicalDuration(30 * 60.));
//		scoringConfigGroup.addActivityParams(new ScoringConfigGroup.ActivityParams("freight_end").setTypicalDuration(30 * 60.));

//		set ride scoring params dependent from car params
//		2.0 + 1.0 = alpha + 1
//		ride cost = alpha * car cost
//		ride marg utility of traveling = (alpha + 1) * marg utility travelling car + alpha * beta perf
		double alpha = 2;
		RideScoringParamsFromCarParams.setRideScoringParamsBasedOnCarParams(scoringConfigGroup, alpha);

		config.qsim().setUsingTravelTimeCheckInTeleportation(true);
		config.qsim().setUsePersonIdForMissingVehicleId(false);
		config.routing().setAccessEgressType(RoutingConfigGroup.AccessEgressType.accessEgressModeToLink);

//		configure annealing params
		config.replanningAnnealer().setActivateAnnealingModule(true);
		ReplanningAnnealerConfigGroup.AnnealingVariable annealingVar = new ReplanningAnnealerConfigGroup.AnnealingVariable();
		annealingVar.setAnnealType(ReplanningAnnealerConfigGroup.AnnealOption.sigmoid);
		annealingVar.setEndValue(0.01);
		annealingVar.setHalfLife(0.5);
		annealingVar.setShapeFactor(0.01);
		annealingVar.setStartValue(0.45);
		annealingVar.setDefaultSubpopulation("person");
		config.replanningAnnealer().addAnnealingVariable(annealingVar);

		//		set pt fare calc model to fareZoneBased = fare of vvo tarifzonen are paid for trips within fare zones
//		every other trip: Deutschlandtarif
//		for more info see PTFareModule / ChainedPtFareCalculator classes in vsp contrib
		PtFareConfigGroup ptFareConfigGroup = ConfigUtils.addOrGetModule(config, PtFareConfigGroup.class);

//		TODO: project pt prices to ref year?

		FareZoneBasedPtFareParams vvo = new FareZoneBasedPtFareParams();
		vvo.setTransactionPartner("VVO Tarifzone 10 Dresden");
		vvo.setDescription("VVO Tarifzone 10 Dresden");
		vvo.setOrder(1);
		vvo.setFareZoneShp("../shp/vvo_tarifzone_10_dresden_utm32n.shp");

		DistanceBasedPtFareParams germany = DistanceBasedPtFareParams.GERMAN_WIDE_FARE_2024;
		germany.setTransactionPartner("Deutschlandtarif");
		germany.setDescription("Deutschlandtarif");
		germany.setOrder(2);

		ptFareConfigGroup.addParameterSet(vvo);
		ptFareConfigGroup.addParameterSet(germany);

//		TODO: continue run class cfg here
//		TODO: prepare small scale commercial cfg?

//		TODO: emissions config

		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {

		//		add longDistanceFreight as allowed modes together with car
//		PrepareNetwork.prepareFreightNetwork(scenario.getNetwork());
//
//		for (Link link : scenario.getNetwork().getLinks().values()) {
//			DisallowedNextLinks disallowed = NetworkUtils.getDisallowedNextLinks(link);
//			if (disallowed != null) {
//				link.getAllowedModes().forEach(disallowed::removeDisallowedLinkSequences);
//				if (disallowed.isEmpty()) {
//					NetworkUtils.removeDisallowedNextLinks(link);
//				}
//			}
//		}
////		TODO: emissions
	}

	@Override
	protected void prepareControler(Controler controler) {
		//analyse PersonMoneyEvents
//		controler.addOverridingModule(new PersonMoneyEventsAnalysisModule());
//
//		controler.addOverridingModule(new SimWrapperModule());
//
//		controler.addOverridingModule(new AbstractModule() {
//			@Override
//			public void install() {
//				install(new PtFareModule());
//				bind(ScoringParametersForPerson.class).to(IncomeDependentUtilityOfMoneyPersonScoringParameters.class).asEagerSingleton();
//
//				addTravelTimeBinding(TransportMode.ride).to(carTravelTime());
//				addTravelDisutilityFactoryBinding(TransportMode.ride).to(carTravelDisutilityFactoryKey());
//			}
//		});
	}
}
