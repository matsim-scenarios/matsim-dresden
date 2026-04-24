package org.matsim.prepare;

import org.geotools.api.feature.simple.SimpleFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.locationtech.jts.geom.Coordinate;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.utils.gis.GeoFileWriter;
import org.matsim.core.utils.gis.PolygonFeatureFactory;
import org.matsim.testcases.MatsimTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AssignPersonAttributeFromShapefileTest {

	@RegisterExtension
	private MatsimTestUtils utils = new MatsimTestUtils();

	/**
	 * Person inside polygon gets the attribute; person outside and person with no home coord do not
	 */
	@Test
	void testAttributeAssignment() {
		String dir = utils.getOutputDirectory();

		Population population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
		addPersonWithHomeAttributes(population, "inside", new Coord(5.0, 5.0));
		addPersonWithHomeAttributes(population, "outside", new Coord(15.0, 15.0));
		addPersonWithoutHomeCoord(population, "nohome");

		String inputPlans = dir + "population.xml.gz";
		PopulationUtils.writePopulation(population, inputPlans);

		// polygon covers (0,0)-(10,10)
		String shpFile = dir + "zones.shp";
		createShapefile(shpFile, new Coordinate[]{
			new Coordinate(0, 0), new Coordinate(10, 0), new Coordinate(10, 10),
			new Coordinate(0, 10), new Coordinate(0, 0)
		}, "zone", "zone-A");

		String outputPlans = dir + "output-population.xml.gz";
		AssignPersonAttributeFromShapefile.main(new String[]{
			inputPlans,
			"--output", outputPlans,
			"--attribute-name", "zone",
			"--shp-attribute", "zone",
			"--shp", shpFile,
			"--shp-crs", "EPSG:25833"
		});

		Population result = PopulationUtils.readPopulation(outputPlans);
		assertEquals(3, result.getPersons().size());
		assertEquals("zone-A", result.getPersons().get(Id.createPersonId("inside")).getAttributes().getAttribute("zone"));
		assertNull(result.getPersons().get(Id.createPersonId("outside")).getAttributes().getAttribute("zone"));
		assertNull(result.getPersons().get(Id.createPersonId("nohome")).getAttributes().getAttribute("zone"));
	}

	private static void addPersonWithHomeAttributes(Population population, String id, Coord homeCoord) {
		PopulationFactory f = population.getFactory();
		Person person = f.createPerson(Id.createPersonId(id));
		person.getAttributes().putAttribute("home_x", homeCoord.getX());
		person.getAttributes().putAttribute("home_y", homeCoord.getY());
		Plan plan = f.createPlan();
		plan.addActivity(f.createActivityFromCoord("home", homeCoord));
		person.addPlan(plan);
		person.setSelectedPlan(plan);
		population.addPerson(person);
	}

	private static void addPersonWithoutHomeCoord(Population population, String id) {
		PopulationFactory f = population.getFactory();
		Person person = f.createPerson(Id.createPersonId(id));
		Plan plan = f.createPlan();
		plan.addActivity(f.createActivityFromCoord("work", new Coord(5.0, 5.0)));
		person.addPlan(plan);
		person.setSelectedPlan(plan);
		population.addPerson(person);
	}

	private static void createShapefile(String path, Coordinate[] ring, String attrName, String attrValue) {
		PolygonFeatureFactory factory = new PolygonFeatureFactory.Builder()
			.setName("zones")
			.addAttribute(attrName, String.class)
			.create();
		SimpleFeature feature = factory.createPolygon(ring, new Object[]{attrValue}, "1");
		GeoFileWriter.writeGeometries(List.of(feature), path);
	}
}
