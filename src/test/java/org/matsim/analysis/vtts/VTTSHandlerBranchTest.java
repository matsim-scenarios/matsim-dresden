package org.matsim.analysis.vtts;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.ActivityEndEvent;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.scoring.functions.ScoringParameters;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import tech.tablesaw.api.Table;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Operational record of which activity {@link VTTSHandler} reports for each branch it can take, driven by synthetic
 * events rather than a run. The point is the two columns the analysis is read for -- {@code actDur} (the interval the
 * performing utility is integrated over) and {@code typDur} (the planned typical duration of the activity the row is
 * about) -- and the fact that each branch picks a different rule for them:
 * <ul>
 *   <li>a middle activity: arrival to departure, typical from the aligned plan activity;</li>
 *   <li>the activity an agent is at when the simulation ends, when the day does <em>not</em> wrap (first and last type
 *   differ -- always the case in this scenario, since EncodeTypicalDuration splits home into home_morning /
 *   home_evening): arrival to {@code simulationPeriodInDays * 24h};</li>
 *   <li>the same, when the day <em>does</em> wrap: arrival to the first activity's end + 24h;</li>
 *   <li>arriving past the day end: a negative window, flagged;</li>
 *   <li>an agent that stalls on a trip after ending an activity: that activity took the middle-activity branch, so it
 *   is not treated as an end-of-day activity at all.</li>
 * </ul>
 * Times are seconds; the day end is 27:00 throughout ({@code simulationPeriodInDays = 1.125}).
 */
class VTTSHandlerBranchTest {

	private static final Id<Person> PID = Id.createPersonId("p");
	private static final Id<Link> LINK = Id.createLinkId("l");
	private static final double DAY_END = 1.125 * 24 * 3600;   // 27:00 = 97200

	private static final double H = 3600.;

	/** A realized activity as the events describe it: no start for the day's first, no end for the day's last. */
	private record Act(String type, Double start, Double end) {}

	private static Act first(String type, double end) { return new Act(type, null, end); }
	private static Act middle(String type, double start, double end) { return new Act(type, start, end); }
	private static Act unfinished(String type, double start) { return new Act(type, start, null); }

	private static Config config() {
		Config config = ConfigUtils.createConfig();
		ScoringConfigGroup scoring = config.scoring();
		scoring.setPerforming_utils_hr(6.);
		scoring.setMarginalUtilityOfMoney(1.);
		for (String type : new String[]{"home", "home_morning", "home_evening", "work", "leisure", "shop"}) {
			// only a fallback: every activity in these tests carries a planned typical duration, which wins
			scoring.addActivityParams(new ScoringConfigGroup.ActivityParams(type).setTypicalDuration(2. * H));
		}
		config.scenario().setSimulationPeriodInDays(1.125);
		return config;
	}

	/**
	 * Feed one agent's day through the handler and return the trips table. {@code plannedTypicals} are the planned
	 * per-activity typical durations in seconds, in plan order -- deliberately allowed to be longer than the realized
	 * day, which is what happens when an agent stalls before finishing its plan.
	 */
	private static Table analyse(double[] plannedTypicals, Act... acts) {
		Config config = config();
		Scenario scenario = ScenarioUtils.createScenario(config);
		scenario.getPopulation().addPerson(scenario.getPopulation().getFactory().createPerson(PID));

		ScoringParametersForPerson params = person -> new ScoringParameters.Builder(
			config.scoring(), config.scoring().getScoringParameters(null), config.scenario()).build();

		double[] noAnchors = new double[plannedTypicals.length];
		Arrays.fill(noAnchors, Double.NaN);
		VTTSHandler handler = new VTTSHandler(scenario, params,
			Map.of(PID, new VTTSHandler.PlannedActivities(plannedTypicals, noAnchors, noAnchors)), false);

		for (Act act : acts) {
			if (act.start() != null) {
				handler.handleEvent(new ActivityStartEvent(act.start(), PID, LINK, null, act.type(), null));
			}
			if (act.end() != null) {
				handler.handleEvent(new ActivityEndEvent(act.end(), PID, LINK, null, act.type(), null));
				handler.handleEvent(new PersonDepartureEvent(act.end(), PID, LINK, "car", "car"));
			}
		}
		handler.computeFinalVTTS();
		return handler.getTablesawTripsTable();
	}

	private static String activity(Table t, int row) { return t.stringColumn(HeadersKN.activity).get(row); }
	private static double actDur(Table t, int row) { return t.doubleColumn(HeadersKN.activityDuration).get(row); }
	private static double typDur(Table t, int row) { return t.doubleColumn(HeadersKN.typicalDuration).get(row); }
	private static boolean afterDayEnd(Table t, int row) { return t.booleanColumn(HeadersKN.afterDayEnd).get(row); }

	/**
	 * A middle activity: the window is simply arrival to departure, and the typical duration is the one planned for
	 * the activity at that position. Note the day's first activity gets no row at all -- a row is keyed to an
	 * incoming trip, and the first activity has none.
	 */
	@Test
	void middleActivityIsScoredFromArrivalToDeparture() {
		Table t = analyse(new double[]{8 * H, 6 * H, 4 * H},
			first("home_morning", 8 * H),
			middle("work", 9 * H, 17 * H),
			unfinished("home_evening", 18 * H));

		assertEquals(2, t.rowCount(), "the day's first activity has no incoming trip, so no row");
		assertEquals("work", activity(t, 0));
		assertEquals(8., actDur(t, 0), 1e-9);
		assertEquals(6., typDur(t, 0), 1e-9, "typical duration of the aligned plan activity, not the config's 2h");
	}

	/**
	 * The activity an agent is at when the simulation ends, on a day that does NOT wrap: scored from arrival to the
	 * end of the simulation period, NOT to the first activity's end + 24h. This is the only case that occurs in the
	 * Dresden scenario, because EncodeTypicalDuration splits home into home_morning / home_evening, so the first and
	 * last activity type never match.
	 */
	@Test
	void endOfDayActivityIsScoredToTheSimulationPeriodEndWhenTheDayDoesNotWrap() {
		Table t = analyse(new double[]{8 * H, 6 * H, 4 * H},
			first("home_morning", 8 * H),
			middle("work", 9 * H, 17 * H),
			unfinished("home_evening", 18 * H));

		assertEquals("home_evening", activity(t, 1));
		assertEquals((DAY_END - 18 * H) / H, actDur(t, 1), 1e-9);   // 27:00 - 18:00 = 9h
		assertEquals(9., actDur(t, 1), 1e-9);
		assertFalse(afterDayEnd(t, 1));

		double wrapAround = (8 * H + 24 * H - 18 * H) / H;          // 14h -- the rule this branch must NOT use
		org.junit.jupiter.api.Assertions.assertNotEquals(wrapAround, actDur(t, 1), 1e-9);
	}

	/**
	 * The same activity on a day that DOES wrap (first and last activity share a type): the scoring folds the two into
	 * one activity running to the first activity's end + 24h, and the reported duration follows. Dead in this scenario
	 * (see above), live in the code, hence recorded here.
	 */
	@Test
	void endOfDayActivityWrapsAroundWhenFirstAndLastTypeMatch() {
		Table t = analyse(new double[]{8 * H, 6 * H, 4 * H},
			first("home", 8 * H),
			middle("work", 9 * H, 17 * H),
			unfinished("home", 18 * H));

		assertEquals("home", activity(t, 1));
		assertEquals((8 * H + 24 * H - 18 * H) / H, actDur(t, 1), 1e-9);   // 14h, spanning midnight
		assertEquals(14., actDur(t, 1), 1e-9);
		assertFalse(afterDayEnd(t, 1));
	}

	/**
	 * Arriving after the day end leaves the scoring integrating over a negative interval. That is reported as such
	 * rather than papered over, and flagged -- the day-end constraint is what binds, not the agent's preferences.
	 */
	@Test
	void arrivingAfterTheDayEndGivesANegativeWindowAndIsFlagged() {
		Table t = analyse(new double[]{8 * H, 6 * H, 4 * H},
			first("home_morning", 8 * H),
			middle("work", 9 * H, 17 * H),
			unfinished("home_evening", 28 * H));       // 28:00, an hour past the 27:00 day end

		assertEquals("home_evening", activity(t, 1));
		assertEquals(-1., actDur(t, 1), 1e-9);
		assertTrue(afterDayEnd(t, 1));
	}

	/**
	 * An agent that ends an activity and then stalls on the following trip: the activity it ended has an end time, so
	 * it went down the middle-activity branch and is NOT treated as an end-of-day activity -- note {@code afterDayEnd}
	 * stays false even though it starts past the day end, since that flag only exists on the other branch. The stalled
	 * trip itself is dropped (no arrival, so nothing to score), leaving one row per completed activity.
	 */
	@Test
	void agentStalledAfterEndingAnActivityIsScoredOnTheMiddleActivityBranch() {
		Table t = analyse(new double[]{8 * H, 6 * H, 4 * H, 2 * H},
			first("home_morning", 8 * H),
			middle("work", 9 * H, 17 * H),
			middle("shop", 28 * H, 28 * H));          // arrives past the day end, leaves at once, then stalls

		assertEquals(2, t.rowCount(), "the trip the agent stalled on produces no row");
		assertEquals("shop", activity(t, 1));
		assertEquals(0., actDur(t, 1), 1e-9, "arrival to departure, not to the day end");
		assertFalse(afterDayEnd(t, 1), "the flag only exists on the end-of-day branch");
	}

	/**
	 * An agent that stalls mid-plan is at the end-of-day branch only because the simulation ended, not because it
	 * reached its last activity. Its typical duration must therefore come from the plan activity it is actually at,
	 * not from the plan's terminal slot -- which belongs to an activity it never reached, and whose typical duration
	 * encodes "from its start to the end of the period" and so is typically much longer.
	 */
	@Test
	void endOfDayActivityTakesTheAlignedTypicalNotThePlansTerminalSlot() {
		Table t = analyse(new double[]{8 * H, 6 * H, 0.5 * H, 7 * H},   // 4 planned, only 3 reached
			first("home_morning", 8 * H),
			middle("work", 9 * H, 17 * H),
			unfinished("leisure", 18 * H));

		assertEquals("leisure", activity(t, 1));
		assertEquals(0.5, typDur(t, 1), 1e-9, "the aligned plan activity (index 2)");
		org.junit.jupiter.api.Assertions.assertNotEquals(7., typDur(t, 1), 1e-9, "not the plan's terminal slot (index 3)");
		assertEquals(9., actDur(t, 1), 1e-9, "still scored to the day end: the simulation ended while it was here");
	}
}
