package org.matsim.replanning.bestresponse;

import org.matsim.core.config.ReflectiveConfigGroup;

/**
 * Config for the {@link DirichletSamplingStrategy}: a single knob, the concentration {@code c} of the
 * {@link DirichletScheduleSampler}. {@code c = 0} samples schedules uniformly from the feasible domain; larger values
 * concentrate the draws around the typical-duration shares of the day (see the sampler's javadoc for the exact
 * parameterization).
 */
public final class DirichletSamplingConfigGroup extends ReflectiveConfigGroup {

	public static final String GROUP_NAME = "dirichletSampling";

	/** Dirichlet concentration c >= 0; 0 = uniform sampling of the feasible schedules (typical durations ignored). */
	private double concentration = 0.0;

	public DirichletSamplingConfigGroup() {
		super( GROUP_NAME );
	}

	@StringGetter("concentration")
	public double getConcentration() {
		return concentration;
	}

	@StringSetter("concentration")
	public void setConcentration( double concentration ) {
		this.concentration = concentration;
	}
}
