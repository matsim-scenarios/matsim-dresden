package org.matsim.run.scenarios;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Dresden-scenario plan rewrites operating on activity types.
 */
public final class DresdenActivities {
	private static final Logger log = LogManager.getLogger(DresdenActivities.class);

	private DresdenActivities() {}

	/**
	 * Force wrap-around scoring of first and last act of the day for every plan where they
	 * currently have different types: pick one side by coin flip and set both first and last
	 * to that base type, with a typical-duration suffix equal to the binned sum of the two
	 * original typical durations.
	 *
	 * <p>The combined typical follows the wrap-around scoring's semantic that first and
	 * last are scored as one merged activity (see
	 * org.matsim.core.scoring.functions.CharyparNagelActivityScoring#handleOvernightActivity),
	 * so the merged typical is the sum of the two originals. This is the formal inverse of
	 * {@link org.matsim.contrib.vsp.scenario.Activities#changeWrapAroundActsIntoMorningAndEveningActs}.
	 *
	 * <p>The coin flip is keyed by person id and the original first/last type strings, so the
	 * realization is stable across runs and changes only if a plan's first/last types change.
	 * On the last activity, endTime/startTime/maximumDuration are unset so it behaves as a
	 * true overnight activity.
	 */
	public static void changeNonWrapAroundActsIntoWrapAroundActs(Scenario scenario) {
		Set<String> firstActTypes = new HashSet<>();
		Set<String> lastActTypes = new HashSet<>();

		for (Person p : scenario.getPopulation().getPersons().values()) {
//			ignore freight / commercial traffic agents and stay home agents
			if (!p.getAttributes().getAttribute("subpopulation").equals("person") ||
				p.getSelectedPlan().getPlanElements().size() == 1) {
				continue;
			}

			for (Plan plan : p.getPlans()) {
				Activity first = (Activity) plan.getPlanElements().getFirst();
				Activity last = (Activity) plan.getPlanElements().getLast();

				String[] splitFirst = first.getType().split("_");
				String typeFirst = String.join("_", Arrays.copyOfRange(splitFirst, 0, splitFirst.length - 1));
				int originalTimeBinFirst = Integer.parseInt(splitFirst[splitFirst.length - 1]);
				firstActTypes.add(typeFirst);

				String[] splitLast = last.getType().split("_");
				String typeLast = String.join("_", Arrays.copyOfRange(splitLast, 0, splitLast.length - 1));
				int originalTimeBinLast = Integer.parseInt(splitLast[splitLast.length - 1]);
				lastActTypes.add(typeLast);

				if (typeFirst.equals(typeLast)) {
//					first and last act already share a base type -> wrap-around scoring already applies.
					continue;
				}

				int combinedBin = getDurationBin((double) originalTimeBinFirst + originalTimeBinLast);

//				coin flip per plan: heads -> first adopts last's base type; tails -> last adopts first's base type.
//				either way, both activities end up with the same full type string (base + combined typical),
//				which is what triggers handleOvernightActivity's wrap-around branch.
				String chosenBase = coinFlip(p.getId().toString(), first.getType(), last.getType()) == 0
					? typeLast
					: typeFirst;

				String newType = String.format("%s_%d", chosenBase, combinedBin);
				first.setType(newType);
				last.setType(newType);
				last.setEndTimeUndefined();
				last.setStartTimeUndefined();
				last.setMaximumDurationUndefined();
			}
		}
		log.info("Activity types of first activity in plans: {}", firstActTypes);
		log.info("Activity types of last activity in plans: {}", lastActTypes);
	}

	private static int getDurationBin(double duration) {
		final int maxCategories = 86400 / 600;

		int durationCategoryNr = (int) Math.round(duration / 600);

		if (durationCategoryNr <= 0) {
			durationCategoryNr = 1;
		}

		if (durationCategoryNr >= maxCategories) {
			durationCategoryNr = maxCategories;
		}
		return durationCategoryNr * 600;
	}

	private static int coinFlip(String... fields) {
//		String.hashCode is stable across JVMs; combining via 31x+y mirrors String.hashCode itself.
		int h = 0;
		for (String f : fields) {
			h = 31 * h + f.hashCode();
		}
		return h & 1;
	}
}
