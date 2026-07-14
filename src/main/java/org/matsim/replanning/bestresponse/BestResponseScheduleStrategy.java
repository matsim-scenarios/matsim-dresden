package org.matsim.replanning.bestresponse;

import com.google.inject.Inject;
import com.google.inject.Provider;
import org.matsim.core.config.groups.GlobalConfigGroup;
import org.matsim.core.config.groups.ScenarioConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.replanning.PlanStrategy;
import org.matsim.core.replanning.PlanStrategyImpl;
import org.matsim.core.replanning.selectors.RandomPlanSelector;

/**
 * A best-response replanning {@link PlanStrategy} meant to replace {@code TimeAllocationMutator}: rather than randomly
 * perturbing one activity end time, it re-plans the whole day consistently by solving a linearized scheduling problem
 * (see {@link BestResponseSchedulePlanAlgorithm} / {@link ScheduleProblem}).
 * <p>
 * Wire it in with, in {@code prepareControler}:
 * <pre>
 *   addPlanStrategyBinding( BestResponseScheduleStrategy.STRATEGY_NAME ).toProvider( BestResponseScheduleStrategy.class );
 * </pre>
 * and reference {@link #STRATEGY_NAME} from a {@code strategysettings} entry (e.g. by renaming the existing
 * {@code TimeAllocationMutator} setting; see {@code DresdenModel} and the {@code --best-response-scheduling} option).
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

	@Override
	public PlanStrategy get() {
		PlanStrategyImpl strategy = new PlanStrategyImpl( new RandomPlanSelector<>() );
		double dayEnd = scenarioConfigGroup.getSimulationPeriodInDays() * 24. * 3600.;
		strategy.addStrategyModule( new BestResponseScheduleModule(
			globalConfigGroup, scoringConfigGroup, bestResponseScheduleConfigGroup.getRandomErrorSigma(), dayEnd ) );
		return strategy;
	}
}
