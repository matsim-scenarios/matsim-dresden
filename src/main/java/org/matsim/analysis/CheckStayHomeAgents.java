package org.matsim.analysis;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.population.PopulationUtils;
import picocli.CommandLine;

import java.util.HashSet;
import java.util.Set;


@CommandLine.Command(
	name = "stay-home-agents",
	description = "Check the selected plans of a population file for stay home agents. Meaning: agents with only one plan element which is a home activity."
)
public class CheckStayHomeAgents implements MATSimAppCommand {
	private static final Logger log = LogManager.getLogger(CheckStayHomeAgents.class);

	@CommandLine.Parameters(arity = "1", paramLabel = "INPUT", description = "Path to input population")
	private String input;

	public static void main(String[] args) {
		new CheckStayHomeAgents().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		Population population = PopulationUtils.readPopulation(input);

		Set<Id<Person>> stayHomeAgents = new HashSet<>();

		for (Person p : population.getPersons().values()) {
			if (p.getSelectedPlan().getPlanElements().size() == 1) {
				if (!(p.getSelectedPlan().getPlanElements().getFirst() instanceof Activity act)) {
					log.fatal("Person with id {} possesses a plan with 1 element only, which is not an activity! This should never be the case!", p.getId());
					throw new IllegalStateException();
				} else {
					if (act.getType().contains("home")) {
						stayHomeAgents.add(p.getId());
					} else {
						log.error("Person with id {} possesses a plan with 1 element only, which is not a home activity! This should never be the case!", p.getId());
					}
				}
			}
		}

		log.info("####################################");

		double share = 0;
		if (!stayHomeAgents.isEmpty()) {
			share = (double) population.getPersons().size() / stayHomeAgents.size();
		}

		log.info("Out of {} agents, {} are stay home agents. ({})", population.getPersons().size(), stayHomeAgents.size(), share);
		log.info("####################################");
		log.info("PersonIds of stay home agents:");
		stayHomeAgents.forEach(log::info);

		return 0;
	}
}
