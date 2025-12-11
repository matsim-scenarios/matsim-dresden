package org.matsim.run.scenarios;

import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.matsim.analysis.personMoney.PersonMoneyEventsAnalysisModule;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.utils.DresdenUtils;
import org.matsim.utils.gis.shp2matsim.ShpGeometryUtils;
import picocli.CommandLine;
import playground.vsp.simpleParkingCostHandler.ParkingCostConfigGroup;
import playground.vsp.simpleParkingCostHandler.ParkingCostModule;

import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.matsim.utils.DresdenUtils.ONE_HOUR_P_COST;

public class DresdenScenarioWithParkingCost extends DresdenScenario {

	@CommandLine.Option(names = "--parking-cost-area", description = "Path to SHP file specifying area with parking cost")
	private Path parkingCostArea;

	@CommandLine.Option(names = "--one-hour-parking-cost", description = "Cost for the first hour of parking")
	private Double oneHourParkingCost;

	@CommandLine.Option(names = "--extra-hour-parking-cost", description = "Cost for each additional hour of parking")
	private Double extraHourParkingCost;

	@CommandLine.Option(names = "residential-parking-cost", description = "Daily residential parking cost")
	private Double residentialParkingCost;



	public static void main(String[] args) {
		org.matsim.application.MATSimApplication.run(DresdenScenarioWithParkingCost.class, args);
	}


	@Override
	@Nullable
	protected Config prepareConfig(Config config) {
		super.prepareConfig(config);
		//add parking cost module
		ParkingCostConfigGroup  parkingCostConfigGroup = ConfigUtils.addOrGetModule(config, ParkingCostConfigGroup.class);
		parkingCostConfigGroup.setFirstHourParkingCostLinkAttributeName(ONE_HOUR_P_COST);
		parkingCostConfigGroup.setExtraHourParkingCostLinkAttributeName(DresdenUtils.EXTRA_HOUR_P_COST);
		parkingCostConfigGroup.setMaxDailyParkingCostLinkAttributeName(DresdenUtils.MAX_DAILY_P_COST);
		parkingCostConfigGroup.setMaxParkingDurationAttributeName(DresdenUtils.MAX_P_TIME);
		parkingCostConfigGroup.setParkingPenaltyAttributeName(DresdenUtils.P_FINE);
		parkingCostConfigGroup.setResidentialParkingFeeAttributeName(DresdenUtils.RES_P_COSTS);
		parkingCostConfigGroup.setActivityPrefixForDailyParkingCosts("home");

		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {
		super.prepareScenario(scenario);

		//add parking cost to network
		addParkingCostToNetwork(scenario.getNetwork(), ShpGeometryUtils.loadPreparedGeometries(IOUtils.resolveFileOrResource(new ShpOptions(parkingCostArea, null, null).getShapeFile().toString()))
			, oneHourParkingCost, extraHourParkingCost, residentialParkingCost);

	}



	@Override
	protected void prepareControler(Controler controler) {
		super.prepareControler(controler);
		//this is needed for  the parking cost
		controler.addOverridingModule(new ParkingCostModule());
		//analyse PersonMoneyEvents
		controler.addOverridingModule(new PersonMoneyEventsAnalysisModule());
	}


	/**
	 * Add parking cost attributes to links within the specified area.
	 * @param network               the network to modify
	 * @param parkingCostArea       list of prepared geometries defining the area with parking cost
	 * @param oneHourParkingCost    cost for the first hour of parking
	 * @param extraHourParkingCost  cost for each additional hour of parking
	 * @param residentialParkingCost daily residential parking cost
	 */
	private void addParkingCostToNetwork(Network network, List<PreparedGeometry> parkingCostArea, Double oneHourParkingCost, Double extraHourParkingCost, Double residentialParkingCost) {
		if (parkingCostArea != null) {
			if (oneHourParkingCost == null || extraHourParkingCost == null || residentialParkingCost == null) {
				throw new IllegalArgumentException("Parking cost values must be provided when parking cost area is specified.");
			} else {
				Set<? extends Link> linksInParkingArea = network.getLinks().values().stream()
					//filter car links
					.filter(link -> link.getAllowedModes().contains(TransportMode.car))
					//spatial filter
					.filter(link -> ShpGeometryUtils.isCoordInPreparedGeometries(link.getCoord(), parkingCostArea))
					//we won't add parking cost to motorways and motorway_links and trunk
					.filter(link -> !((String) link.getAttributes().getAttribute("type")).contains("motorway"))
					.filter(link -> !((String) link.getAttributes().getAttribute("type")).contains("trunk"))
					.collect(Collectors.toSet());

				linksInParkingArea.forEach(link -> {
					link.getAttributes().putAttribute(ONE_HOUR_P_COST, oneHourParkingCost);
					link.getAttributes().putAttribute(DresdenUtils.EXTRA_HOUR_P_COST, extraHourParkingCost);
					link.getAttributes().putAttribute(DresdenUtils.RES_P_COSTS, residentialParkingCost);
					}
				);
			}
		}
	}

}
