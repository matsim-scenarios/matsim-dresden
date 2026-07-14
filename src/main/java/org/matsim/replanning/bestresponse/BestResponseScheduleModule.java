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
	private final double dayEnd;
	private final ScheduleSolver solver = new LpScheduleSolver();

	public BestResponseScheduleModule( GlobalConfigGroup globalConfigGroup, ScoringConfigGroup scoringConfigGroup,
									   double randomErrorSigma, double dayEnd ) {
		super( globalConfigGroup );
		this.scoringConfigGroup = scoringConfigGroup;
		this.randomErrorSigma = randomErrorSigma;
		this.dayEnd = dayEnd;
	}

	@Override
	public PlanAlgorithm getPlanAlgoInstance() {
		return new BestResponseSchedulePlanAlgorithm( scoringConfigGroup, solver, randomErrorSigma, dayEnd, MatsimRandom.getLocalInstance() );
	}
}
