package org.matsim.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.prepare.population.ExtractHomeCoordinates;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import picocli.CommandLine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.SplittableRandom;

@CommandLine.Command(
	name = "population",
	description = "Prepares person attributes and plans."
)
public class PreparePopulation implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(PreparePopulation.class);

	private final SplittableRandom rnd = new SplittableRandom(1234);

	@CommandLine.Parameters(arity = "1", paramLabel = "INPUT", description = "Path to input population")
	private Path input;

	@CommandLine.Option(names = "--output", description = "Path to output population", required = true)
	private Path output;

	public static void main(String[] args) {
		new PreparePopulation().execute(args);
	}

	private static double round(double x) {
		return BigDecimal.valueOf(x).setScale(2, RoundingMode.HALF_UP).doubleValue();
	}

	@Override
	public Integer call() throws Exception {

		if (!Files.exists(input)) {
			log.error("Input population does not exist: {}", input);
			return 2;
		}


		Population population = PopulationUtils.readPopulation(input.toString());


		population.getPersons().values().forEach(this::prepare);


		PopulationUtils.writePopulation(population, output.toString());

		return 0;
	}

	/**
	 * This step runs before any other steps.
	 */
	private void prepare(Person person) {


		List<Activity> activities = TripStructureUtils.getActivities(person.getSelectedPlan(), TripStructureUtils.StageActivityHandling.ExcludeStageActivities);
		for (Activity act : activities) {
			// Reduce unnecessary precision
			if (act.getCoord() != null) {
				act.setCoord(new Coord(round(act.getCoord().getX()), round(act.getCoord().getY())));
			}
		}

		// Set car availability to "never" for agents below 18 years old
		// Standardize the attribute "age"
		String avail = "always";
		Object age = person.getAttributes().getAttribute("age");
		if (age != null && (int) age < 18) {
			avail = "never";
		}

		// Replace with standardized car availability attribute
		PersonUtils.setCarAvail(person, avail);
		person.getAttributes().removeAttribute("carAvailability");

		// Standardize the attribute "sex"
		Object sex = person.getAttributes().getAttribute("gender");
		if (sex != null) {
			PersonUtils.setSex(person, (String) sex);
//			gender and sex are two different things: sex is the biological sex, assigned at birth
//			gender is the social/cultural "sex" or gender, which can coincide with the sex but does not have to. -sm0925
//			person.getAttributes().removeAttribute("gender");
		}

		// Assign income to person (skip the freight agents)
		if (person.getId().toString().startsWith("freight") || person.getId().toString().startsWith("commercial")
		|| person.getId().toString().startsWith("goods")) {
			return;
		}

		ExtractHomeCoordinates.setHomeCoordinate(person);

		String incomeGroupString = (String) person.getAttributes().getAttribute("hhIncome");
		String householdSizeString = (String) person.getAttributes().getAttribute("hhSize");
		int incomeGroup = 0;
		double householdSize = 1;
		if (incomeGroupString != null && householdSizeString != null) {
			incomeGroup = Integer.parseInt(incomeGroupString);
			householdSize = Double.parseDouble(householdSizeString);
		}

		double income = switch (incomeGroup) {
			case 1 -> 500 / householdSize;
			case 2 -> (rnd.nextInt(400) + 500) / householdSize;
			case 3 -> (rnd.nextInt(600) + 900) / householdSize;
			case 4 -> (rnd.nextInt(500) + 1500) / householdSize;
			case 5 -> (rnd.nextInt(1000) + 2000) / householdSize;
			case 6 -> (rnd.nextInt(1000) + 3000) / householdSize;
			case 7 -> (rnd.nextInt(1000) + 4000) / householdSize;
			case 8 -> (rnd.nextInt(1000) + 5000) / householdSize;
			case 9 -> (rnd.nextInt(1000) + 6000) / householdSize;
			case 10 -> (Math.abs(rnd.nextGaussian()) * 1000 + 7000) / householdSize;
			default -> 2364;
			// Average monthly household income per Capita (2021). See comments below for details
			// Average Gross household income: 4734 Euro
			// Average household size: 83.1M persons /41.5M households = 2.0 persons / household
			// Average household income per capita: 4734/2.0 = 2364 Euro
			// Source (Access date: 21 Sep. 2021):
			// https://www.destatis.de/EN/Themes/Society-Environment/Income-Consumption-Living-Conditions/Income-Receipts-Expenditure/_node.html
			// https://www.destatis.de/EN/Themes/Society-Environment/Population/Households-Families/_node.html
			// https://www.destatis.de/EN/Themes/Society-Environment/Population/Current-Population/_node.html;jsessionid=E0D7A060D654B31C3045AAB1E884CA75.live711
		};
		PersonUtils.setIncome(person, (int) income);

	}

}
