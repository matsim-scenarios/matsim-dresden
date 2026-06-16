package org.matsim.run.scenarios;

import jakarta.annotation.Nullable;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimApplication;
import org.matsim.application.prepare.population.CleanPopulation;
import org.matsim.core.config.Config;
import org.matsim.core.population.routes.NetworkRoute;
import picocli.CommandLine;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class DresdenModelCarolaBridge extends DresdenModel {
	@CommandLine.Option(names = "--close-carola-bridge", description = "the status of Carola bridge (FUNCTIONAL or COLLAPSED)", defaultValue = "COLLAPSED")
	private DresdenUtils.CarolaBridgeStatus carolaBridgeStatus;

	public static void main(String[] args) {
		MATSimApplication.execute(DresdenModelCarolaBridge.class, args);
	}

	@Nullable
	@Override
	protected Config prepareConfig(Config config) {
		super.prepareConfig(config);
		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {
		super.prepareScenario(scenario);

		if (carolaBridgeStatus == DresdenUtils.CarolaBridgeStatus.COLLAPSED) {
			// close down Carola bridge for car and bike (reduce the free speed to almost 0)
			Network network = scenario.getNetwork();
			List<Link> carolaBridgeLinks = network.getLinks().values().stream()
				.filter(link -> "Carolabrücke".equals(link.getAttributes().getAttribute("name")))
				.collect(Collectors.toList());
			// reduce the free speed of the links representing Carola bridge to a very small value
			carolaBridgeLinks.forEach(link -> link.setFreespeed(0.00001));

			// reroute pt lines
			ReroutePtLinesCarolaBridge.reroutePtLines(scenario.getTransitSchedule(), scenario.getTransitVehicles());

			// remove the route in the plan, if the route covers carola bridge (i.e., they need to re-route at the first iteration already).
			Set<Id<Link>> carolaBridgeLinkIds = carolaBridgeLinks.stream().map(Link::getId).collect(Collectors.toSet());
			for (Person person : scenario.getPopulation().getPersons().values()) {
				for (Plan plan : person.getPlans()) {
					for (PlanElement planElement : plan.getPlanElements()) {
						if (planElement instanceof Leg leg) {
							Route route = leg.getRoute();
							if (route instanceof NetworkRoute networkRoute) {
								Set<Id<Link>> routeLinkIds = new HashSet<>(networkRoute.getLinkIds());
								routeLinkIds.add(networkRoute.getStartLinkId());
								routeLinkIds.add(networkRoute.getEndLinkId());
								boolean routeIsImpacted = routeLinkIds.stream().anyMatch(carolaBridgeLinkIds::contains)
									|| leg.getMode().equals(TransportMode.pt);
								if (routeIsImpacted) {
									CleanPopulation.removeRouteFromLeg(leg);
								}
							}
						}
					}
				}
			}
		}
	}

}
