package org.matsim.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationWriter;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.scenario.ScenarioUtils;
import picocli.CommandLine;

import java.io.File;
import java.nio.file.Path;

@CommandLine.Command(
	name = "assign-person-attribute-from-shapefile",
	description = "Assigns a person attribute from a shapefile feature based on the person's home coordinate."
)
public class AssignPersonAttributeFromShapefile implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(AssignPersonAttributeFromShapefile.class);

	@CommandLine.Option(names = "--inputconfig", description = "Path to input config", required = true)
	private String inputconfig;

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
		if (!new File(inputconfig).exists()) {
			log.error("Input config does not exist: {}", inputconfig);
			return 2;
		}
		output.toFile().mkdirs();

		ShpOptions.Index index = shp.createIndex(shp.getShapeCrs(), shpAttribute);

		Config config = ConfigUtils.loadConfig(inputconfig);
		Scenario scenario = ScenarioUtils.loadScenario(config);
		Population population = scenario.getPopulation();

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

		String outputplans = new File(output.toString(), ScaleDigitalTwinWithSnzData.POPULATIONFILE).getAbsolutePath();
		new PopulationWriter(scenario.getPopulation()).write(outputplans);

		scenario.getConfig().plans().setInputFile(outputplans);
		String outputconfig = new File(output.toString(), ScaleDigitalTwinWithSnzData.CONFIG).getAbsolutePath();
		new ConfigWriter(scenario.getConfig()).write(outputconfig);

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
