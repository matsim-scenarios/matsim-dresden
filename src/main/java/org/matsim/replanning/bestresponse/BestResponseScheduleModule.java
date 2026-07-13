package org.matsim.replanning.bestresponse;

import org.matsim.core.config.groups.GlobalConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.population.algorithms.PlanAlgorithm;
import org.matsim.core.replanning.modules.AbstractMultithreadedModule;

/**
 * Wraps {@link BestResponseSchedulePlanAlgorithm} into a {@link org.matsim.api.core.v01.replanning.PlanStrategyModule}
 * so it can be used for replanning, with multithreading handled by {@link AbstractMultithreadedModule}. A fresh
 * algorithm instance is created per worker thread with a thread-local RNG, so it is thread-safe. The {@link ScheduleSolver}
 * is stateless and shared.
 */
public final class BestResponseScheduleModule extends AbstractMultithreadedModule {

	private final ScoringConfigGroup scoringConfigGroup;
	private final double randomErrorSigma;
	private final ScheduleSolver solver = new SeparableDurationScheduleSolver();

	public BestResponseScheduleModule( GlobalConfigGroup globalConfigGroup, ScoringConfigGroup scoringConfigGroup, double randomErrorSigma ) {
		super( globalConfigGroup );
		this.scoringConfigGroup = scoringConfigGroup;
		this.randomErrorSigma = randomErrorSigma;
	}

	@Override
	public PlanAlgorithm getPlanAlgoInstance() {
		return new BestResponseSchedulePlanAlgorithm( scoringConfigGroup, solver, randomErrorSigma, MatsimRandom.getLocalInstance() );
	}
}
