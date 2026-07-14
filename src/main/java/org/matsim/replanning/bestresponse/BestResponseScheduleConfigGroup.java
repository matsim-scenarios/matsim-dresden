package org.matsim.replanning.bestresponse;

import org.matsim.core.config.ReflectiveConfigGroup;

/**
 * Config for the {@link BestResponseScheduleStrategy}. Kept deliberately small: the penalty slopes are derived from the
 * configured Charypar-Nagel scoring parameters at runtime (see {@link BestResponseSchedulePlanAlgorithm}), so the only
 * genuinely new parameter here is the scale of the random error added to the target end times that turns the
 * deterministic best response into a stochastic one.
 */
public final class BestResponseScheduleConfigGroup extends ReflectiveConfigGroup {

	public static final String GROUP_NAME = "bestResponseSchedule";

	/** Standard deviation (seconds) of the random perturbation added to each target end time. Default 0 = deterministic. */
	private double randomErrorSigma = 0.0;

	public BestResponseScheduleConfigGroup() {
		super( GROUP_NAME );
	}

	@StringGetter("randomErrorSigma")
	public double getRandomErrorSigma() {
		return randomErrorSigma;
	}

	@StringSetter("randomErrorSigma")
	public void setRandomErrorSigma( double randomErrorSigma ) {
		this.randomErrorSigma = randomErrorSigma;
	}
}
