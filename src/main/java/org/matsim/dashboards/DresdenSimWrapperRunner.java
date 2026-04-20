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
import org.matsim.run.scenarios.DresdenUtils;
import org.matsim.vehicles.MatsimVehicleWriter;
import picocli.CommandLine;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.matsim.prepare.PrepareNetwork.prepareEmissionsAttributes;
import static org.matsim.run.scenarios.DresdenUtils.prepareVehicleTypesForEmissionAnalysis;
import static org.matsim.run.scenarios.DresdenUtils.setEmissionsConfigs;

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
	@CommandLine.Option(names = "--noise", defaultValue = "NO_NOISE_ANALYSIS", description = "create noise dashboard")
	private DresdenUtils.NoiseAnalysisHandling noise;
	@CommandLine.Option(names = "--trips", defaultValue = "NO_TRIPS_ANALYSIS", description = "create trips dashboard")
	private DresdenUtils.TripsAnalysisHandling trips;
	@CommandLine.Option(names = "--emissions", defaultValue = "NO_EMISSIONS_ANALYSIS", description = "create emission dashboard. Options: RUN_EMISSIONS_ANALYSIS, NO_EMISSIONS_ANALYSIS")
	DresdenUtils.EmissionsAnalysisHandling emissions;

	public static final String BEFORE = "_before_emissions.xml";
	public static final String AFTER = "_after_emissions.xml";
	public static final String XML = ".xml";


	public DresdenSimWrapperRunner(){
//		public constructor needed for testing purposes.
	}

	public static void main(String[] args) {
		new DresdenSimWrapperRunner().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		if (noise == DresdenUtils.NoiseAnalysisHandling.NO_NOISE_ANALYSIS && trips == DresdenUtils.TripsAnalysisHandling.NO_TRIPS_ANALYSIS &&
			emissions == DresdenUtils.EmissionsAnalysisHandling.NO_EMISSIONS_ANALYSIS){
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
			simwrapperCfg.setDefaultDashboards(SimWrapperConfigGroup.DefaultDashboardsMode.disabled);

			//add dashboards according to command line parameters
//			if more dashboards are to be added here, we need to check if noise==true before adding noise dashboard here
			if (noise == DresdenUtils.NoiseAnalysisHandling.RUN_NOISE_ANALYSIS) {
				sw.addDashboard(Dashboard.customize(new NoiseDashboard(config.global().getCoordinateSystem())).context("noise"));
			}

			if (trips == DresdenUtils.TripsAnalysisHandling.RUN_TRIPS_ANALYSIS) {
				sw.addDashboard(Dashboard.customize(new TripDashboard(
					"mode_share_ref.csv",
					"mode_share_per_dist_ref.csv",
					"mode_users_ref.csv")
					.withGroupedRefData("mode_share_per_group_dist_ref.csv", "age", "economic_status", "income", "employment")
					.withDistanceDistribution("mode_share_distance_distribution.csv")
					.setAnalysisArgs("--person-filter", "subpopulation=person")).context("calibration").title("Trips (calibration)"));
			}

//			we need to define the following paths outside of the following if clause because we need to use them later on.
			String networkPath = ApplicationUtils.matchInput("*output_network.xml*", runDirectory).toString();
			String vehiclesPath = ApplicationUtils.matchInput("*output_vehicles.xml*", runDirectory).toString();
			String transitVehiclesPath = ApplicationUtils.matchInput("*output_transitVehicles.xml*", runDirectory).toString();
			String populationPath = ApplicationUtils.matchInput("*output_plans.xml*", runDirectory).toString();
			Path beforeEmissionsConfigPath = getUniqueTargetPath(Path.of(configPath.split(XML)[0] + BEFORE));
			Path beforeEmissionsNetworkPath = getUniqueTargetPath(Path.of(networkPath.split(XML)[0] + BEFORE + ".gz"));
			Path beforeEmissionsVehiclesPath = getUniqueTargetPath(Path.of(vehiclesPath.split(XML)[0] + BEFORE + ".gz"));
			Path beforeEmissionsTransitVehiclesPath = getUniqueTargetPath(Path.of(transitVehiclesPath.split(XML)[0] + BEFORE + ".gz"));

			if (emissions == DresdenUtils.EmissionsAnalysisHandling.RUN_EMISSIONS_ANALYSIS) {
				sw.addDashboard(Dashboard.customize(new EmissionsDashboard(config.global().getCoordinateSystem())).context("emissions"));

				setEmissionsConfigs(config);

				config.network().setInputFile(networkPath);
				config.vehicles().setVehiclesFile(vehiclesPath);
				config.transit().setVehiclesFile(transitVehiclesPath);
				config.plans().setInputFile(populationPath);

				Scenario scenario = ScenarioUtils.loadScenario(config);

//				adapt network and veh types for emissions analysis like in LausitzScenario base run class
				prepareEmissionsAttributes(scenario.getNetwork());
				prepareVehicleTypesForEmissionAnalysis(scenario);

//			write outputs with adapted files
// 			original output files need to be overwritten as AirPollutionAnalysis searches for "config.xml".
//			We will copy the original output files back to their old file names later. very clunky, but I see no alternative, if we want to keep our run output consistent.
//			copy old files to separate files
				Files.copy(Path.of(configPath), beforeEmissionsConfigPath);
				Files.copy(Path.of(networkPath), beforeEmissionsNetworkPath);
				Files.copy(Path.of(vehiclesPath), beforeEmissionsVehiclesPath);
				Files.copy(Path.of(transitVehiclesPath), beforeEmissionsTransitVehiclesPath);

//			now we can write the prepared output to the usual output file paths.
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

			if (emissions == DresdenUtils.EmissionsAnalysisHandling.RUN_EMISSIONS_ANALYSIS) {
//			after finishing the emissions analysis we can
//			1) copy the transformed files to paths with _after_emissions suffix
//			2) copy the original files from paths with suffix _before_emissions to original paths
//			3) delete the files with _before_emissions suffix.
				Path afterEmissionsConfigPath = getUniqueTargetPath(Path.of(configPath.split(XML)[0] + AFTER));
				Path afterEmissionsNetworkPath = getUniqueTargetPath(Path.of(networkPath.split(XML)[0] + AFTER + ".gz"));
				Path afterEmissionsVehiclesPath = getUniqueTargetPath(Path.of(vehiclesPath.split(XML)[0] + AFTER + ".gz"));
				Path afterEmissionsTransitVehiclesPath = getUniqueTargetPath(Path.of(transitVehiclesPath.split(XML)[0] + AFTER + ".gz"));
				Files.copy(Path.of(configPath), afterEmissionsConfigPath);
				Files.copy(Path.of(networkPath), afterEmissionsNetworkPath);
				Files.copy(Path.of(vehiclesPath), afterEmissionsVehiclesPath);
				Files.copy(Path.of(transitVehiclesPath), afterEmissionsTransitVehiclesPath);
				Files.copy(beforeEmissionsConfigPath, Path.of(configPath), StandardCopyOption.REPLACE_EXISTING);
				Files.copy(beforeEmissionsNetworkPath, Path.of(networkPath), StandardCopyOption.REPLACE_EXISTING);
				Files.copy(beforeEmissionsVehiclesPath, Path.of(vehiclesPath), StandardCopyOption.REPLACE_EXISTING);
				Files.copy(beforeEmissionsTransitVehiclesPath, Path.of(transitVehiclesPath), StandardCopyOption.REPLACE_EXISTING);
				Files.delete(beforeEmissionsConfigPath);
				Files.delete(beforeEmissionsNetworkPath);
				Files.delete(beforeEmissionsVehiclesPath);
				Files.delete(beforeEmissionsTransitVehiclesPath);
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
