package org.matsim.run.scenarios;

import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import ch.sbb.matsim.config.SwissRailRaptorConfigGroup.IntermodalAccessEgressModeSelection;
import ch.sbb.matsim.config.SwissRailRaptorConfigGroup.IntermodalAccessEgressParameterSet;
import com.google.common.collect.Sets;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.contrib.emissions.HbefaVehicleCategory;
import org.matsim.contrib.emissions.utils.EmissionsConfigGroup;
import org.matsim.contrib.emissions.utils.EmissionsConfigGroup.DetailedVsAverageLookupBehavior;
import org.matsim.contrib.emissions.utils.EmissionsConfigGroup.HbefaTableConsistencyCheckingLevel;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule;
import org.matsim.vehicles.EngineInformation;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.util.HashSet;
import java.util.Set;

import static ch.sbb.matsim.config.SwissRailRaptorConfigGroup.IntermodalAccessEgressModeSelection.*;
import static org.matsim.contrib.vsp.scenario.HbefaDefaultStrings.*;
import static org.matsim.contrib.vsp.scenario.SubpopulationDefaultNames.*;
import static org.matsim.run.scenarios.DresdenUtils.SNZPersonAttributeNames.*;

/**
 * Utils class for Dresden scenario with often used parameters and/or methods.
 */
public final class DresdenUtils {
	public static final String HEAVY_MODE = "truck40t";
	public static final String MEDIUM_MODE = "truck18t";
	public static final String LIGHT_MODE = "truck8t";

	public static final String LONG_DIST_FREIGHT_SUBPOP = "longDistanceFreight";

	private static final String AVERAGE = "average";

	private DresdenUtils() {

	}

	public static Set<String> getSNZPersonAttrNames() {
		return Set.of( HH_INCOME, HOME_REGIOSTAR_17, HH_SIZE, AGE, PT_TICKET, CAR_AVAILABILITY, GENDER);
	}

	public static Set<String> getFreightModes() {
		return Set.of(HEAVY_MODE, MEDIUM_MODE, LIGHT_MODE);
	}

	public static Set<String> getSmallScaleComSubpops() {
		return Set.of( SUBPOP_COM_PERSON, SUBPOP_COM_PERSON_SERVICE, SUBPOP_GOODS );
	}

	public static void setExplicitIntermodalityParamsForWalkToPt(SwissRailRaptorConfigGroup srrConfig) {
		srrConfig.setUseIntermodalAccessEgress(true);
		srrConfig.setIntermodalAccessEgressModeSelection( CalcLeastCostModePerStop );

//			add walk as access egress mode to pt
		IntermodalAccessEgressParameterSet accessEgressWalkParam = new IntermodalAccessEgressParameterSet();
		accessEgressWalkParam.setMode(TransportMode.walk);
//			initial radius for pt stop search
		accessEgressWalkParam.setInitialSearchRadius(10000);
		accessEgressWalkParam.setMaxRadius(100000);
//			with this, initialSearchRadius gets extended by the set value until maxRadius is reached
		accessEgressWalkParam.setSearchExtensionRadius(1000);
		srrConfig.addIntermodalAccessEgress(accessEgressWalkParam);
	}

	public static void setEmissionsConfigs(Config config) {
		EmissionsConfigGroup eConfig = ConfigUtils.addOrGetModule(config, EmissionsConfigGroup.class);
		eConfig.setDetailedColdEmissionFactorsFile( HBEFA_FILE_COLD_DETAILED );
		eConfig.setDetailedWarmEmissionFactorsFile(HBEFA_FILE_WARM_DETAILED);
		eConfig.setAverageColdEmissionFactorsFile(HBEFA_FILE_COLD_AVERAGE);
		eConfig.setAverageWarmEmissionFactorsFile(HBEFA_FILE_WARM_AVERAGE);
		eConfig.setHbefaTableConsistencyCheckingLevel( HbefaTableConsistencyCheckingLevel.consistent );
		eConfig.setDetailedVsAverageLookupBehavior( DetailedVsAverageLookupBehavior.tryDetailedThenTechnologyAverageThenAverageTable );
	}

	/**
	 * Prepare vehicle types with necessary HBEFA information for emission analysis.
	 */
	public static void prepareVehicleTypesForEmissionAnalysis(Scenario scenario) {
		for (VehicleType type : scenario.getVehicles().getVehicleTypes().values()) {
			EngineInformation engineInformation = type.getEngineInformation();

//				only set engine information if none are present
			if (engineInformation.getAttributes().isEmpty()) {
				switch (type.getId().toString()) {
					case TransportMode.car -> {
						VehicleUtils.setHbefaVehicleCategory(engineInformation, HbefaVehicleCategory.PASSENGER_CAR.toString());
//						based on car registrations in germany 2023: 30% petrol, 17% diesel, 30% Hybrid, 18% battery. Thus, average is the choice here.
						VehicleUtils.setHbefaTechnology(engineInformation, AVERAGE);
						VehicleUtils.setHbefaSizeClass(engineInformation, AVERAGE);
						VehicleUtils.setHbefaEmissionsConcept(engineInformation, AVERAGE);
					}
					case TransportMode.ride -> {
//							ignore ride, the mode is routed on network, but then teleported
						VehicleUtils.setHbefaVehicleCategory(engineInformation, HbefaVehicleCategory.NON_HBEFA_VEHICLE.toString());
						VehicleUtils.setHbefaTechnology(engineInformation, AVERAGE);
						VehicleUtils.setHbefaSizeClass(engineInformation, AVERAGE);
						VehicleUtils.setHbefaEmissionsConcept(engineInformation, AVERAGE);
					}
					case TransportMode.bike -> {
//							ignore bikes
						VehicleUtils.setHbefaVehicleCategory(engineInformation, HbefaVehicleCategory.NON_HBEFA_VEHICLE.toString());
						VehicleUtils.setHbefaTechnology(engineInformation, AVERAGE);
						VehicleUtils.setHbefaSizeClass(engineInformation, AVERAGE);
						VehicleUtils.setHbefaEmissionsConcept(engineInformation, AVERAGE);
					}
					case LIGHT_MODE -> {
						VehicleUtils.setHbefaVehicleCategory(engineInformation, HbefaVehicleCategory.LIGHT_COMMERCIAL_VEHICLE.toString());
						VehicleUtils.setHbefaTechnology(engineInformation, "diesel");
						VehicleUtils.setHbefaSizeClass(engineInformation, AVERAGE);
						VehicleUtils.setHbefaEmissionsConcept(engineInformation, AVERAGE);
					}
					case MEDIUM_MODE, HEAVY_MODE -> {
						VehicleUtils.setHbefaVehicleCategory(engineInformation, HbefaVehicleCategory.HEAVY_GOODS_VEHICLE.toString());
						VehicleUtils.setHbefaTechnology(engineInformation, "diesel");
						VehicleUtils.setHbefaSizeClass(engineInformation, AVERAGE);
						VehicleUtils.setHbefaEmissionsConcept(engineInformation, AVERAGE);
					}
					default -> throw new IllegalArgumentException("does not know how to handle vehicleType " + type.getId().toString());
				}
			}
		}

//			ignore all pt veh types
		scenario.getTransitVehicles()
			.getVehicleTypes()
			.values().forEach(type -> VehicleUtils.setHbefaVehicleCategory(type.getEngineInformation(), HbefaVehicleCategory.NON_HBEFA_VEHICLE.toString()));
	}

	/**
	 * original snz attribute names as delivered in personAttributes.xml (shared-svn/projects/agimo).
	 */
	public static final class SNZPersonAttributeNames {
		public static final String HH_INCOME = "hhIncome";
		public static final String HOME_REGIOSTAR_17 = "homeRegioStaR17";
		public static final String HH_SIZE = "hhSize";
		public static final String AGE = "age";
		public static final String PT_TICKET = "ptTicket";
		public static final String CAR_AVAILABILITY = "carAvailability";
		public static final String GENDER = "gender";

		private SNZPersonAttributeNames() { }
	}

	/**
	 * Switch on/off automatic analysis on air pollution emissions.
	 */
	public enum EmissionsAnalysisHandling {RUN_EMISSIONS_ANALYSIS, NO_EMISSIONS_ANALYSIS}

	/**
	 * Switch on/off analysis on noise emissions and creation of noise dashboard.
	 */
	public enum NoiseAnalysisHandling {RUN_NOISE_ANALYSIS, NO_NOISE_ANALYSIS}

	/**
	 * Switch on/off analysis on trips and creation of trips dashboard.
	 */
	public enum TripsAnalysisHandling {RUN_TRIPS_ANALYSIS, NO_TRIPS_ANALYSIS}
	/**
	 * Prepare the config for commercial traffic.
	 */
	public static void prepareCommercialTrafficConfig( Config config ) {

		getFreightModes().forEach(mode -> {
			ScoringConfigGroup.ModeParams thisModeParams = new ScoringConfigGroup.ModeParams(mode);
			config.scoring().addModeParams(thisModeParams);
		});

		Set<String> qsimModes = new HashSet<>(config.qsim().getMainModes());
		config.qsim().setMainModes( Sets.union(qsimModes, getFreightModes() ) );
		config.routing().setNetworkModes(Sets.union( new HashSet<>( config.routing().getNetworkModes() ), getFreightModes() ) );

		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("commercial_start").setTypicalDuration(30 * 60.));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("commercial_end").setTypicalDuration(30 * 60.));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("service").setTypicalDuration(30 * 60.));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("start").setTypicalDuration(30 * 60.));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("end").setTypicalDuration(30 * 60.));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("freight_start").setTypicalDuration(30 * 60.));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("freight_end").setTypicalDuration(30 * 60.));

//		replanning strategies for small scale commercial traffic
		for (String subpopulation : getSmallScaleComSubpops()) {

			config.replanning().addStrategySettings( new ReplanningConfigGroup.StrategySettings()
					.setStrategyName( DefaultPlanStrategiesModule.DefaultSelector.ChangeExpBeta )
					.setWeight(0.85)
					.setSubpopulation(subpopulation)
			);

			config.replanning().addStrategySettings( new ReplanningConfigGroup.StrategySettings()
					.setStrategyName( DefaultPlanStrategiesModule.DefaultStrategy.ReRoute )
					.setWeight(0.1)
					.setSubpopulation(subpopulation)
			);
		}

//		replanning strategies for longDistanceFreight
		config.replanning().addStrategySettings( new ReplanningConfigGroup.StrategySettings()
				.setStrategyName(DefaultPlanStrategiesModule.DefaultSelector.ChangeExpBeta)
				.setWeight(0.95)
				.setSubpopulation(LONG_DIST_FREIGHT_SUBPOP)
		);
		config.replanning().addStrategySettings( new ReplanningConfigGroup.StrategySettings()
				.setStrategyName( DefaultPlanStrategiesModule.DefaultStrategy.ReRoute )
				.setWeight(0.05)
				.setSubpopulation(LONG_DIST_FREIGHT_SUBPOP)
		);

//		analyze travel times for all qsim main modes
		config.travelTimeCalculator().setAnalyzedModes(Sets.union(qsimModes, getFreightModes()));

	}
}
