package org.matsim.run.scenarios;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.analysis.CheckAndSummarizeLongDistanceFreightPopulation;
import org.matsim.analysis.CheckStayHomeAgents;
import org.matsim.api.core.v01.Scenario;
import org.matsim.application.MATSimApplication;
import org.matsim.application.analysis.CheckPopulation;
import org.matsim.application.analysis.traffic.LinkStats;
import org.matsim.application.prepare.CreateLandUseShp;
import org.matsim.application.prepare.counts.CreateCountsFromBAStData;
import org.matsim.application.prepare.longDistanceFreightGER.tripExtraction.ExtractRelevantFreightTrips;
import org.matsim.application.prepare.network.CleanNetwork;
import org.matsim.application.prepare.network.CreateNetworkFromSumo;
import org.matsim.application.prepare.population.AdjustActivityToLinkDistances;
import org.matsim.application.prepare.population.DownSamplePopulation;
import org.matsim.application.prepare.population.ExtractHomeCoordinates;
import org.matsim.application.prepare.population.FixSubtourModes;
import org.matsim.application.prepare.population.GenerateShortDistanceTrips;
import org.matsim.application.prepare.population.MergePopulations;
import org.matsim.application.prepare.population.ResolveGridCoordinates;
import org.matsim.application.prepare.population.SplitActivityTypesDuration;
import org.matsim.application.prepare.population.TrajectoryToPlans;
import org.matsim.application.prepare.population.XYToLinks;
import org.matsim.application.prepare.pt.CreateTransitScheduleFromGtfs;
import org.matsim.application.prepare.scenario.CreateScenarioCutOut;
import org.matsim.contrib.vsp.scenario.CornerCases;
import org.matsim.core.config.Config;
import org.matsim.core.controler.Controler;
import org.matsim.prepare.CreateFacilitiesFromPopulation;
import org.matsim.prepare.CreateSingleTransportModePopulation;
import org.matsim.prepare.CutOutDresdenPopulation;
import org.matsim.prepare.PrepareNetwork;
import org.matsim.prepare.PreparePopulation;
import org.matsim.prepare.RemoveVehicleInformationFromPopulation;
import org.matsim.smallScaleCommercialTrafficGeneration.GenerateSmallScaleCommercialTrafficDemand;
import org.matsim.smallScaleCommercialTrafficGeneration.prepare.CreateDataDistributionOfStructureData;
import picocli.CommandLine;
import playground.vsp.simpleParkingCostHandler.ParkingCostModule;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs one of the policy cases provided by {@link CornerCases} on top of the Dresden model.
 *
 * <p>The base case continues to run directly through {@link DresdenModel}. This subclass is used only for policy
 * runs, and every invocation applies exactly one policy to freshly prepared base inputs. Spatial policies require a
 * local shapefile supplied through {@code --policy-shp}, as required by the {@link CornerCases} API.</p>
 */
@CommandLine.Command(header = ":: Dresden Corner Cases ::", version = DresdenModel.VERSION,
		mixinStandardHelpOptions = true)
// MATSimApplication does not inherit these annotations, so the subclass must expose the same commands as the base.
@MATSimApplication.Prepare({
		CreateNetworkFromSumo.class, CreateTransitScheduleFromGtfs.class, TrajectoryToPlans.class,
		GenerateShortDistanceTrips.class, MergePopulations.class, ExtractRelevantFreightTrips.class,
		DownSamplePopulation.class, ExtractHomeCoordinates.class, CreateLandUseShp.class, ResolveGridCoordinates.class,
		FixSubtourModes.class, AdjustActivityToLinkDistances.class, XYToLinks.class, CleanNetwork.class,
		PrepareNetwork.class, SplitActivityTypesDuration.class, CreateCountsFromBAStData.class,
		CutOutDresdenPopulation.class, CreateDataDistributionOfStructureData.class,
		GenerateSmallScaleCommercialTrafficDemand.class, PreparePopulation.class, CreateFacilitiesFromPopulation.class,
		CreateSingleTransportModePopulation.class, RemoveVehicleInformationFromPopulation.class, CreateScenarioCutOut.class
})
@MATSimApplication.Analysis({
		LinkStats.class, CheckPopulation.class, CheckAndSummarizeLongDistanceFreightPopulation.class,
		CheckStayHomeAgents.class
})
public final class DresdenCornerCases extends DresdenModel {

	private static final Logger log = LogManager.getLogger(DresdenCornerCases.class);

	static final String FIRST_HOUR_PARKING_COST_ATTRIBUTE = "firstHourParkingCost";
	static final String EXTRA_HOUR_PARKING_COST_ATTRIBUTE = "extraHourParkingCost";
	static final String RESIDENTIAL_PARKING_FEE_ATTRIBUTE = "residentialParkingFee";

	static final String BIKE_SPEED_FACTOR_ATTRIBUTE = "dresdenPolicyBikeSpeedFactor";

	// CornerCases excludes link types, we define them here.
	static final String DEFAULT_EXCLUDED_ROAD_TYPES =
			"highway.motorway,highway.motorway_link,highway.trunk,highway.trunk_link";

	@CommandLine.Option(names = "--policy", required = true,
			description = "Policy case: ${COMPLETION-CANDIDATES}.")
	private PolicyCase policy;

	@CommandLine.Option(names = "--policy-shp", paramLabel = "<file.shp>",
			description = "Local shape file used by spatial policies.")
	private Path policyShape;

	@CommandLine.Option(names = "--policy-factor", paramLabel = "<factor>",
			description = "Bike speed factor (> 0 and not 1), or car speed/capacity factor in (0, 1).")
	private Double policyFactor;

	@CommandLine.Option(names = "--excluded-road-types", split = ",",
			defaultValue = DEFAULT_EXCLUDED_ROAD_TYPES,
			description = "Exact network type values excluded from CAR_SPEED and CAR_CAPACITY.")
	private String[] excludedRoadTypes = DEFAULT_EXCLUDED_ROAD_TYPES.split(",");

	@CommandLine.Option(names = "--first-hour-parking-cost", paramLabel = "<amount>",
			description = "Parking cost for the first hour.")
	private Double firstHourParkingCost;

	@CommandLine.Option(names = "--extra-hour-parking-cost", paramLabel = "<amount>",
			description = "Parking cost for every additional hour.")
	private Double extraHourParkingCost;

	@CommandLine.Option(names = "--residential-parking-fee", paramLabel = "<amount>",
			description = "Residential parking fee.")
	private Double residentialParkingFee;

	public DresdenCornerCases() {
	}

	/**
	 * Allows tests and programmatic callers to provide a typed MATSim config.
	 */
	public DresdenCornerCases(Config config) {
		super(config);
	}

	public static void main(String[] args) {
		MATSimApplication.execute(DresdenCornerCases.class, args);
	}

	@Override
	protected Config prepareConfig(Config config) {
		// Validate CLI input before the base configuration is mutated. This produces an error for incomplete
		// policy commands instead of failing later during scenario preparation.
		validatePolicyOptions();
		//default config options
		Config prepared = super.prepareConfig(config);

		if (policy == PolicyCase.PARKING_COST) {
			// Parking uses named link attributes. The helper registers those names in the parking config module.
			CornerCases.prepareConfigForParkingCost(prepared);
		}

		log.info("Running Dresden policy {} with shape={}, factor={}, excludedRoadTypes={}",
				policy, policyShape, policyFactor, excludedRoadTypeSet());
		return prepared;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {
		super.prepareScenario(scenario);
		// Apply the corner case only after DresdenModel has prepared network modes, vehicles and emissions data.
		applyPolicy(scenario);
	}

	@Override
	protected void prepareControler(Controler controler) {
		super.prepareControler(controler);

		if (policy == PolicyCase.PARKING_COST) {
			// CornerCases.prepareControllerForParkingCost also installs the money-events
			// analysis, which DresdenModel already installs. Add only the missing module.
			controler.addOverridingModule(new ParkingCostModule());
		}
	}

	/**
	 * Applies exactly one selected policy and verifies that the spatial policies actually touched the network.
	 * Package visibility for testing
	 */
	void applyPolicy(Scenario scenario) {
		validatePolicyOptions();
		// Policy cases are independent experiments.
		rejectPreexistingPolicyMarkers(scenario);

		switch (policy) {
			case BIKE_SPEED -> {
				CornerCases.modifyBikeSpeed(scenario, policyFactor);
				scenario.getNetwork().getAttributes().putAttribute(BIKE_SPEED_FACTOR_ATTRIBUTE, policyFactor);
			}
			case CAR_SPEED -> {
				CornerCases.reduceCarSpeed(scenario, normalizedPolicyShape(), policyFactor, excludedRoadTypeSet());
				verifyModifiedLinks(scenario, CornerCases.SPEED_REDUCTION_FACTOR_ATTRIBUTE);
			}
			case CAR_CAPACITY -> {
				CornerCases.reduceCarCapacities(scenario, normalizedPolicyShape(), policyFactor, excludedRoadTypeSet());
				verifyModifiedLinks(scenario, CornerCases.CAPACITY_REDUCTION_FACTOR_ATTRIBUTE);
			}
			case PARKING_COST -> {
				CornerCases.prepareScenarioForParkingCost(scenario, normalizedPolicyShape(), firstHourParkingCost,
						extraHourParkingCost, residentialParkingFee);
				verifyModifiedLinks(scenario, FIRST_HOUR_PARKING_COST_ATTRIBUTE);
			}
		}
	}

	private void validatePolicyOptions() {
		if (policy == null) {
			throw new IllegalArgumentException("--policy must be set.");
		}

		switch (policy) {
			case BIKE_SPEED -> validatePositiveFactor();
			case CAR_SPEED, CAR_CAPACITY -> {
				validateShape();
				validateReductionFactor();
			}
			case PARKING_COST -> {
				validateShape();
				validateNonNegative("--first-hour-parking-cost", firstHourParkingCost);
				validateNonNegative("--extra-hour-parking-cost", extraHourParkingCost);
				validateNonNegative("--residential-parking-fee", residentialParkingFee);
				if (firstHourParkingCost == 0. && extraHourParkingCost == 0. && residentialParkingFee == 0.) {
					throw new IllegalArgumentException("At least one parking cost must be greater than zero.");
				}
			}
		}
	}

	private void validateShape() {
		if (policyShape == null) {
			throw new IllegalArgumentException("--policy-shp is required for policy " + policy + ".");
		}
		if (!Files.isRegularFile(normalizedPolicyShape())) {
			throw new IllegalArgumentException("Policy shape does not exist or is not a file: " + policyShape);
		}
	}

	private void validatePositiveFactor() {
		if (policyFactor == null || !Double.isFinite(policyFactor) || policyFactor <= 0. || policyFactor == 1.) {
			throw new IllegalArgumentException(
					"--policy-factor must be finite, greater than zero, and different from one for " + policy + ".");
		}
	}

	private void validateReductionFactor() {
		if (policyFactor == null || !Double.isFinite(policyFactor) || policyFactor <= 0. || policyFactor >= 1.) {
			throw new IllegalArgumentException("--policy-factor must be finite and in (0, 1) for " + policy + ".");
		}
	}

	private static void rejectPreexistingPolicyMarkers(Scenario scenario) {
		if (scenario.getNetwork().getAttributes().getAttribute(BIKE_SPEED_FACTOR_ATTRIBUTE) != null) {
			throw new IllegalStateException("The input network already contains Dresden policy markers. "
					+ "Every case must start from the calibrated base inputs.");
		}

		Set<String> linkMarkers = Set.of(
				CornerCases.SPEED_REDUCTION_FACTOR_ATTRIBUTE,
				CornerCases.CAPACITY_REDUCTION_FACTOR_ATTRIBUTE,
				FIRST_HOUR_PARKING_COST_ATTRIBUTE,
				EXTRA_HOUR_PARKING_COST_ATTRIBUTE,
				RESIDENTIAL_PARKING_FEE_ATTRIBUTE
		);
		boolean containsPolicyMarker = scenario.getNetwork().getLinks().values().stream()
				.anyMatch(link -> linkMarkers.stream()
						.anyMatch(marker -> link.getAttributes().getAttribute(marker) != null));
		if (containsPolicyMarker) {
			throw new IllegalStateException("The input network already contains Dresden policy markers. "
					+ "Every case must start from the calibrated base inputs.");
		}
	}

	private static void validateNonNegative(String option, Double value) {
		if (value == null || !Double.isFinite(value) || value < 0.) {
			throw new IllegalArgumentException(option + " must be finite and non-negative.");
		}
	}

	private Path normalizedPolicyShape() {
		return policyShape.toAbsolutePath().normalize();
	}

	private void verifyModifiedLinks(Scenario scenario, String attribute) {
		long modifiedLinks = scenario.getNetwork().getLinks().values().stream()
				.filter(link -> link.getAttributes().getAttribute(attribute) != null)
				.count();
		log.info("Policy {} marked {} network links with attribute {}.", policy, modifiedLinks, attribute);
		// A zero count usually indicates a wrong CRS or an unintended type exclusion. Failing here avoids producing a
		// successfully labelled policy run that is behaviorally identical to the base case.
		if (modifiedLinks == 0) {
			throw new IllegalStateException("Policy " + policy + " did not modify any network link. "
					+ "Check the policy shape CRS and the exact excluded road types.");
		}
	}

	private Set<String> excludedRoadTypeSet() {
		return Arrays.stream(excludedRoadTypes)
				.map(String::trim)
				.filter(value -> !value.isEmpty())
				.collect(Collectors.toUnmodifiableSet());
	}

	public enum PolicyCase {
		BIKE_SPEED,
		CAR_SPEED,
		CAR_CAPACITY,
		PARKING_COST
	}
}
