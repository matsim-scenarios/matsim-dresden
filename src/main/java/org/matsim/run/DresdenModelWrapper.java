package org.matsim.run;

import org.matsim.application.MATSimApplication;
import org.matsim.run.scenarios.DresdenModel;

/**
 * Run the Dresden model.
 */
public final class DresdenModelWrapper {
	private DresdenModelWrapper() {

	}

	public static void main(String[] args) {
		MATSimApplication.run(DresdenModel.class, args);
	}
}
