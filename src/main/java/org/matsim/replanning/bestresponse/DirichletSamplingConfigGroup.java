package org.matsim.replanning.bestresponse;

import org.matsim.core.config.ReflectiveConfigGroup;

/**
 * Config for the {@link DirichletSamplingStrategy}: a single knob, the inverse temperature of the
 * {@link DirichletScheduleSampler}'s Boltzmann distribution over the feasible schedules. 0 samples uniformly (typical
 * durations ignored); 1 samples the Charypar-Nagel performing utility at the temperature of ChangeExpBeta plan
 * selection (default beta = 1/util); larger values approach the deterministic proportional fit of the typical
 * durations (see the sampler's javadoc).
 */
public final class DirichletSamplingConfigGroup extends ReflectiveConfigGroup {

	public static final String GROUP_NAME = "dirichletSampling";

	/** Inverse temperature (1/utils) of the Boltzmann schedule distribution; >= 0, 0 = uniform sampling. */
	private double inverseTemperature = 0.0;

	public DirichletSamplingConfigGroup() {
		super( GROUP_NAME );
	}

	@StringGetter("inverseTemperature")
	public double getInverseTemperature() {
		return inverseTemperature;
	}

	@StringSetter("inverseTemperature")
	public void setInverseTemperature( double inverseTemperature ) {
		this.inverseTemperature = inverseTemperature;
	}
}
