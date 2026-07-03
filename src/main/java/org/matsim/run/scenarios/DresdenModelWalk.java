package org.matsim.run.scenarios;

import com.google.inject.Singleton;
import com.google.inject.multibindings.Multibinder;
import com.sun.jdi.connect.Transport;
import jakarta.annotation.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.application.MATSimApplication;
import org.matsim.contrib.vsp.pt.fare.PtFareModule;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.dashboards.DresdenDashboardProvider;
import org.matsim.simwrapper.*;
import org.matsim.simwrapper.dashboard.TrafficDashboard;
import org.matsim.vehicles.VehicleType;
import playground.vsp.scoring.IncomeDependentUtilityOfMoneyPersonScoringParameters;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public final class DresdenModelWalk extends DresdenModel {

	Logger log = LogManager.getLogger( DresdenModelWalk.class );

	private RoutingConfigGroup.TeleportedModeParams walkParams;

	public static void main(String[] args) {
		MATSimApplication.execute(DresdenModelWalk.class, args);
	}

	@Nullable
	@Override
	protected Config prepareConfig(Config config) {
		log.warn("###################################################################");
		log.warn("This class is not meant to be executed as a normal controller. It  ");
		log.warn("expects calibrated plans, it removes all original agents and       ");
		log.warn("converts there walk-trips into single-trip-agents. It further adds ");
		log.warn("walk as a network- and main-mode and adds a default-walk-vehicle   ");
		log.warn("with max-speed of teleported-mode-speed. It further assumes that   ");
		log.warn("that we have no walk-network available and adds walk to all bike-  ");
		log.warn("links. In the end we will have walk-trips with network-routs and   ");
		log.warn("corresponding analysis. ");
		log.warn("###################################################################");


		super.prepareConfig( config );

		// assume we have a relaxed scenario with routes, we route walk with freespeed and simulate only one iter
		config.controller().setLastIteration(0);

		// we add walk here as network-mode
		HashSet<String> modes = new HashSet<>(config.qsim().getMainModes());
		modes.add(TransportMode.walk);
		config.qsim().setMainModes(modes);
		config.routing().setNetworkModes(modes);

		// we need to remove the walk-teleported-params since walk is on the network
		this.walkParams = config.routing().getTeleportedModeParams().get(TransportMode.walk);
		config.routing().removeTeleportedModeParams(TransportMode.walk);

		// need this as a work-around
		RoutingConfigGroup.TeleportedModeParams nonNetworkWalk = config.routing().getOrCreateModeRoutingParams(TransportMode.non_network_walk);
		nonNetworkWalk.setBeelineDistanceFactor(this.walkParams.getBeelineDistanceFactor());
		nonNetworkWalk.setTeleportedModeSpeed(this.walkParams.getTeleportedModeSpeed());
		nonNetworkWalk.setTeleportedModeFreespeedLimit(this.walkParams.getTeleportedModeFreespeedLimit());

		SimWrapperConfigGroup simwrapperCfg = ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class);
		simwrapperCfg.setDefaultDashboards(SimWrapperConfigGroup.DefaultDashboardsMode.disabled);

		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {
		super.prepareScenario( scenario );

		scenario.getNetwork().getLinks().values().parallelStream()
			.filter(l -> l.getAllowedModes().contains(TransportMode.bike))
			.forEach( l -> {
				HashSet<String> modes = new HashSet<>(l.getAllowedModes());
				modes.add(TransportMode.walk);
				l.setAllowedModes(modes);
		});

		// add a default-vehicle for walk
		VehicleType vehicleType = scenario.getVehicles().addModeVehicleType(TransportMode.walk);
		vehicleType.setMaximumVelocity(this.walkParams.getTeleportedModeSpeed());


		// create one person per walk-trip
		List<Person> newPersons = new ArrayList<>();

		PopulationFactory factory = scenario.getPopulation().getFactory();

		AtomicInteger counter = new AtomicInteger(0);

		scenario.getPopulation().getPersons().values().stream().forEach( p -> {
			List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(p.getSelectedPlan());
			trips.forEach(trip -> {
				String mainMode = TripStructureUtils.getRoutingModeIdentifier().identifyMainMode(trip.getTripElements());
				if(mainMode.equals(TransportMode.walk)) {
					Person tripPerson = factory.createPerson(Id.createPersonId(counter.getAndIncrement()));
					p.getAttributes().getAsMap().forEach((k,v) -> {
						if(k.equals("vehicles")) return;
						tripPerson.getAttributes().putAttribute(k,v);
					});
					newPersons.add(tripPerson);
					Plan plan = factory.createPlan();
					tripPerson.addPlan(plan);
					plan.addActivity(trip.getOriginActivity());
					plan.addLeg(factory.createLeg(TransportMode.walk));
					plan.addActivity(trip.getDestinationActivity());
				}
			});
		});
		new HashSet<Person>(scenario.getPopulation().getPersons().values()).forEach( p -> scenario.getPopulation().removePerson(p.getId()));

		newPersons.forEach( p -> scenario.getPopulation().addPerson(p));
	}

	@Override
	protected void prepareControler(Controler controler) {
		super.prepareControler( controler );
		controler.addOverridingModule(new SimWrapperModule());

		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				DashboardProvider dashboardProvider = new DashboardProvider() {
					@Override
					public List<Dashboard> getDashboards(Config config, SimWrapper simWrapper) {
						return List.of(new TrafficDashboard(Set.of(TransportMode.walk)));
					}
				};

				Multibinder.newSetBinder( binder(), DashboardProvider.class ).addBinding().toInstance( dashboardProvider );
			}
		});
	}

}
