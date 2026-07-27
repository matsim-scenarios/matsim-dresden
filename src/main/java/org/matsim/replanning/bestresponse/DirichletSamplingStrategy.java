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
 * A schedule-sampling replanning {@link PlanStrategy} that <em>replaces</em> {@code TimeAllocationMutator}: instead of
 * randomly perturbing one end time, each replanning draws a whole new feasible day from a Dirichlet distribution (see
 * {@link DirichletScheduleSampler}). It is deliberately bound under the mutator's own strategy name (see
 * {@code DresdenModel.prepareControler}), so the strategy weight, subpopulation and annealing configured for
 * {@code TimeAllocationMutator} apply unchanged.
 * <p>
 * Like the mutator -- and unlike {@link BestResponseScheduleStrategy} -- it does not re-route: the proposal keeps the
 * plan's routes, and pt plans are momentarily inconsistent until the next re-route, exactly as with the mutator.
 * <p>
 * Only valid for fully duration-based, non-wrap-around populations; the sampler throws otherwise (fail fast rather
 * than silently ignoring anchors).
 */
public final class DirichletSamplingStrategy implements Provider<PlanStrategy> {

	@Inject private GlobalConfigGroup globalConfigGroup;
	@Inject private ScoringConfigGroup scoringConfigGroup;
	@Inject private ScenarioConfigGroup scenarioConfigGroup;
	@Inject private DirichletSamplingConfigGroup dirichletSamplingConfigGroup;
	@Inject private ActivityFacilities facilities;
	@Inject private Provider<TripRouter> tripRouterProvider;
	@Inject private TimeInterpretation timeInterpretation;

	@Override
	public PlanStrategy get() {
		PlanStrategyImpl strategy = new PlanStrategyImpl( new RandomPlanSelector<>() );
		double dayEnd = scenarioConfigGroup.getSimulationPeriodInDays() * 24. * 3600.;
		strategy.addStrategyModule( new DirichletSamplingModule(
			globalConfigGroup, scoringConfigGroup, dirichletSamplingConfigGroup.getInverseTemperature(), dayEnd ) );
		strategy.addStrategyModule( new ReRoute( facilities, tripRouterProvider, globalConfigGroup, timeInterpretation ) );
		return strategy;
	}
}
