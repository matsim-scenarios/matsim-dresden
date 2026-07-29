package org.matsim.run.scenarios;

import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import com.google.inject.Singleton;
import com.google.inject.multibindings.Multibinder;
import jakarta.annotation.Nullable;
import org.matsim.analysis.CheckAndSummarizeLongDistanceFreightPopulation;
import org.matsim.analysis.CheckStayHomeAgents;
import org.matsim.analysis.PlansToParquet;
import org.matsim.analysis.WriteDuckDbQueries;
import org.matsim.analysis.personMoney.PersonMoneyEventsAnalysisModule;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.MATSimApplication;
import org.matsim.application.analysis.CheckPopulation;
import org.matsim.analysis.vtts.DresdenAddVttsEtcToActivities;
import org.matsim.application.analysis.traffic.LinkStats;
import org.matsim.application.prepare.CreateLandUseShp;
import org.matsim.application.prepare.counts.CreateCountsFromBAStData;
import org.matsim.application.prepare.longDistanceFreightGER.tripExtraction.ExtractRelevantFreightTrips;
import org.matsim.application.prepare.network.CleanNetwork;
import org.matsim.application.prepare.network.CreateNetworkFromSumo;
import org.matsim.application.prepare.population.*;
import org.matsim.application.prepare.pt.CreateTransitScheduleFromGtfs;
import org.matsim.application.prepare.scenario.CreateScenarioCutOut;
import org.matsim.contrib.vsp.pt.fare.DistanceBasedPtFareParams;
import org.matsim.contrib.vsp.pt.fare.FareZoneBasedPtFareParams;
import org.matsim.contrib.vsp.pt.fare.PtFareConfigGroup;
import org.matsim.contrib.vsp.pt.fare.PtFareModule;
import org.matsim.contrib.vsp.scenario.SnzActivities;
import org.matsim.contrib.vsp.scoring.RideScoringParamsFromCarParams;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.replanning.bestresponse.BestResponseReport;
import org.matsim.replanning.bestresponse.BestResponseScheduleConfigGroup;
import org.matsim.replanning.bestresponse.BestResponseScheduleStrategy;
import org.matsim.replanning.bestresponse.DirichletSamplingConfigGroup;
import org.matsim.replanning.bestresponse.DirichletSamplingStrategy;
import org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.RoutingConfigGroup.AccessEgressType;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.turnRestrictions.DisallowedNextLinks;
import org.matsim.core.population.algorithms.MutateActivityTimeAllocation;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.replanning.annealing.ReplanningAnnealerConfigGroup.AnnealOption;
import org.matsim.core.replanning.annealing.ReplanningAnnealerConfigGroup.AnnealingVariable;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.dashboards.DresdenDashboardProvider;
import org.matsim.prepare.*;
import org.matsim.scoring.DresdenActivityScoring;
import org.matsim.scoring.DresdenScoringConfigGroup;
import org.matsim.scoring.DresdenScoringFunctionFactory;
import org.matsim.simwrapper.DashboardProvider;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.simwrapper.SimWrapperModule;
import org.matsim.smallScaleCommercialTrafficGeneration.GenerateSmallScaleCommercialTrafficDemand;
import org.matsim.smallScaleCommercialTrafficGeneration.prepare.CreateDataDistributionOfStructureData;
import org.matsim.store.PlanSnapshotWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import playground.vsp.scoring.IncomeDependentUtilityOfMoneyPersonScoringParameters;

import java.nio.file.Path;
import java.util.List;

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
		LinkStats.class, CheckPopulation.class, CheckAndSummarizeLongDistanceFreightPopulation.class, CheckStayHomeAgents.class, DresdenAddVttsEtcToActivities.class,
		BestResponseReport.class, PlansToParquet.class
})
public class DresdenModel extends MATSimApplication {

	public static final String VERSION = "v1.1";

	/**
	 * Default length of the simulation period, as a multiple of 24h; overridable with --simulation-period-in-days.
	 * 1.125 (= 27:00) makes the non-wrap-around overnight scoring clamp the last activity at 27:00 instead of 24:00
	 * (see {@link org.matsim.scoring.DresdenActivityScoring} / {@link org.matsim.prepare.EncodeTypicalDuration}).
	 * Exposed so post-hoc analyses (e.g. the VTTS analysis) can reproduce a run's scoring; the value is not
	 * persisted to the output config (the scenario module is not written there), so it must be supplied there.
	 */
	public static final double DEFAULT_SIMULATION_PERIOD_IN_DAYS = 1.125;

	/**
	 * Fallback typical duration (seconds) for the untagged person activity types. Normally overridden per
	 * activity by the "typicalDuration" attribute (see EncodeTypicalDuration / DresdenActivityScoring); only
	 * used for activities that carry no such attribute.
	 */
	private static final double FALLBACK_TYPICAL_DURATION = 2 * 3600;
	private static final Logger log = LoggerFactory.getLogger(DresdenModel.class);

	/**
	 * Wall-clock start of this command, taken at construction, i.e. as early as the model can observe itself.
	 * Threaded into the post-processing so the run can report how long it took as a metric; see
	 * {@link DresdenAddVttsEtcToActivities}. A field initializer rather than a constructor statement so it covers
	 * both constructors.
	 */
	private final long startNanoTime = System.nanoTime();

	@CommandLine.Option(names = "--emissions",
		description = "Define if emission analysis should be performed or not" )
	private EmissionsAnalysisHandling emissions = EmissionsAnalysisHandling.RUN_EMISSIONS_ANALYSIS;

	@CommandLine.Option(names="--emissions-from-iteration")
	private long emissionsFromIteration = 10;

	@CommandLine.Option(names="--with-opening-times")
	private boolean withOpeningTimes = true;

	@CommandLine.Option(names="--simulation-period-in-days",
		description = "Length of the simulation period, as a multiple of 24h. Moves the else-branch overnight " +
			"scoring clamp: handleOvernightActivity scores the (non-wrap-around) last activity from its start to " +
			"simulationPeriodInDays * 24h. Preprocessing (reschedule-late-plans, encode-typical-duration) and " +
			"post-hoc analyses must use the same value.")
	private double simulationPeriodInDays = DEFAULT_SIMULATION_PERIOD_IN_DAYS;

	@CommandLine.Option(names="--mutate-around-initial-end-time-only",
		description = "If set, the TimeAllocationMutator mutates each activity's end time only around its initial " +
			"(feasible, post-preprocessing) value rather than random-walking from the current one, so end times " +
			"cannot drift into -- and get stuck at -- zero-duration corners. Default false (free exploration, which " +
			"demonstrably traps agents at zero-duration activities).")
	private boolean mutateAroundInitialEndTimeOnly = false;

	@CommandLine.Option(names="--best-response-scheduling",
		description = "Experimental: ADD a best-response scheduling strategy that re-plans the whole day at once by " +
			"optimizing a linearized approximation of the activity scoring, then re-routes (see " +
			"org.matsim.replanning.bestresponse). It runs alongside the random TimeAllocationMutator rather than " +
			"replacing it: the mutator keeps exploring locally, the best response supplies the coordinated multi-" +
			"activity moves the mutator cannot reach in one step. Default false.")
	private boolean bestResponseScheduling = false;



	@CommandLine.Option(names="--schedule-delay-scoring",
		description = "Arm the schedule-delay corridor in the scoring: starting later than the stamped initial " +
			"(surveyed) start time is penalized with the lateArrival rate, ending earlier than the stamped initial " +
			"end time with the performing rate (via earlyDeparture). If false (default), the per-activity anchors " +
			"are ignored by the scoring and only the (undefined) type-level trigger times apply, i.e. the terms are " +
			"dead, as before. Default false.")
	private boolean scheduleDelayScoring = false;

	@CommandLine.Option(names="--allow-config-typical-durations",
		description = "Allow person-subpopulation activities without a typicalDuration attribute to score against the " +
			"config typical duration. By default such an activity ABORTS the run: the experiment requires every person " +
			"activity to be scored against its survey-derived typical duration. Pass this only for legacy type-encoded " +
			"populations (v1.1 and earlier), whose typicals live in the activity type names. Default false.")
	private boolean allowConfigTypicalDurations = false;

	@CommandLine.Option(names="--best-response-sigma",
		description = "Standard deviation (seconds) of the random perturbation added to the target end times in the " +
			"best-response scheduling strategy; >0 makes the best response stochastic. Default 0. Only used with " +
			"--best-response-scheduling.")
	private double bestResponseSigma = 0.0;

	@CommandLine.Option(names="--dirichlet-sampling",
		description = "Experimental: REPLACE the TimeAllocationMutator with a Dirichlet schedule sampler: instead of " +
			"randomly perturbing one end time, each replanning draws a whole new feasible day (durations sampled " +
			"from a Dirichlet distribution over the day's time budget; see " +
			"org.matsim.replanning.bestresponse.DirichletScheduleSampler). Bound under the mutator's strategy name, " +
			"so its configured weight and the annealing apply unchanged. Requires a fully duration-based, " +
			"non-wrap-around population; the sampler throws on end-time anchors and wrap-around plans. Default false.")
	private boolean dirichletSampling = false;

	@CommandLine.Option(names="--dirichlet-inverse-temperature",
		description = "Inverse temperature (1/utils, >= 0) of the Dirichlet schedule sampler (see " +
			"--dirichlet-sampling): the sampler draws from the Boltzmann distribution of the Charypar-Nagel " +
			"performing utility, alpha_i = 1 + invTemp * beta_perf * typical_i. 0 samples uniformly among the " +
			"feasible schedules (typical durations ignored); 1 matches the temperature of ChangeExpBeta plan " +
			"selection (default beta = 1/util); larger values approach the deterministic proportional fit of the " +
			"typicals. Default 0.")
	private double dirichletInverseTemperature = 0.0;

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

//		Register the original, untagged Snz activity types (base types with their opening times) plus the
//		_morning and _evening variants that switch off wrap-around scoring. Upstream SnzActivities only offers
//		the duration-tagged variants (one type per duration bin), so we register the untagged types ourselves.
//		The per-activity typical duration is supplied at scoring time by the "typicalDuration" attribute (see
//		EncodeTypicalDuration / DresdenActivityScoring); the typical duration set here is only a fallback for
//		activities that lack the attribute.
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
		// Anchor time mutation to the initial (feasible) end times so it cannot drift activities into zero-duration
		// corners the mutator can then not climb back out of; see --mutate-around-initial-end-time-only.
		config.timeAllocationMutator().setMutateAroundInitialEndTimeOnly(mutateAroundInitialEndTimeOnly);
		config.timeAllocationMutator().setAffectingDuration(false);

		// Best-response scheduling: register its config group. The strategy itself is declared in the config
		// alongside the other person strategies and resolved in configureBestResponseStrategy; the binding is in
		// prepareControler.
		ConfigUtils.addOrGetModule(config, BestResponseScheduleConfigGroup.class).setRandomErrorSigma(bestResponseSigma);

		// Dirichlet schedule sampling: register its config group. The strategy replaces TimeAllocationMutator under
		// its own name when enabled; the binding is in prepareControler.
		ConfigUtils.addOrGetModule(config, DirichletSamplingConfigGroup.class).setInverseTemperature(dirichletInverseTemperature);

//		config.vspExperimental().setVspDefaultsCheckingLevel( VspDefaultsCheckingLevel.abort );

		ScoringConfigGroup scoringConfig = config.scoring();
		scoringConfig.setPerforming_utils_hr( 6.0 );
		scoringConfig.setWriteExperiencedPlans(true);
		scoringConfig.setPathSizeLogitBeta(0.);

//		Schedule-delay corridor (see --schedule-delay-scoring and DresdenActivityScoring): armed, the per-activity
//		anchor times (initialStartTime/initialEndTime, stamped in prepareScenario) act as latestStartTime /
//		earliestEndTime -- the late side keeps the configured lateArrival (-18/h), the early side gets the performing
//		rate as its opportunity cost (ending earlier than the anchored schedule means waiting/untyped time instead of
//		performing). The on/off gate is whether DresdenScoringFunctionFactory adds the DresdenActivityScoring corridor
//		terms at all (via DresdenScoringConfigGroup), NOT in the slopes: zeroing lateArrival would also soften the
//		stuck-agent penalty (abortedPlanScore derives from it) and the best-response scheduler's slopes.
		DresdenScoringConfigGroup dresdenScoring = ConfigUtils.addOrGetModule(config, DresdenScoringConfigGroup.class);
		dresdenScoring.setScheduleDelayScoring(scheduleDelayScoring);
		dresdenScoring.setAllowConfigTypicalDurations(allowConfigTypicalDurations);
		if (scheduleDelayScoring) {
			scoringConfig.setEarlyDeparture_utils_hr(-scoringConfig.getPerforming_utils_hr());
		}

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
		vvo10.setFareZoneShp( "shp/v1.1_vvo_tarifzone_10_dresden_utm32n.shp" ); // DVC-tracked local copy (input/shp.dvc), resolved relative to the config context
		ptFareConfigGroup.addParameterSet( vvo10 );

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

//		Stamp each end-time-based activity's initial start and end time as its stable scheduling anchors. The end
//		anchor is what the initial-anchored time mutator and the best-response scheduling strategy read as the target
//		end time (without it they fall back to the *current* end time, which makes the anchor self-referential:
//		every replanning re-anchors to its own output, and with a stochastic best response (sigma > 0) end times
//		random-walk instead of exploring around a fixed point). Both anchors are what DresdenActivityScoring reads as
//		the per-activity latestStartTime / earliestEndTime of the schedule-delay corridor (armed via
//		--schedule-delay-scoring). Stamping only where absent keeps the original anchors across restarts (output
//		plans carry the attributes) and lets preprocessing override. Only stamped when the schedule-delay corridor
//		is armed: an anchor that nothing will use is not harmless -- the Dirichlet schedule sampler correctly
//		refuses plans carrying end-time anchors -- and the anchored mutator / best-response modes are run together
//		with schedule-delay scoring anyway.
		if (scheduleDelayScoring) {
			stampScheduleAnchors(scenario);
		}

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

	/** See the call site in {@link #prepareScenario(Scenario)}: every end-time-based activity gets its end time (and,
	 * except for the first activity, its start time -- recorded where present, otherwise reconstructed by walking the
	 * chain) stamped as its stable scheduling anchors, unless preprocessing or an earlier run already stamped them.
	 * Duration-based and open activities get no anchors; their position is implied by their anchored neighbours.
	 * Package-visible for testing. */
	static void stampScheduleAnchors(Scenario scenario) {
		for (Person person : scenario.getPopulation().getPersons().values()) {
			for (Plan plan : person.getPlans()) {
				List<Activity> activities = TripStructureUtils.getActivities(plan, TripStructureUtils.StageActivityHandling.ExcludeStageActivities);
				List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(plan);
				double clock = 0;
				for (int i = 0; i < activities.size(); i++) {
					Activity act = activities.get(i);
					if (i > 0) {
						double travel = 0;
						if (i - 1 < trips.size()) {
							for (Leg leg : trips.get(i - 1).getLegsOnly()) {
								if (leg.getTravelTime().isDefined()) {
									travel += leg.getTravelTime().seconds();
								} else if (leg.getRoute() != null && leg.getRoute().getTravelTime().isDefined()) {
									travel += leg.getRoute().getTravelTime().seconds();
								}
							}
						}
						clock += travel;
					}
					double start = act.getStartTime().orElse(clock);
					if (act.getEndTime().isDefined()) {
						stampIfAbsent(act, MutateActivityTimeAllocation.INITIAL_END_TIME_ATTRIBUTE, act.getEndTime().seconds());
						if (i > 0) {
							stampIfAbsent(act, DresdenActivityScoring.INITIAL_START_TIME_ATTRIBUTE, start);
						}
						clock = act.getEndTime().seconds();
					} else if (act.getMaximumDuration().isDefined()) {
						clock = start + act.getMaximumDuration().seconds();
					} else {
						clock = start;
					}
				}
			}
		}
	}

	private static void stampIfAbsent(Activity act, String attribute, double value) {
		if (act.getAttributes().getAttribute(attribute) == null) {
			act.getAttributes().putAttribute(attribute, value);
		}
	}

	/** Fraction of the run's last iteration after which best-response scheduling switches off. Below the global
	 * replanning.fractionOfIterationsToDisableInnovation (0.9) on purpose: the best response should pull agents out of
	 * the corners the mutator cannot escape early on, then get out of the way so the run settles into its stochastic
	 * equilibrium under the usual strategies rather than being held at a near-deterministic schedule. */
	private static final double BEST_RESPONSE_DISABLE_FRACTION = 0.7;

	/**
	 * Resolve the best-response scheduling strategy that the config declares alongside the other person strategies:
	 * drop it unless the run enables it, and otherwise switch it off after {@link #BEST_RESPONSE_DISABLE_FRACTION} of
	 * the run's last iteration. Runs from {@link #prepareControler} because the last iteration is only final there --
	 * {@code MATSimApplication} applies the {@code --config:...} overrides after {@code prepareConfig}, and that is how
	 * the run targets set {@code controller.lastIteration} (prepare-config.xml still says 500). The strategy settings
	 * reach the {@code StrategyManager}, which the injector builds inside {@code controler.run()}, i.e. after this.
	 */
	private void configureBestResponseStrategy(Config config) {
		List<ReplanningConfigGroup.StrategySettings> declared = config.replanning().getStrategySettings().stream()
			.filter(settings -> BestResponseScheduleStrategy.STRATEGY_NAME.equals(settings.getStrategyName()))
			.toList();
		if (!bestResponseScheduling) {
			declared.forEach(config.replanning()::removeParameterSet);
			return;
		}
		int lastIteration = config.controller().getLastIteration();
		int disableAfter = (int) Math.round(BEST_RESPONSE_DISABLE_FRACTION * lastIteration);
		declared.forEach(settings -> settings.setDisableAfter(disableAfter));
		log.info("Best-response scheduling enabled: {} strategy setting(s), disabled after iteration {} (fraction {} of "
				 + "last iteration {}); innovation overall stops at fraction {}.",
			declared.size(), disableAfter, BEST_RESPONSE_DISABLE_FRACTION, lastIteration,
			config.replanning().getFractionOfIterationsToDisableInnovation());
	}

	@Override
	protected void prepareControler(Controler controler) {
		configureBestResponseStrategy(controler.getConfig());

		//analyse PersonMoneyEvents
		controler.addOverridingModule(new PersonMoneyEventsAnalysisModule());

		// controler.addOverridingModule(new SimWrapperModule());

		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				install(new PtFareModule());
				bind(ScoringParametersForPerson.class).to(IncomeDependentUtilityOfMoneyPersonScoringParameters.class).in( Singleton.class );

//				score activities against the plan-derived typical duration stored on each activity (see
//				EncodeTypicalDuration), instead of encoding the typical duration in the activity type.
				bindScoringFunctionFactory().to(DresdenScoringFunctionFactory.class);

//				best-response scheduling strategy, selected via --best-response-scheduling; the binding is
//				harmless when unused (a strategy is only instantiated if referenced by a strategysettings entry).
				addPlanStrategyBinding(BestResponseScheduleStrategy.STRATEGY_NAME).toProvider(BestResponseScheduleStrategy.class);

//				Dirichlet schedule sampling (--dirichlet-sampling) replaces the TimeAllocationMutator: bound under
//				the mutator's own strategy name, so the weight/subpopulation declared in prepare-config.xml and the
//				annealing keep applying. Unlike the best-response binding above this one MUST be conditional --
//				binding the name at all is what overrides the default mutator.
				if (dirichletSampling) {
					addPlanStrategyBinding(DefaultPlanStrategiesModule.DefaultStrategy.TimeAllocationMutator)
						.toProvider(DirichletSamplingStrategy.class);
				}

				addTravelTimeBinding(TransportMode.ride).to(carTravelTime());
				addTravelDisutilityFactoryBinding(TransportMode.ride).to(carTravelDisutilityFactoryKey());

				DresdenDashboardProvider dashboardProvider = new DresdenDashboardProvider(emissions);

				Multibinder.newSetBinder( binder(), DashboardProvider.class ).addBinding().toInstance( dashboardProvider );
			}
		});
		controler.addControllerListener(new PlanSnapshotWriter());
	}

	/**
	 * Run the VTTS analysis as a post-processing step of every run, so its outputs -- including the metrics.json that
	 * tracks the zero-duration-activity count as a DVC metric -- land directly in the run output folder (owned by the
	 * "run" DVC stage). No separate make/DVC stage. simulationPeriodInDays is threaded in because it is not persisted to
	 * the output config the analysis reloads. The vtts-v1.1 make target stays as a standalone backport for old runs.
	 */
	@Override
	protected List<MATSimAppCommand> preparePostProcessing(Path outputFolder, String runId) {
		return List.of(
			new DresdenAddVttsEtcToActivities(outputFolder, runId, simulationPeriodInDays, startNanoTime),
			new PlansToParquet(outputFolder, runId, Controler.DefaultFiles.population.getFilename()),
			new PlansToParquet(outputFolder, runId, Controler.DefaultFiles.experiencedPlans.getFilename()),
			new WriteDuckDbQueries(outputFolder)
		);
	}

}
