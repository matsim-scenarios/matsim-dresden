package org.matsim.run.scenarios;

import com.google.common.collect.Sets;
import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.config.Config;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.utils.gis.shp2matsim.ShpGeometryUtils;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehiclesFactory;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.*;

public class DresdenWithEBikeCity extends DresdenScenario {


	@CommandLine.Option(names = "--e-bike-city-area", description = "Path to SHP file specifying the e-bike city area, where the capacity of the transport mode car is reduced.")
	private Path eBikeCityArea;

	public static void main(String[] args) {
		org.matsim.application.MATSimApplication.run(DresdenWithEBikeCity.class, args);
	}

	String E_BIKE_MODE = "ebike";


	@Override
	@Nullable
	protected Config prepareConfig(Config config) {
		 super.prepareConfig(config);

		//add new e-bike mode to scenario

		ScoringConfigGroup.ModeParams eBikeParams = new ScoringConfigGroup.ModeParams(E_BIKE_MODE);
		eBikeParams.setDailyUtilityConstant(0.0);
		eBikeParams.setMonetaryDistanceRate(0.0);
		eBikeParams.setDailyMonetaryConstant(-1.0); //daily cost for using an e-bike TODO think about realistic value here
		config.scoring().addModeParams(eBikeParams);


		//configure e-bike as a bike for routing purposes
		Set<String> modes = Sets.newHashSet(E_BIKE_MODE);
		modes.addAll(config.qsim().getMainModes());
		config.qsim().setMainModes(modes);
		config.qsim().setLinkDynamics(QSimConfigGroup.LinkDynamics.PassingQ);

		//add e-bike as a network mode
		Collection<String> networkModes = Sets.newHashSet(E_BIKE_MODE);
		networkModes.addAll(config.routing().getNetworkModes());
		config.routing().setNetworkModes(networkModes);

		//add e-bike to the replanning modes for subtour mode choice
		Collection<String> replanningModes = Sets.newHashSet(E_BIKE_MODE);
		replanningModes.addAll(List.of(config.subtourModeChoice().getModes()));
		config.subtourModeChoice().setModes(
			replanningModes.toArray(new String[0])
		);

		//add e-bike as chain based mode for subtour mode choice
		Set<String> chainModes = new HashSet<>(Arrays.asList(config.subtourModeChoice().getChainBasedModes()));
		chainModes.add(E_BIKE_MODE);
		config.subtourModeChoice().setChainBasedModes(chainModes.toArray(new String[0]));
		return config;
	}


	@Override
	protected void prepareScenario(Scenario scenario) {
		super.prepareScenario(scenario);
		VehiclesFactory vehiclesFactory = scenario.getVehicles().getFactory();

		VehicleType ebike = vehiclesFactory.createVehicleType(Id.createVehicleTypeId(E_BIKE_MODE));
		ebike.setNetworkMode(E_BIKE_MODE);
		//max allowed speed in germany for e-bikes is 25 km/h
		ebike.setMaximumVelocity(25.0 / 3.6);
		//get rest from bike parameters
		ebike.setPcuEquivalents(scenario.getVehicles().getVehicleTypes().get(Id.createVehicleTypeId(TransportMode.bike)).getPcuEquivalents());
		ebike.setLength(scenario.getVehicles().getVehicleTypes().get(Id.createVehicleTypeId(TransportMode.bike)).getLength());
		ebike.setWidth(scenario.getVehicles().getVehicleTypes().get(Id.createVehicleTypeId(TransportMode.bike)).getWidth());
		ebike.setFlowEfficiencyFactor(scenario.getVehicles().getVehicleTypes().get(Id.createVehicleTypeId(TransportMode.bike)).getFlowEfficiencyFactor());

		scenario.getVehicles().addVehicleType(ebike);

		scenario.getPopulation().getPersons().values().forEach(person -> {
			Vehicle ebikeForEveryAgent = vehiclesFactory.createVehicle(
				Id.createVehicleId(person.getId() + "_ebike"),
				ebike
			);
			scenario.getVehicles().addVehicle(ebikeForEveryAgent);
		});

		for (Link link : scenario.getNetwork().getLinks().values()) {
			// if it is a car link bikes can be added
			if (link.getAllowedModes().contains(TransportMode.bike)) {
				//not add bikes to trunk and motorways
				Set<String> allowedModes = Sets.newHashSet(E_BIKE_MODE);
				allowedModes.addAll(link.getAllowedModes());
				link.setAllowedModes(allowedModes);
			}
		}

		applyEBikeCity(scenario.getNetwork(), ShpGeometryUtils.loadPreparedGeometries(IOUtils.resolveFileOrResource(new ShpOptions(eBikeCityArea, null, null).getShapeFile().toString())));

	}


	@Override
	protected void prepareControler(Controler controler) {
		super.prepareControler(controler);
	}

	private static void applyEBikeCity(Network network, List<PreparedGeometry> areaFilter) {
		if (areaFilter == null || areaFilter.isEmpty()) {
			throw new IllegalArgumentException("areaFilter must be provided and not empty.");
		}

		network.getLinks().values().stream()
			// filter only car links
			.filter(link -> link.getAllowedModes().contains(TransportMode.car))
			// spatial filter: link must be inside one of the geometries
			.filter(link -> ShpGeometryUtils.isCoordInPreparedGeometries(link.getCoord(), areaFilter))
			// exclude motorways and trunks
			.filter(link -> {
				String type = (String) link.getAttributes().getAttribute("type");
				return type != null && !type.contains("motorway") && !type.contains("trunk");
			})

			//TODO think about lanes as they are needed for the storage capacity
			.forEach(link -> {
				// reduce capacity
				link.setCapacity(link.getCapacity() * 0.5);

				// reduce lanes if more than 2
				if (link.getNumberOfLanes() > 2.0) {
					link.setNumberOfLanes(link.getNumberOfLanes() * 0.5);
				}
			});
	}

}
