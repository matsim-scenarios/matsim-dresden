package org.matsim.run;

import org.matsim.application.MATSimApplication;
import org.matsim.run.scenarios.DresdenScenario;

public class RunDresdenScenario {
	private RunDresdenScenario() {

	}

	public static void main(String[] args) {
		MATSimApplication.runWithDefaults(DresdenScenario.class, args);
	}
}
