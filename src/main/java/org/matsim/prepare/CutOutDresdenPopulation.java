package org.matsim.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CrsOptions;
import org.matsim.application.options.ShpOptions;
import org.matsim.application.prepare.population.CleanPopulation;
import org.matsim.application.prepare.scenario.CreateScenarioCutOut;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.algorithms.ParallelPersonAlgorithmUtils;
import org.matsim.core.population.algorithms.TripsToLegsAlgorithm;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.router.*;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.utils.DresdenUtils;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.matsim.application.ApplicationUtils.globFile;

public class CutOutDresdenPopulation implements MATSimAppCommand {
	Logger log = LogManager.getLogger(CutOutDresdenPopulation.class);

	@CommandLine.Option(names = "--population", description = "Path to input population", required = true)
	private String populationPath;
	@CommandLine.Option(names = "--network", description = "Path to network", required = true)
	private String networkPath;
	@CommandLine.Option(names = "--buffer", description = "Buffer around zones in meter", defaultValue = "5000")
	private double buffer;
	@CommandLine.Option(names = "--output-population", description = "Path to output population", required = true)
	private String outputPopulation;
	@CommandLine.Option(names = "--network-mode", description = "Mode to be used for network routing", defaultValue = TransportMode.car)
	private String mode;
	@CommandLine.Option(names = "--beeline", description = "If activated, it is checked if agents' routes go through the defined area by beeline -- connecting start and end of a trip --" +
		"rather than network routes.", defaultValue = "DISABLED")
	private DresdenUtils.FunctionalityHandling beeline;
	@CommandLine.Mixin
	private CrsOptions crs;
	@CommandLine.Mixin
	private ShpOptions shp;

	public static void main(String[] args) {
		new CutOutDresdenPopulation().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		Config config = ConfigUtils.createConfig();
		config.global().setCoordinateSystem(crs.getInputCRS());
		config.plans().setInputFile(populationPath);
		config.network().setInputFile(networkPath);
		config.network().setTimeVariantNetwork(true);

		Path tempDir = Files.createTempDirectory("temp");
		config.controller().setOutputDirectory(tempDir.toString());
		config.controller().setLastIteration(0);

		Scenario scenario = ScenarioUtils.loadScenario(config);

		Geometry geom = shp.getGeometry(crs.getInputCRS());
		Geometry geomBuffer = geom.buffer(buffer);

		Population population;

		if (beeline == DresdenUtils.FunctionalityHandling.ENABLED) {
//			use CR logic from Cutout. It is checked if either activities or the beeline of a leg's route touches the shp
			ParallelPersonAlgorithmUtils.run(scenario.getPopulation(), Runtime.getRuntime().availableProcessors(), new CreateScenarioCutOut());
			population = scenario.getPopulation();
		} else {
			final TripsToLegsAlgorithm trips2Legs = new TripsToLegsAlgorithm(new RoutingModeMainModeIdentifier());

			for (Person person : scenario.getPopulation().getPersons().values()) {
				for (Plan plan : person.getPlans()) {
//					make trips = legs
					trips2Legs.run(plan);

					for (PlanElement el : plan.getPlanElements()) {
						if (el instanceof Leg leg) {
//							remove routes from legs
							CleanPopulation.removeRouteFromLeg(leg);
							leg.setRoutingMode(mode);
							leg.setMode(mode);
						}
					}
				}
			}

			Controler controler = new Controler(scenario);
			controler.run();

			String popPath = globFile(tempDir, "*output_population.xml.gz").toString();

			population = PopulationUtils.readPopulation(popPath);
			Set<Id<Person>> relevantPersons = new HashSet<>();

			personLoop:
			for (Person person : population.getPersons().values()) {
				for (Plan plan : person.getPlans()) {
					for (PlanElement el : plan.getPlanElements()) {
						if (el instanceof Leg leg) {
							if (leg.getRoute() instanceof NetworkRoute networkRoute) {
								for (Id<Link> linkId : networkRoute.getLinkIds()) {
									Link link = scenario.getNetwork().getLinks().get(linkId);

									if (MGC.coord2Point(link.getFromNode().getCoord()).within(geomBuffer) ||
										MGC.coord2Point(link.getToNode().getCoord()).within(geomBuffer)) {
										relevantPersons.add(person.getId());
										// break out of planElement + plan loop, continue with next person
										continue personLoop;
									}
								}
							}
						}
					}
				}
			}

			for (Id<Person> personId : relevantPersons) {
				population.removePerson(personId);
			}

			log.info("{} persons of {} have been removed from the population because they do not touch the study area defined in --shp.",
				relevantPersons.size(), scenario.getPopulation().getPersons().size());
		}
		PopulationUtils.writePopulation(population, outputPopulation);

		return 0;
	}
}
