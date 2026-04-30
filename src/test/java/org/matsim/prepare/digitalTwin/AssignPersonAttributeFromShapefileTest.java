package org.matsim.prepare.digitalTwin;

import org.geotools.api.feature.simple.SimpleFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.locationtech.jts.geom.Coordinate;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.gis.GeoFileWriter;
import org.matsim.core.utils.gis.PolygonFeatureFactory;
import org.matsim.testcases.MatsimTestUtils;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AssignPersonAttributeFromShapefileTest {

	@RegisterExtension
	private MatsimTestUtils utils = new MatsimTestUtils();

	private static final String POPULATION_CRS = "EPSG:25833";
	private static final String ATTR_NAME = "zone";
	private static final String SHP_ATTR = "zone";

	@Test
	void testAssignAttributeFromShapefile() throws Exception {
		String dir = utils.getOutputDirectory();
		File shpFile = new File(dir, "test.shp");
		createShapefile(shpFile.getAbsolutePath());

		Config config = ConfigUtils.createConfig();
		Population population = ScenarioUtils.createScenario(config).getPopulation();

		// inside the polygon → should receive the attribute
		addPerson(population, "inside", 1000.0, 2000.0);
		// outside any polygon → should not receive the attribute
		addPerson(population, "outside", 9000.0, 9000.0);
		// no home_x/home_y → should not receive the attribute
		addPersonWithoutHomeCoord(population, "nohome");

		String plansFile = dir + "plans.xml.gz";
		new PopulationWriter(population).write(plansFile);
		config.plans().setInputFile("plans.xml.gz");
		String configFile = dir + "config.xml";
		new ConfigWriter(config).write(configFile);

		AssignPersonAttributeFromShapefile.main(new String[]{
			"--inputconfig", configFile,
			"--population-crs", POPULATION_CRS,
			"--output", dir + "output",
			"--attribute-name", ATTR_NAME,
			"--shp-attribute", SHP_ATTR,
			"--shp", shpFile.getAbsolutePath()
		});

		Population result = PopulationUtils.readPopulation(
			new File(dir + "output", ScaleDigitalTwinWithSnzData.POPULATIONFILE).getAbsolutePath());

		assertEquals("Zone1", result.getPersons().get(Id.createPersonId("inside")).getAttributes().getAttribute(ATTR_NAME));
		assertNull(result.getPersons().get(Id.createPersonId("outside")).getAttributes().getAttribute(ATTR_NAME));
		assertNull(result.getPersons().get(Id.createPersonId("nohome")).getAttributes().getAttribute(ATTR_NAME));
	}

	private void createShapefile(String file) throws Exception {
		Coordinate[] ring = new Coordinate[]{
			new Coordinate(0, 1000), new Coordinate(2000, 1000), new Coordinate(2000, 3000),
			new Coordinate(0, 3000), new Coordinate(0, 1000)
		};

		PolygonFeatureFactory factory = new PolygonFeatureFactory.Builder()
			.setName("zones")
			.addAttribute(SHP_ATTR, String.class)
			.setCrs(MGC.getCRS("epsg:25833"))
			.create();
		SimpleFeature feature = factory.createPolygon(ring, new Object[]{"Zone1"}, "1");
		GeoFileWriter.writeGeometries(List.of(feature), file);
	}

	private static void addPerson(Population population, String id, double homeX, double homeY) {
		PopulationFactory f = population.getFactory();
		Person person = f.createPerson(Id.createPersonId(id));
		person.getAttributes().putAttribute("home_x", homeX);
		person.getAttributes().putAttribute("home_y", homeY);
		Plan plan = f.createPlan();
		plan.addActivity(f.createActivityFromCoord("home", new Coord(homeX, homeY)));
		person.addPlan(plan);
		person.setSelectedPlan(plan);
		population.addPerson(person);
	}

	private static void addPersonWithoutHomeCoord(Population population, String id) {
		PopulationFactory f = population.getFactory();
		Person person = f.createPerson(Id.createPersonId(id));
		Plan plan = f.createPlan();
		plan.addActivity(f.createActivityFromCoord("home", new Coord(500, 500)));
		person.addPlan(plan);
		person.setSelectedPlan(plan);
		population.addPerson(person);
	}
}
