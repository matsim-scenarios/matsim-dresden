package org.matsim.replanning.bestresponse;

import com.google.inject.Inject;
import com.google.inject.Provider;
import org.matsim.core.config.groups.GlobalConfigGroup;
import org.matsim.core.config.groups.ScenarioConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.replanning.PlanStrategy;
import org.matsim.core.replanning.PlanStrategyImpl;
import org.matsim.core.replanning.modules.ReRoute;
import org.matsim.core.replanning.selectors.RandomPlanSelector;
import org.matsim.core.router.TripRouter;
import org.matsim.core.utils.timing.TimeInterpretation;
import org.matsim.facilities.ActivityFacilities;

/**
 * A best-response replanning {@link PlanStrategy} that runs <em>alongside</em> {@code TimeAllocationMutator}: rather
 * than randomly perturbing activity end times, it re-plans the whole day consistently by solving a linearized
 * scheduling problem (see {@link BestResponseSchedulePlanAlgorithm} / {@link ScheduleProblem}), and then re-routes.
 * <p>
 * The two do different jobs, which is why this does not replace the mutator. The mutator perturbs each end time
 * independently within its mutation range; leaving a zero-duration corner needs a coordinated move across several
 * activities at once, whose joint probability decays geometrically with the number of activities -- so agents with
 * long chains sit there indefinitely. This strategy supplies that coordinated move in one step, while the mutator
 * keeps supplying the local noise the stochastic equilibrium is built on.
 * <p>
 * It is declared in the config next to the other person strategies (see {@code input/prepare-config.xml}) and bound in
 * {@code DresdenModel.prepareControler} with:
 * <pre>
 *   addPlanStrategyBinding( BestResponseScheduleStrategy.STRATEGY_NAME ).toProvider( BestResponseScheduleStrategy.class );
 * </pre>
 * {@code DresdenModel.configureBestResponseStrategy} drops that declaration unless the run enables it, and otherwise
 * sets the iteration after which it switches off.
 * <p>
 * The selector is a {@link RandomPlanSelector}: the strategy copies the selected plan (for its activity sequence and
 * reported travel times) and overwrites the schedule, so which plan is copied matters little.
 */
public final class BestResponseScheduleStrategy implements Provider<PlanStrategy> {

	public static final String STRATEGY_NAME = "BestResponseSchedule";

	@Inject private GlobalConfigGroup globalConfigGroup;
	@Inject private ScoringConfigGroup scoringConfigGroup;
	@Inject private ScenarioConfigGroup scenarioConfigGroup;
	@Inject private BestResponseScheduleConfigGroup bestResponseScheduleConfigGroup;
	@Inject private ActivityFacilities facilities;
	@Inject private Provider<TripRouter> tripRouterProvider;
	@Inject private TimeInterpretation timeInterpretation;

	@Override
	public PlanStrategy get() {
		PlanStrategyImpl strategy = new PlanStrategyImpl( new RandomPlanSelector<>() );
		double dayEnd = scenarioConfigGroup.getSimulationPeriodInDays() * 24. * 3600.;
		strategy.addStrategyModule( new BestResponseScheduleModule(
			globalConfigGroup, scoringConfigGroup, bestResponseScheduleConfigGroup.getRandomErrorSigma(), dayEnd ) );
		// Re-route after re-timing. The scheduler treats each trip's travel time as a constant, which is exact for the
		// teleported modes, near enough for car (congestion varies smoothly over the time of day), and plain wrong for
		// pt, where travel time is a step function of departure time -- miss the connection by a second and the trip
		// takes an hour longer. Without this module the plan would also be internally inconsistent for pt: the activity
		// times move while the leg keeps the transit route (line, departure) it was assigned for the old schedule. The
		// re-routing does not make the scheduler's decision right -- it optimized against stale pt travel times -- but
		// it makes the proposed plan self-consistent, so the mobsim executes and the scoring prices what the agent
		// would actually experience, and a proposal that turns out worse is simply dropped from the plan memory.
		strategy.addStrategyModule( new ReRoute( facilities, tripRouterProvider, globalConfigGroup, timeInterpretation ) );
		return strategy;
	}
}
