package org.matsim.replanning.bestresponse;

import org.matsim.core.config.ReflectiveConfigGroup;

/**
 * Config for the {@link BestResponseScheduleStrategy}. Kept deliberately small for the mockup: the penalty slopes are
 * derived from the configured Charypar-Nagel scoring parameters at runtime (see
 * {@link BestResponseSchedulePlanAlgorithm}), so the only genuinely new parameter here is the scale of the random error
 * that turns the deterministic best response into a stochastic one (as in Pougala et al.).
 */
public final class BestResponseScheduleConfigGroup extends ReflectiveConfigGroup {

	public static final String GROUP_NAME = "bestResponseSchedule";

	/** Standard deviation (seconds) of the per-activity random error added to each preferred duration. */
	private double randomErrorSigma = 900.0;

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
