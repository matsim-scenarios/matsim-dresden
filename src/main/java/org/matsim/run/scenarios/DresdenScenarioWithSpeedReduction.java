package org.matsim.run.scenarios;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimApplication;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.config.Config;
import org.matsim.core.controler.Controler;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.utils.gis.shp2matsim.ShpGeometryUtils;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Dresden scenario with option to reduce speed in certain area.
 */

public class DresdenScenarioWithSpeedReduction extends DresdenScenario{


	private static final Logger log = LogManager.getLogger(DresdenScenarioWithSpeedReduction.class);

	@CommandLine.Option(names = "--slow-speed-area", description = "Path to SHP file specifying area of adapted speed")
	private Path slowSpeedArea;

	@CommandLine.Option(names = "--slow-speed-relative-change", description = "provide a value that is bigger than 0.0 and smaller than 1.0")
	private Double slowSpeedRelativeChange;


	public static void main(String[] args) {
		MATSimApplication.run(DresdenScenarioWithSpeedReduction.class, args);
	}


	@Override
	@Nullable
	protected Config prepareConfig(Config config) {
		return super.prepareConfig(config);
	}


	@Override
	protected void prepareScenario(Scenario scenario) {
		super.prepareScenario(scenario);

			if (!Files.exists(slowSpeedArea)) {
				throw new IllegalArgumentException("Path to slow speed area not found: " + slowSpeedArea);
			} else if (slowSpeedRelativeChange == null) {
				throw new IllegalArgumentException("No relative change value for freeSpeed defined: " + slowSpeedArea);
			} else {
				prepareSlowSpeed(scenario.getNetwork(),
					ShpGeometryUtils.loadPreparedGeometries(IOUtils.resolveFileOrResource(new ShpOptions(slowSpeedArea, null, null).getShapeFile().toString())),
					slowSpeedRelativeChange);
			}

	}


	@Override
	protected void prepareControler(Controler controler) {
		super.prepareControler(controler);
	}



	/**
	 * Reduce speed of link in certain zone. Does not affect motorways and trunks. The speed is reduced by a relative factor.
	 * @param network             the network to be modified
	 * @param geometries          the geometries defining the area
	 * @param relativeSpeedChange the relative speed change (e.g. 0.5 reduces speedby 50%)
	 */
	private static void prepareSlowSpeed(Network network, List<PreparedGeometry> geometries, Double relativeSpeedChange) {

		Set<? extends Link> carLinksInArea = network.getLinks().values().stream()
			//filter car links
			.filter(link -> link.getAllowedModes().contains(TransportMode.car))
			//spatial filter
			.filter(link -> ShpGeometryUtils.isCoordInPreparedGeometries(link.getCoord(), geometries))
			//we won't change motorways and motorway_links
			.filter(link -> !((String) link.getAttributes().getAttribute("type")).contains("motorway"))
			.filter(link -> !((String) link.getAttributes().getAttribute("type")).contains("trunk"))
			.collect(Collectors.toSet());

		if (relativeSpeedChange >= 0.0 && relativeSpeedChange < 1.0) {
			log.info("reduce speed relatively by a factor of: {}", relativeSpeedChange);
			//apply 'tempo 20' to all roads but motorways
			carLinksInArea.forEach(link -> link.setFreespeed(link.getFreespeed() * relativeSpeedChange));
		}
	}

}
