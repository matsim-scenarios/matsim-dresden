package org.matsim.run.scenarios;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.contrib.vsp.scenario.SnzActivities;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Dresden-local, clamp-aware copy of {@link org.matsim.contrib.vsp.scenario.Activities}.
 *
 * <p>The upstream {@code Activities.changeWrapAroundActsIntoMorningAndEveningActs} splits a
 * wrap-around activity (same first/last type, e.g. home) into a {@code _morning} and an
 * {@code _evening} sub-type so the day is scored via the else-branch of
 * {@link org.matsim.core.scoring.functions.CharyparNagelActivityScoring#handleOvernightActivity}
 * instead of the wrap-around if-branch. It assigns the evening typical duration as
 * {@code originalTypical - durationFirst}. That formula silently assumes the evening activity is
 * scored against a 24:00 day-end, and it lands at (or below) zero — collapsing into the 10-minute
 * floor bin — whenever the morning departure exceeds the whole-day typical.</p>
 *
 * <p>In Dresden the else-branch clamp is moved to 27:00 via
 * {@code config.scenario().setSimulationPeriodInDays(1.125)} (the scoring reads
 * {@code simulationPeriodInDays * 24 * 3600} as the overnight clamp). That gives the evening
 * activity {@code clamp - 24h} extra room relative to the wrap frame in which the original
 * typical was measured. This copy carries that extra room into the typical:
 * {@code evening = originalTypical - durationFirst + (clamp - 24h)}, the value that keeps the
 * evening "satisfied" at the same reference plan, and lets both sub-types bin up to the clamp
 * rather than 24h. When {@code simulationPeriodInDays == 1.0} (clamp at 24:00) the slack is zero
 * and the maximum bin is 86400, so this reduces exactly to the upstream behaviour.</p>
 *
 * @see org.matsim.contrib.vsp.scenario.Activities
 */
public final class DresdenActivities {
	private static final Logger log = LogManager.getLogger( DresdenActivities.class );

	/** The wrap-around if-branch scores the last act to firstActEnd + 24h; hardcoded in MATSim. */
	private static final double WRAP_OFFSET_S = 24 * 3600;

	private DresdenActivities(){}

	/**
	 * Disable wrap-around scoring of the first and last act of the day by setting them to different
	 * subtypes "_morning" and "_evening". Clamp-aware variant of
	 * {@link org.matsim.contrib.vsp.scenario.Activities#changeWrapAroundActsIntoMorningAndEveningActs(Scenario)}.
	 */
	public static void changeWrapAroundActsIntoMorningAndEveningActs( Scenario scenario ) {
		Set<String> firstActTypes = new HashSet<>();
		Set<String> lastActTypes = new HashSet<>();

		// Else-branch overnight clamp = simulationPeriodInDays * 24h (read from the same config the
		// scoring function uses). The evening activity is scored from its start to this clamp.
		double elseClampS = scenario.getConfig().scenario().getSimulationPeriodInDays() * 24 * 3600;
		double clampSlack = elseClampS - WRAP_OFFSET_S;
		int maxBinSeconds = (int) Math.round( elseClampS );

		for ( Person p : scenario.getPopulation().getPersons().values()) {
//			ignore freight / commercial traffic agents and stay home agents
			if (!p.getAttributes().getAttribute("subpopulation").equals("person") ||
			p.getSelectedPlan().getPlanElements().size() == 1) {
				continue;
			}

			for ( Plan plan : p.getPlans()) {
				Activity first = (Activity) plan.getPlanElements().getFirst();
				Activity last = (Activity) plan.getPlanElements().getLast();

				String[] splitFirst = first.getType().split("_");
				String typeFirst = String.join("_", Arrays.copyOfRange(splitFirst, 0, splitFirst.length - 1 ) );
				int orginalTimeBinFirst = Integer.parseInt(splitFirst[splitFirst.length - 1]);
				firstActTypes.add(typeFirst);

				String[] splitLast = last.getType().split("_");
				String typeLast = String.join("_", Arrays.copyOfRange(splitLast, 0, splitLast.length - 1));
				int orginalTimeBinLast = Integer.parseInt(splitLast[splitLast.length - 1]);
				lastActTypes.add(typeLast);

				if (!typeFirst.equals(typeLast)) {
//					if first and last act do not have the same type, we will not change anything.
//					this is the pragmatic version. There are last acts with without startTime, endTime or maxDuration.
//					this needs to be repaired upstream (in the makefile process). -sm0226
					continue;
				}

				Double durationFirst = null;
				if (first.getEndTime().isDefined()) {
//					use act end time if defined
					durationFirst = first.getEndTime().seconds();
				}

				if (durationFirst == null && first.getMaximumDuration().isDefined()) {
					durationFirst = first.getMaximumDuration().seconds();
				}

				if (durationFirst == null) {
					log.fatal("Neither duration nor end time is defined for activity {} of agent {}. This should not happen, aborting!", first, p.getId() );
					throw new IllegalStateException("");
				}

				int durationBinFirst = getDurationBin(durationFirst, maxBinSeconds);

				first.setType(String.format("%s_%d", SnzActivities.createMorningActivityType(typeFirst ), durationBinFirst ) );

	//			act types of first and last act the same
				if (orginalTimeBinFirst != orginalTimeBinLast) {
					log.fatal("typical duration of first and last activity of person {} with the same act type {} are not the same. This should not happen, aborting!", p.getId(), typeLast );
					throw new IllegalStateException("");
				}
//				The evening activity is scored to the 27:00 else-branch clamp, so it has clampSlack
//				more room than the 24h wrap frame in which orginalTimeBinLast was measured. Carry
//				that room into the typical so the evening stays satisfied instead of collapsing into
//				the 10-minute floor bin. clampSlack is 0 when the clamp is at 24:00 (upstream behaviour).
				double durationLast = orginalTimeBinLast - durationFirst + clampSlack;

				last.setType(String.format("%s_%d", SnzActivities.createEveningActivityType(typeLast ), getDurationBin(durationLast, maxBinSeconds ) ) );
				last.setMaximumDuration(durationLast);
				last.setEndTimeUndefined();
				last.setStartTimeUndefined();
			}
		}
		log.info("Activity types of first activity in plans: {}", firstActTypes );
		log.info("Activity types of last activity in plans: {}", lastActTypes );
	}

	/**
	 * Round a duration to the nearest 10-minute bin, clamped to [600, maxSeconds]. Clamp-aware
	 * variant of the upstream getDurationBin, whose ceiling is fixed at 86400; here it tracks the
	 * day length so morning/evening typicals can reach the 27:00 clamp (matching the scoring params
	 * SnzActivities registers up to 97200).
	 */
	private static int getDurationBin( double duration, int maxSeconds ) {
		final int maxCategories = maxSeconds / 600;

		int durationCategoryNr = (int) Math.round(duration / 600);

		if (durationCategoryNr <= 0) {
			durationCategoryNr = 1;
		}

		if (durationCategoryNr >= maxCategories) {
			durationCategoryNr = maxCategories;
		}
		return durationCategoryNr * 600;
	}
}
