package org.matsim.replanning.bestresponse;

import org.matsim.core.config.groups.GlobalConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.population.algorithms.PlanAlgorithm;
import org.matsim.core.replanning.modules.AbstractMultithreadedModule;

import java.util.Random;

/**
 * Wraps the {@link DirichletScheduleSampler} into a replanning module. It reuses
 * {@link BestResponseSchedulePlanAlgorithm} for the plan-to-problem extraction and write-back -- only the solving step
 * differs: a Dirichlet draw over the feasible schedules instead of the LP best response. randomErrorSigma is 0
 * because the perturbation only touches end-time anchors, which the sampler rejects anyway.
 * <p>
 * Unlike the {@link LpScheduleSolver}, the sampler carries its own RNG, so a fresh sampler is created per worker
 * thread along with the algorithm instance.
 */
public final class DirichletSamplingModule extends AbstractMultithreadedModule {

	private final ScoringConfigGroup scoringConfigGroup;
	private final double inverseTemperature;
	private final double dayEnd;

	public DirichletSamplingModule( GlobalConfigGroup globalConfigGroup, ScoringConfigGroup scoringConfigGroup,
									double inverseTemperature, double dayEnd ) {
		super( globalConfigGroup );
		this.scoringConfigGroup = scoringConfigGroup;
		this.inverseTemperature = inverseTemperature;
		this.dayEnd = dayEnd;
	}

	@Override
	public PlanAlgorithm getPlanAlgoInstance() {
		Random random = MatsimRandom.getLocalInstance();
		return new BestResponseSchedulePlanAlgorithm( scoringConfigGroup,
			new DirichletScheduleSampler( inverseTemperature, random ), 0., dayEnd, random );
	}
}
