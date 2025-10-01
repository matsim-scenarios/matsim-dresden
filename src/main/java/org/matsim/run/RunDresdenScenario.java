package org.matsim.run;

import org.matsim.application.MATSimApplication;
import org.matsim.run.scenarios.DresdenScenario;

/**
 * Run the Dresden scenario with default configuration.
 */
public final class RunDresdenScenario {
	private RunDresdenScenario() {

	}

	public static void main(String[] args) {
		MATSimApplication.runWithDefaults(DresdenScenario.class, args);
	}
}
