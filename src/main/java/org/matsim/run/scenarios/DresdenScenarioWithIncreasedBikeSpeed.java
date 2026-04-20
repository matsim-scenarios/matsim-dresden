package org.matsim.run.scenarios;

import org.jetbrains.annotations.Nullable;
import org.matsim.api.core.v01.Scenario;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;
import org.matsim.core.controler.Controler;
import org.matsim.vehicles.VehicleType;


/*
 * Dresden scenario with increased bike speed.
 */
public class DresdenScenarioWithIncreasedBikeSpeed extends DresdenScenario {


	public static void main(String[] args) {
		MATSimApplication.run(DresdenScenarioWithIncreasedBikeSpeed.class, args);
	}


	@Override
	@Nullable
	protected Config prepareConfig(Config config) {
		return super.prepareConfig(config);
	}

	@Override
	protected void prepareScenario(Scenario scenario) {
		super.prepareScenario(scenario);
		// increase bike speed by factor 2
		VehicleType bike = scenario.getVehicles().getVehicleTypes().get("bike");
		bike.setMaximumVelocity(bike.getMaximumVelocity() * 2);
	}

	@Override
	protected void prepareControler(Controler controler) {
		super.prepareControler(controler);
	}
}



