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
import org.matsim.contrib.vsp.pt.fare.DistanceBasedPtFareParams;
import org.matsim.contrib.vsp.pt.fare.FareZoneBasedPtFareParams;
import org.matsim.contrib.vsp.pt.fare.PtFareConfigGroup;
import org.matsim.contrib.vsp.pt.fare.PtFareModule;
import org.matsim.contrib.vsp.scenario.SnzActivities;
import org.matsim.contrib.vsp.scoring.RideScoringParamsFromCarParams;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.RoutingConfigGroup.AccessEgressType;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.config.groups.VspExperimentalConfigGroup.VspDefaultsCheckingLevel;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.turnRestrictions.DisallowedNextLinks;
import org.matsim.core.replanning.annealing.ReplanningAnnealerConfigGroup.AnnealOption;
import org.matsim.core.replanning.annealing.ReplanningAnnealerConfigGroup.AnnealingVariable;
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

import static java.lang.Double.*;
import static org.matsim.run.scenarios.DresdenUtils.*;

@CommandLine.Command(header = ":: Dresden Scenario ::", version = DresdenModel.VERSION, mixinStandardHelpOptions = true)
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
public class DresdenModel extends MATSimApplication {

	public static final String VERSION = "v1.0";

	@CommandLine.Option(names = "--emissions", defaultValue = "RUN_EMISSIONS_ANALYSIS",
		description = "Define if emission analysis should be performed or not. Options: RUN_EMISSIONS_ANALYSIS, NO_EMISSIONS_ANALYSIS")
	private EmissionsAnalysisHandling emissions;

	public static void main(String[] args) {
		MATSimApplication.execute(DresdenModel.class, args);
	}

	protected void addScoringParams( Config config ) {
		// yyyy need to find a way to remove the existing scoring params; then this can be programmed without inheritance
		SnzActivities.addScoringParams(config);
	}

	@Nullable
	@Override
	protected Config prepareConfig(Config config) {

		// Add all activity types with time bins
		this.addScoringParams( config );

		//		add simwrapper config module
		ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class).defaultParams().setContext("").setMapCenter("14.5,51.53").setMapZoomLevel(6.8)
				   .setShp(String.format("vvo_tarifzone_10_dresden/%s_vvo_tarifzone_10_dresden_utm32n.shp", VERSION));
//		(the tarifzone shp file basically is a dresden shp file with fare prices as additional information)

		config.timeAllocationMutator().setLatestActivityEndTime(String.valueOf(config.qsim().getEndTime().seconds()));
		config.timeAllocationMutator().setMutateAroundInitialEndTimeOnly(false);
		config.timeAllocationMutator().setAffectingDuration(false);

		config.vspExperimental().setVspDefaultsCheckingLevel( VspDefaultsCheckingLevel.abort );

		ScoringConfigGroup scoringConrig = config.scoring();
		scoringConrig.setPerforming_utils_hr( 6.0 );
		scoringConrig.setWriteExperiencedPlans(true);
		scoringConrig.setPathSizeLogitBeta(0.);

		prepareCommercialTrafficConfig(config);

		RideScoringParamsFromCarParams.setRideScoringParamsBasedOnCarParams(scoringConrig, 2 );

		config.qsim().setUsingTravelTimeCheckInTeleportation(true);
		config.qsim().setUsePersonIdForMissingVehicleId(false);

		config.routing().setAccessEgressType( AccessEgressType.accessEgressModeToLink ); // this is the default

//		configure annealing params
		config.replanningAnnealer().setActivateAnnealingModule(true);
		config.replanningAnnealer().addAnnealingVariable( new AnnealingVariable().setAnnealType(
			AnnealOption.sigmoid ).setEndValue(0.01 ).setHalfLife(0.5 ).setShapeFactor(0.01 ).setStartValue(0.45 ).setDefaultSubpopulation("person" ) );

		//		set pt fare calc model to fareZoneBased = fare of vvo tarifzonen are paid for trips within fare zones
//		every other trip: Deutschlandtarif
//		for more info see PTFareModule / ChainedPtFareCalculator classes in vsp contrib
		PtFareConfigGroup ptFareConfigGroup = ConfigUtils.addOrGetModule(config, PtFareConfigGroup.class);
		{
//		pt fare for single ticket in tarifzone 10 dresden was 3 eu in 2023.
//		see: https://dawo-dresden.de/2024/03/20/bus-und-bahnfahren-ab-1-april-teurer/?utm_source=chatgpt.com
//		pt single ticket fare 2021 = fare 2023 / inflationFactor (see below) = 3eu / 1.16 ~ 2.6 eu
//		fare prices for vvo tarifzone 10 aka Dresden have to be set in shp file.
			FareZoneBasedPtFareParams vvo10 = new FareZoneBasedPtFareParams();
			vvo10.setTransactionPartner( "VVO Tarifzone 10 Dresden" );
			vvo10.setDescription( "VVO Tarifzone 10 Dresden" );
			vvo10.setOrder( 1 );
			vvo10.setFareZoneShp( String.format( "./vvo_tarifzone_10_dresden/%s_vvo_tarifzone_10_dresden_utm32n.shp", VERSION ) );
			ptFareConfigGroup.addParameterSet( vvo10 );
		}
		{
			DistanceBasedPtFareParams germany = DistanceBasedPtFareParams.GERMAN_WIDE_FARE_2024;
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
			DistanceBasedPtFareParams.DistanceClassLinearFareFunctionParams below100km = germany.getOrCreateDistanceClassFareParams( 100_000. );
			below100km.setFareSlope( below100km.getFareSlope() / inflationFactor );
			below100km.setFareIntercept( below100km.getFareIntercept() / inflationFactor );

			DistanceBasedPtFareParams.DistanceClassLinearFareFunctionParams greaterThan100km = germany.getOrCreateDistanceClassFareParams( POSITIVE_INFINITY );
			greaterThan100km.setFareSlope( greaterThan100km.getFareSlope() / inflationFactor );
			greaterThan100km.setFareIntercept( greaterThan100km.getFareIntercept() / inflationFactor );
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

				addTravelTimeBinding(TransportMode.ride).to(carTravelTime());
				addTravelDisutilityFactoryBinding(TransportMode.ride).to(carTravelDisutilityFactoryKey());

				Multibinder.newSetBinder( binder(), DashboardProvider.class ).addBinding().to( DresdenDashboardProvider.class );
			}
		});
	}

}
