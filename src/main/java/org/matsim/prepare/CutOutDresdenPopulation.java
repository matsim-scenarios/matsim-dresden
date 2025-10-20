package org.matsim.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CrsOptions;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.*;
import org.matsim.core.router.costcalculators.RandomizingTimeDistanceTravelDisutilityFactory;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.LeastCostPathCalculatorFactory;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;
import org.matsim.core.utils.geometry.geotools.MGC;
import picocli.CommandLine;

import java.util.HashSet;
import java.util.Set;

@CommandLine.Command(
	name = "cutout",
	description = "Cut out a population from a bigger population based on network routing and a shp file."
)
public class CutOutDresdenPopulation implements MATSimAppCommand {
	Logger log = LogManager.getLogger(CutOutDresdenPopulation.class);

	@CommandLine.Option(names = "--population", description = "Path to input population", required = true)
	private String populationPath;
	@CommandLine.Option(names = "--network", description = "Path to network", required = true)
	private String networkPath;
	@CommandLine.Option(names = "--buffer", description = "Buffer around zones in meter", defaultValue = "0")
	private double buffer;
	@CommandLine.Option(names = "--output-population", description = "Path to output population", required = true)
	private String outputPopulation;
	@CommandLine.Option(names = "--network-mode", description = "Mode to be used for network routing", defaultValue = TransportMode.car)
	private String mode;
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
		config.network().setTimeVariantNetwork(true);

		Population population = PopulationUtils.readPopulation(populationPath);
		Network network = NetworkUtils.readNetwork(networkPath);

		Geometry geom = shp.getGeometry(crs.getInputCRS());

		if (buffer != 0.) {
			geom = geom.buffer(buffer);
		}

		// Create router
		FreeSpeedTravelTime travelTime = new FreeSpeedTravelTime();
		LeastCostPathCalculatorFactory fastAStarLandmarksFactory = new SpeedyALTFactory();
		RandomizingTimeDistanceTravelDisutilityFactory disutilityFactory = new RandomizingTimeDistanceTravelDisutilityFactory(
			mode, config);
		TravelDisutility travelDisutility = disutilityFactory.createTravelDisutility(travelTime);
		LeastCostPathCalculator router = fastAStarLandmarksFactory.createPathCalculator(network, travelDisutility,
			travelTime);

		Set<Id<Person>> relevantPersons = new HashSet<>();
		personLoop:
		for (Person person : population.getPersons().values()) {
			for (Plan plan : person.getPlans()) {
				for (TripStructureUtils.Trip trip : TripStructureUtils.getTrips(plan)) {
					Link startLink = getLinkOfAct(trip.getOriginActivity(), network);
					Link endLink = getLinkOfAct(trip.getDestinationActivity(), network);

					LeastCostPathCalculator.Path route = router.calcLeastCostPath(startLink, endLink,
						0, person, null);

					for (Link link : route.links) {
						if (MGC.coord2Point(link.getFromNode().getCoord()).within(geom) ||
							MGC.coord2Point(link.getToNode().getCoord()).within(geom)) {
							relevantPersons.add(person.getId());
							// break out of planElement + plan loop, continue with next person
							continue personLoop;
						}
					}
				}
			}
		}

		Population cutoutPopulation = PopulationUtils.createPopulation(ConfigUtils.createConfig());

		for (Id<Person> personId : relevantPersons) {
			cutoutPopulation.addPerson(population.getPersons().get(personId));
		}

		PopulationUtils.writePopulation(cutoutPopulation, outputPopulation);
		log.info("{} persons of {} have been removed from the population because they do not touch the study area defined in --shp.",
			population.getPersons().size() - relevantPersons.size(), population.getPersons().size());

		return 0;
	}

	private static Link getLinkOfAct(Activity act, Network network) {
		if (act.getLinkId() == null) {
			return NetworkUtils.getNearestLink(network, act.getCoord());
		} else {
			return network.getLinks().get(act.getLinkId());
		}
	}
}
