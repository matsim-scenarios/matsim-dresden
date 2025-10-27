/* *********************************************************************** *
 * project: org.matsim.*
 * Controler.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2007 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.dashboards;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.application.ApplicationUtils;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.simwrapper.Dashboard;
import org.matsim.simwrapper.SimWrapper;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.simwrapper.dashboard.EmissionsDashboard;
import org.matsim.simwrapper.dashboard.NoiseDashboard;
import org.matsim.simwrapper.dashboard.TripDashboard;
import org.matsim.vehicles.MatsimVehicleWriter;
import picocli.CommandLine;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.matsim.prepare.PrepareNetwork.prepareEmissionsAttributes;
import static org.matsim.utils.DresdenUtils.prepareVehicleTypesForEmissionAnalysis;
import static org.matsim.utils.DresdenUtils.setEmissionsConfigs;

@CommandLine.Command(
	name = "simwrapper",
	description = "Run additional analysis and create SimWrapper dashboard for existing run output."
)
public final class DresdenSimWrapperRunner implements MATSimAppCommand {
	private static final Logger log = LogManager.getLogger(DresdenSimWrapperRunner.class);

	@CommandLine.Parameters(arity = "1..*", description = "Path to run output directories for which dashboards are to be generated.")
	private List<Path> inputPaths;
	@CommandLine.Mixin
	private final ShpOptions shp = new ShpOptions();
	@CommandLine.Option(names = "--noise", defaultValue = "false", description = "create noise dashboard")
	private boolean noise;
	@CommandLine.Option(names = "--trips", defaultValue = "false", description = "create trips dashboard")
	private boolean trips;
	@CommandLine.Option(names = "--emissions", defaultValue = "false", description = "create emission dashboard")
	private boolean emissions;

	private static final String FILE_TYPE = "_before_emissions.xml";


	public DresdenSimWrapperRunner(){
//		public constructor needed for testing purposes.
	}

	public static void main(String[] args) {
		new DresdenSimWrapperRunner().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		if (!noise && !trips && !emissions){
			throw new IllegalArgumentException("you have not configured any dashboard to be created! Please use command line parameters!");
		}

		for (Path runDirectory : inputPaths) {
			log.info("Running on {}", runDirectory);

			String configPath = ApplicationUtils.matchInput("config.xml", runDirectory).toString();
			Config config = ConfigUtils.loadConfig(configPath);
			SimWrapper sw = SimWrapper.create(config);

			SimWrapperConfigGroup simwrapperCfg = ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class);
			if (shp.isDefined()){
				simwrapperCfg.get("").setShp(shp.getShapeFile());
			}
			//skip default dashboards
			simwrapperCfg.setDefaultDashboards(SimWrapperConfigGroup.Mode.disabled);

			//add dashboards according to command line parameters
//			if more dashboards are to be added here, we need to check if noise==true before adding noise dashboard here
			if (noise) {
				sw.addDashboard(Dashboard.customize(new NoiseDashboard(config.global().getCoordinateSystem())).context("noise"));
			}

			if (trips) {
				sw.addDashboard(new TripDashboard(
					"lausitz_mode_share.csv",
					"lausitz_mode_share_per_dist.csv",
					"lausitz_mode_users.csv")
					.withGroupedRefData("lausitz_mode_share_per_group_dist_ref.csv", "age", "economic_status", "income")
					.withDistanceDistribution("lausitz_mode_share_distance_distribution.csv"));
			}

			if (emissions) {
				sw.addDashboard(Dashboard.customize(new EmissionsDashboard(config.global().getCoordinateSystem())).context("emissions"));

				setEmissionsConfigs(config);

				String networkPath = ApplicationUtils.matchInput("output_network.xml.gz", runDirectory).toString();
				String vehiclesPath = ApplicationUtils.matchInput("output_vehicles.xml.gz", runDirectory).toString();
				String transitVehiclesPath = ApplicationUtils.matchInput("output_transitVehicles.xml.gz", runDirectory).toString();
				String populationPath = ApplicationUtils.matchInput("output_plans.xml.gz", runDirectory).toString();

				config.network().setInputFile(networkPath);
				config.vehicles().setVehiclesFile(vehiclesPath);
				config.transit().setVehiclesFile(transitVehiclesPath);
				config.plans().setInputFile(populationPath);

				Scenario scenario = ScenarioUtils.loadScenario(config);

//				adapt network and veh types for emissions analysis like in LausitzScenario base run class
				prepareEmissionsAttributes(scenario.getNetwork());
				prepareVehicleTypesForEmissionAnalysis(scenario);

//				write outputs with adapted files.
//				original output files need to be overwritten as AirPollutionAnalysis searches for "config.xml".
//				copy old files to separate files
				Files.copy(Path.of(configPath), getUniqueTargetPath(Path.of(configPath.split(".xml")[0] + FILE_TYPE)));
				Files.copy(Path.of(networkPath), getUniqueTargetPath(Path.of(networkPath.split(".xml")[0] + FILE_TYPE + ".gz")));
				Files.copy(Path.of(vehiclesPath), getUniqueTargetPath(Path.of(vehiclesPath.split(".xml")[0] + FILE_TYPE + ".gz")));
				Files.copy(Path.of(transitVehiclesPath), getUniqueTargetPath(Path.of(transitVehiclesPath.split(".xml")[0] + FILE_TYPE + ".gz")));

				ConfigUtils.writeConfig(config, configPath);
				NetworkUtils.writeNetwork(scenario.getNetwork(), networkPath);
				new MatsimVehicleWriter(scenario.getVehicles()).writeFile(vehiclesPath);
				new MatsimVehicleWriter(scenario.getTransitVehicles()).writeFile(transitVehiclesPath);
			}

			try {
				sw.generate(runDirectory, true);
				sw.run(runDirectory);
			} catch (IOException e) {
				throw new InterruptedIOException();
			}
		}

		return 0;
	}

	private static Path getUniqueTargetPath(Path targetPath) {
		int counter = 1;
		Path uniquePath = targetPath;

		// Add a suffix if the file already exists
		while (Files.exists(uniquePath)) {
			String originalPath = targetPath.toString();
			int dotIndex = originalPath.lastIndexOf(".");
			if (dotIndex == -1) {
				uniquePath = Path.of(originalPath + "_" + counter);
			} else {
				uniquePath = Path.of(originalPath.substring(0, dotIndex) + "_" + counter + originalPath.substring(dotIndex));
			}
			counter++;
		}

		return uniquePath;
	}
}
