package org.matsim.run.scenarios;

import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimApplication;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.config.Config;
import org.matsim.core.controler.Controler;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.utils.gis.shp2matsim.ShpGeometryUtils;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.List;


/**
 * Dresden scenario with option to reduce car capacities in certain area.
 */
public class DresdenWithCarCapacitiesReduced extends DresdenScenario {

	@CommandLine.Option(names = "--reduced-capacity-area", description = "Path to SHP file where the capacity of the transport mode car is reduced.")
	private Path reducedCapacityArea;


	public static void main(String[] args) {
		MATSimApplication.run(DresdenWithCarCapacitiesReduced.class, args);
	}


	@Override
	@Nullable
	protected Config prepareConfig(Config config) {
		return super.prepareConfig(config);
	}

	@Override
	protected void prepareScenario(Scenario scenario) {
		super.prepareScenario(scenario);
		reduceCapacitiesCar(scenario.getNetwork(),ShpGeometryUtils.loadPreparedGeometries(IOUtils.resolveFileOrResource(new ShpOptions(reducedCapacityArea, null, null).getShapeFile().toString())) );
	}


	@Override
	protected void prepareControler(Controler controler) {
		super.prepareControler(controler);
	}


	/**
	 * Reduce the capacities of car links by 50% in the specified area, excluding motorways and trunks.
	 *
	 * @param network    the network to modify
	 * @param areaFilter list of prepared geometries defining the area where capacities should be reduced
	 */
	private static void reduceCapacitiesCar(Network network, List<PreparedGeometry> areaFilter) {
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
