package org.matsim.run.scenarios;

import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import com.google.inject.Singleton;
import com.google.inject.multibindings.Multibinder;
import jakarta.annotation.Nullable;
import org.matsim.analysis.CheckAndSummarizeLongDistanceFreightPopulation;
import org.matsim.analysis.CheckStayHomeAgents;
import org.matsim.analysis.personMoney.PersonMoneyEventsAnalysisModule;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.application.MATSimApplication;
import org.matsim.application.analysis.CheckPopulation;
import org.matsim.application.analysis.traffic.LinkStats;
import org.matsim.application.prepare.CreateLandUseShp;
import org.matsim.application.prepare.counts.CreateCountsFromBAStData;
import org.matsim.application.prepare.longDistanceFreightGER.tripExtraction.ExtractRelevantFreightTrips;
import org.matsim.application.prepare.network.CleanNetwork;
import org.matsim.application.prepare.network.CreateNetworkFromSumo;
import org.matsim.application.prepare.population.*;
import org.matsim.application.prepare.pt.CreateTransitScheduleFromGtfs;
import org.matsim.application.prepare.scenario.CreateScenarioCutOut;
import org.matsim.contrib.common.conventions.vsp.SnzActivities;
import org.matsim.contrib.vsp.pt.fare.DistanceBasedPtFareParams;
import org.matsim.contrib.vsp.pt.fare.FareZoneBasedPtFareParams;
import org.matsim.contrib.vsp.pt.fare.PtFareConfigGroup;
import org.matsim.contrib.vsp.pt.fare.PtFareModule;
import org.matsim.contrib.vsp.scenario.Activities;
import org.matsim.contrib.vsp.scoring.RideScoringParamsFromCarParams;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.PlanInheritanceConfigGroup;
import org.matsim.core.config.groups.RoutingConfigGroup.AccessEgressType;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.turnRestrictions.DisallowedNextLinks;
import org.matsim.core.replanning.annealing.ReplanningAnnealerConfigGroup.AnnealOption;
import org.matsim.core.replanning.annealing.ReplanningAnnealerConfigGroup.AnnealingVariable;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.dashboards.DresdenDashboardProvider;
import org.matsim.prepare.*;
import org.matsim.scoring.DresdenScoringConfigGroup;
import org.matsim.scoring.DresdenScoringFunctionFactory;
import org.matsim.simwrapper.DashboardProvider;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.simwrapper.SimWrapperModule;
import org.matsim.smallScaleCommercialTrafficGeneration.GenerateSmallScaleCommercialTrafficDemand;
import org.matsim.smallScaleCommercialTrafficGeneration.prepare.CreateDataDistributionOfStructureData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import playground.vsp.scoring.IncomeDependentUtilityOfMoneyPersonScoringParameters;

import static java.lang.Double.*;
import static org.matsim.run.scenarios.DresdenUtils.*;

@CommandLine.Command(header = ":: Dresden Scenario ::", version = DresdenModel.VERSION, mixinStandardHelpOptions = true)
@MATSimApplication.Prepare({
		CreateNetworkFromSumo.class, CreateTransitScheduleFromGtfs.class, TrajectoryToPlans.class, GenerateShortDistanceTrips.class,
		MergePopulations.class, ExtractRelevantFreightTrips.class, DownSamplePopulation.class, ExtractHomeCoordinates.class,
		CreateLandUseShp.class, ResolveGridCoordinates.class, FixSubtourModes.class, AdjustActivityToLinkDistances.class, XYToLinks.class,
		CleanNetwork.class, PrepareNetwork.class, SplitActivityTypesDuration.class, CreateCountsFromBAStData.class,
		CutOutDresdenPopulation.class, CreateDataDistributionOfStructureData.class, GenerateSmallScaleCommercialTrafficDemand.class,
		PreparePopulation.class, RescheduleLatePlans.class, SplitWrapAroundActivities.class, EndTimeToDuration.class, EncodeTypicalDuration.class, CreateFacilitiesFromPopulation.class, CreateSingleTransportModePopulation.class, RemoveVehicleInformationFromPopulation.class,
		CreateScenarioCutOut.class
})
@MATSimApplication.Analysis({
		LinkStats.class, CheckPopulation.class, CheckAndSummarizeLongDistanceFreightPopulation.class, CheckStayHomeAgents.class
})
public class DresdenModel extends MATSimApplication {

	public static final String VERSION = "v1.1";

	/**
	 * Default length of the simulation period, as a multiple of 24h; overridable with --simulation-period-in-days.
	 * 1.125 (= 27:00) makes the non-wrap-around overnight scoring clamp the last activity at 27:00 instead of 24:00
	 * (see {@link org.matsim.prepare.EncodeTypicalDuration}).
	 */
	public static final double DEFAULT_SIMULATION_PERIOD_IN_DAYS = 1.125;

	/**
	 * Fallback typical duration (seconds) for the untagged person activity types. Normally overridden per
	 * activity by the "typicalDuration" attribute (see EncodeTypicalDuration / DresdenScoringFunctionFactory); only
	 * used for activities that carry no such attribute.
	 */
	private static final double FALLBACK_TYPICAL_DURATION = 2 * 3600;
	private static final Logger log = LoggerFactory.getLogger(DresdenModel.class);

	@CommandLine.Option(names = "--emissions",
		description = "Define if emission analysis should be performed or not" )
	private EmissionsAnalysisHandling emissions = EmissionsAnalysisHandling.RUN_EMISSIONS_ANALYSIS;

	@CommandLine.Option(names="--emissions-from-iteration")
	private long emissionsFromIteration = 10;

	@CommandLine.Option(names="--with-opening-times",
		description = "Give the activity types the made-up opening times of the Snz conventions. Default false: the " +
			"per-activity typical durations carry the schedule, so the opening times only distort it.")
	private boolean withOpeningTimes = false;

	@CommandLine.Option(names="--simulation-period-in-days",
		description = "Length of the simulation period, as a multiple of 24h. Moves the else-branch overnight " +
			"scoring clamp: handleOvernightActivity scores the (non-wrap-around) last activity from its start to " +
			"simulationPeriodInDays * 24h. Preprocessing (reschedule-late-plans, encode-typical-duration) must use " +
			"the same value.")
	private double simulationPeriodInDays = DEFAULT_SIMULATION_PERIOD_IN_DAYS;

	@CommandLine.Option(names="--allow-config-typical-durations",
		description = "Allow person-subpopulation activities without a typicalDuration attribute to score against the " +
			"config typical duration. By default such an activity ABORTS the run: every person activity is to be " +
			"scored against its survey-derived typical duration. Pass this only for legacy type-encoded populations " +
			"(v1.1 and earlier), whose typicals live in the activity type names. Default false.")
	private boolean allowConfigTypicalDurations = false;

//	TODO: remove before release
//	@CommandLine.Option(names="--ride-alpha", description = "alpha value for ride. For calibration only! To be removed before release.")
	private final double rideAlpha = 1.;

	public DresdenModel(){}

	/**
	 * This constructor is useful to pass a typed config rather than non-typed cl args.  E.g. for testing.
	 */
	public DresdenModel( Config config ) {
		super( config );
	}

	public static void main(String[] args) {
		MATSimApplication.execute(DresdenModel.class, args);
	}

	protected void addScoringParams( Config config ) {
		// yyyy need to find a way to remove the existing scoring params; then this can be programmed without inheritance

//		Register the original, untagged Snz activity types plus the _morning and _evening variants that switch off
//		wrap-around scoring. Upstream SnzActivities only offers the duration-tagged variants (one type per duration
//		bin), so we register the untagged types ourselves. The per-activity typical duration is supplied at scoring
//		time by the "typicalDuration" attribute (see EncodeTypicalDuration / DresdenScoringFunctionFactory); the
//		typical duration set here is only a fallback for activities that lack the attribute.
		for (SnzActivities value : SnzActivities.values()) {
			if (withOpeningTimes) {
				log.info("with opening times");
				config.scoring().addActivityParams(value.apply(new ScoringConfigGroup.ActivityParams(value.name()).setTypicalDuration(FALLBACK_TYPICAL_DURATION)));
			} else {
				log.info("without opening times");
				config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams(value.name()).setTypicalDuration(FALLBACK_TYPICAL_DURATION));
			}
//			morning/evening variants deliberately without opening times, matching SnzActivities.addMorningEveningScoringParams.
			config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams(SnzActivities.createMorningActivityType(value.name())).setTypicalDuration(FALLBACK_TYPICAL_DURATION));
			config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams(SnzActivities.createEveningActivityType(value.name())).setTypicalDuration(FALLBACK_TYPICAL_DURATION));
		}
	}

	@Nullable
	@Override
	protected Config prepareConfig(Config config) {

		// Add all activity types with time bins
		this.addScoringParams( config );

		//		add simwrapper config module
		ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class).defaultParams().setContext("").setMapCenter("14.5,51.53").setMapZoomLevel(6.8)
				   .setShp("https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/dresden/dresden-v1.1/shp/v1.1_vvo_tarifzone_10_dresden_utm32n.shp");
//		(the tarifzone shp file basically is a dresden shp file with fare prices as additional information)

		config.timeAllocationMutator().setLatestActivityEndTime(String.valueOf(config.qsim().getEndTime().seconds()));
		config.timeAllocationMutator().setMutateAroundInitialEndTimeOnly(false);
		config.timeAllocationMutator().setAffectingDuration(false);

//		config.vspExperimental().setVspDefaultsCheckingLevel( VspDefaultsCheckingLevel.abort );

		ScoringConfigGroup scoringConfig = config.scoring();
		scoringConfig.setPerforming_utils_hr( 6.0 );
		scoringConfig.setWriteExperiencedPlans(true);
		scoringConfig.setPathSizeLogitBeta(0.);
//		write score explanations into person attrs for each person
		config.scoring().setExplainScores(true);

//		also enable plan inheritance analysis
		PlanInheritanceConfigGroup planInheritanceConfigGroup = ConfigUtils.addOrGetModule(config, PlanInheritanceConfigGroup.class);
		planInheritanceConfigGroup.setEnabled(true);

//		Record in the output config whether activities without a typicalDuration attribute are tolerated; see
//		--allow-config-typical-durations and DresdenScoringFunctionFactory.
		ConfigUtils.addOrGetModule(config, DresdenScoringConfigGroup.class)
			.setAllowConfigTypicalDurations(allowConfigTypicalDurations);

//		Move the else-branch overnight scoring clamp away from 24:00. handleOvernightActivity
//		scores the (non-wrap-around) last activity from its start to simulationPeriodInDays * 24h;
//		the default 1.125 * 24h = 27:00. See --simulation-period-in-days.
		config.scenario().setSimulationPeriodInDays( simulationPeriodInDays );

		prepareCommercialTrafficConfig(config);

		RideScoringParamsFromCarParams.setRideScoringParamsBasedOnCarParams(scoringConfig, rideAlpha );

		config.qsim().setUsingTravelTimeCheckInTeleportation(true);
		config.qsim().setUsePersonIdForMissingVehicleId(false);

		// this is the default
		config.routing().setAccessEgressType( AccessEgressType.accessEgressModeToLink );

//		configure annealing params
		config.replanningAnnealer().setActivateAnnealingModule(true);
		config.replanningAnnealer().addAnnealingVariable( new AnnealingVariable().setAnnealType(
			AnnealOption.sigmoid ).setEndValue(0.01 ).setHalfLife(0.5 ).setShapeFactor(0.01 ).setStartValue(0.45 ).setDefaultSubpopulation("person" ) );

		//		set pt fare calc model to fareZoneBased = fare of vvo tarifzonen are paid for trips within fare zones
//		every other trip: Deutschlandtarif
//		for more info see PTFareModule / ChainedPtFareCalculator classes in vsp contrib
		PtFareConfigGroup ptFareConfigGroup = ConfigUtils.addOrGetModule(config, PtFareConfigGroup.class);

//		pt fare for single ticket in tarifzone 10 dresden was 3 eu in 2023.
// 		see: https://dawo-dresden.de/2024/03/20/bus-und-bahnfahren-ab-1-april-teurer/?utm_source=chatgpt.com
//		pt single ticket fare 2021 = fare 2023 / inflationFactor (see below) = 3eu / 1.16 ~ 2.6 eu
//		fare prices for vvo tarifzone 10 aka Dresden have to be set in shp file.
		FareZoneBasedPtFareParams vvo10 = new FareZoneBasedPtFareParams();
		vvo10.setTransactionPartner( "VVO Tarifzone 10 Dresden" );
		vvo10.setDescription( "VVO Tarifzone 10 Dresden" );
		vvo10.setOrder( 1 );
		vvo10.setFareZoneShp( "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/dresden/dresden-v1.1/shp/v1.1_vvo_tarifzone_10_dresden_utm32n.shp" );
		ptFareConfigGroup.addParameterSet( vvo10 );

//		GERMAN_WIDE_FARE_2024 is a shared static singleton. Deflating it in place would compound the factor
//		once per model setup in the same JVM (the test suite sets up several), so copy it and deflate the copy.
		DistanceBasedPtFareParams germanWide2024 = DistanceBasedPtFareParams.GERMAN_WIDE_FARE_2024;
		DistanceBasedPtFareParams germany = new DistanceBasedPtFareParams();
		germany.setMinFare( germanWide2024.getMinFare() );
		germany.setTransactionPartner( "Deutschlandtarif" );
		germany.setDescription( "Deutschlandtarif" );
		germany.setOrder( 2 );
		ptFareConfigGroup.addParameterSet( germany );

//		apply inflation factor to distance based fare. fare values are from 10.12.23 / for the whole of 2024.
//		car cost in this scenario is projected to 2021. Hence, we deflate the pt cost to 2021
//		according to https://www-genesis.destatis.de/genesis/online?sequenz=tabelleErgebnis&selectionname=61111-0001&startjahr=1991#abreadcrumb (same source as for car cost inflation in google drive)
//		Verbraucherpreisindex 2021 to 2024: 103.1 to 119.3 = 16.2 = inflationFactor of 1.16
//		pt distance cost 2021: cost = (m*distance + b) / inflationFactor = m * inflationFactor * distance + b * inflationFactor
//		ergo: slope2021 = slope2024/inflationFactor and intercept2021 = intercept2024/inflationFactor
		double inflationFactor = 1.16;
		for ( double maxDistance : new double[] { 100_000., POSITIVE_INFINITY } ) {
			DistanceBasedPtFareParams.DistanceClassLinearFareFunctionParams fare2024 = germanWide2024.getOrCreateDistanceClassFareParams( maxDistance );
			DistanceBasedPtFareParams.DistanceClassLinearFareFunctionParams fare2021 = germany.getOrCreateDistanceClassFareParams( maxDistance );
			fare2021.setFareSlope( fare2024.getFareSlope() / inflationFactor );
			fare2021.setFareIntercept( fare2024.getFareIntercept() / inflationFactor );
		}

		setExplicitIntermodalityParamsForWalkToPt(ConfigUtils.addOrGetModule(config, SwissRailRaptorConfigGroup.class));

		if (emissions == EmissionsAnalysisHandling.RUN_EMISSIONS_ANALYSIS) {
			setEmissionsConfigs(config);
		}
		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {
		//		add freight modes of DresdenUtils to network.
//		this happens in the makefile pipeline already, but we do it here anyways, in case somebody uses a preliminary network.
		PrepareNetwork.prepareFreightNetwork(scenario.getNetwork());

//		Splitting the first and last act of the day into separate _morning and _evening act types (to switch off
//		wrap-around scoring) is now done during population preparation, see the split-wrap-around-activities step.

//		remove disallowed links. The disallowed links cause many problems and (usually) are not useful in our rather macroscopic view on transport systems.
		// yyyy I have no idea what this means; could someone please explain?  kai, dec'25
		// --> The way this reads to me is that we may have a network where the "disallowedNextLinks" attribute is used.    In the
		// code that follows here, we disable those attributes.  kai, dec'25
		for (Link link : scenario.getNetwork().getLinks().values()) {
			DisallowedNextLinks disallowed = NetworkUtils.getDisallowedNextLinks(link);
			if (disallowed != null) {
				link.getAllowedModes().forEach(disallowed::removeDisallowedLinkSequences);
				if (disallowed.isEmpty()) {
					NetworkUtils.removeDisallowedNextLinks(link);
					// yyyy whey do we only do this if disallowed is empty, and not in all cases?  kai, dec'25
				}
			}
		}

		if (emissions == EmissionsAnalysisHandling.RUN_EMISSIONS_ANALYSIS) {
//			prepare hbefa link attributes + make link.getType() handable for OsmHbefaMapping
//			this also happens in makefile pipeline. integrating it here for same reason as above.
			PrepareNetwork.prepareEmissionsAttributes(scenario.getNetwork());
//			prepare vehicle types for emission analysis
			prepareVehicleTypesForEmissionAnalysis(scenario);
		}
	}

	@Override
	protected void prepareControler(Controler controler) {
		//analyse PersonMoneyEvents
		controler.addOverridingModule(new PersonMoneyEventsAnalysisModule());

		controler.addOverridingModule(new SimWrapperModule());

		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				install(new PtFareModule());
				bind(ScoringParametersForPerson.class).to(IncomeDependentUtilityOfMoneyPersonScoringParameters.class).in( Singleton.class );

//				score activities against the plan-derived typical duration stored on each activity (see
//				EncodeTypicalDuration), instead of encoding the typical duration in the activity type.
				bindScoringFunctionFactory().to(DresdenScoringFunctionFactory.class);

				addTravelTimeBinding(TransportMode.ride).to(carTravelTime());
				addTravelDisutilityFactoryBinding(TransportMode.ride).to(carTravelDisutilityFactoryKey());

				DresdenDashboardProvider dashboardProvider = new DresdenDashboardProvider(emissions);

				Multibinder.newSetBinder( binder(), DashboardProvider.class ).addBinding().toInstance( dashboardProvider );
			}
		});
	}

}
