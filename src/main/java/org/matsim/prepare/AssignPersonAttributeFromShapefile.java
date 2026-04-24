package org.matsim.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CrsOptions;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

@CommandLine.Command(
	name = "assign-person-attribute-from-shapefile",
	description = "Assigns a person attribute from a shapefile feature based on the person's home coordinate."
)
public class AssignPersonAttributeFromShapefile implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(AssignPersonAttributeFromShapefile.class);

	@CommandLine.Parameters(arity = "1", paramLabel = "INPUT", description = "Path to input population")
	private Path input;

	@CommandLine.Option(names = "--output", description = "outputpath", required = true)
	private Path output;

	@CommandLine.Option(names = "--attribute-name", description = "Name of person attribute to assign", required = true)
	private String attributeName;

	@CommandLine.Option(names = "--shp-attribute", description = "Name of the shapefile attribute field to use as value", required = true)
	private String shpAttribute;

	@CommandLine.Mixin
	private ShpOptions shp;

	public static void main(String[] args) {
		new AssignPersonAttributeFromShapefile().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		if (!Files.exists(input)) {
			log.error("Input population does not exist: {}", input);
			return 2;
		}

		ShpOptions.Index index = shp.createIndex(shp.getShapeCrs(), shpAttribute);

		Population population = PopulationUtils.readPopulation(input.toString());

		int assigned = 0;
		int notFound = 0;

		for (Person person : population.getPersons().values()) {
			Coord homeCoord = getHomeCoord(person);
			if (homeCoord == null) {
				notFound++;
				continue;
			}

			Object value = index.query(homeCoord);
			if (value != null) {
				person.getAttributes().putAttribute(attributeName, value.toString());
				assigned++;
			} else {
				notFound++;
			}
		}

		log.info("Assigned '{}' to {} persons; {} persons had no matching shapefile feature", attributeName, assigned, notFound);
		PopulationUtils.writePopulation(population, output.toString());
		return 0;
	}

	private Coord getHomeCoord(Person person) {
		Object homeX = person.getAttributes().getAttribute("home_x");
		Object homeY = person.getAttributes().getAttribute("home_y");
		if (homeX != null && homeY != null) {
			return new Coord((double) homeX, (double) homeY);
		}

		return null;
	}
}
