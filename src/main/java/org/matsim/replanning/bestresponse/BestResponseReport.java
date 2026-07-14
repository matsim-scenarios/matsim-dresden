package org.matsim.replanning.bestresponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Standalone diagnostic: read a population, run the {@link BestResponseSchedulePlanAlgorithm} (the same optimizer the
 * replanning strategy uses) on every selected plan, and summarize what changed -- how far activities moved, and whether
 * the pathological zero-duration / long-tail-overnight schedules were repaired. Does not run a simulation, so it is a
 * fast way to sanity-check the optimizer on any population (the post-prepare initial plans, the raw source, or a garbled
 * run output).
 * <p>
 * The input population should carry per-activity typical durations (see {@link org.matsim.prepare.EncodeTypicalDuration});
 * without them every activity falls back to a generic typical duration and the numbers are not meaningful.
 */
@CommandLine.Command(name = "best-response-report",
	description = "Apply the best-response scheduling optimizer to a population and summarize what changed.")
public final class BestResponseReport implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger( BestResponseReport.class );

	@CommandLine.Parameters(arity = "1", paramLabel = "INPUT", description = "Path to input population")
	private Path input;

	@CommandLine.Option(names = "--output", description = "Optional path to write the optimized population to.")
	private Path output;

	@CommandLine.Option(names = "--sigma", description = "Standard deviation (seconds) of the random perturbation " +
		"added to the target end times. Default 0 gives the deterministic best response, which is easiest to " +
		"interpret.", defaultValue = "0")
	private double sigma;

	@CommandLine.Option(names = "--simulation-period-in-days", description = "Effective end of day, as a multiple of " +
		"24h; reference point for the last activity's wrap-around share. Pass the same value the scenario uses.", defaultValue = "1.0")
	private double simulationPeriodInDays;

	@CommandLine.Option(names = "--subpopulation", description = "Only process persons of this subpopulation, " +
		"mirroring the strategysettings the replanning strategy runs under; empty = all.", defaultValue = "person")
	private String subpopulation;

	@CommandLine.Option(names = "--seed", description = "RNG seed (only matters when --sigma > 0).", defaultValue = "4711")
	private long seed;

	@CommandLine.Option(names = "--performing", description = "marginalUtilityOfPerforming (utils/h) for the optimizer's " +
		"penalty slopes; defaults to the Dresden value.", defaultValue = "6.0")
	private double performing;

	@CommandLine.Option(names = "--day-end-hours", description = "An activity chain whose last activity starts after this " +
		"hour is counted as an overnight overshoot.", defaultValue = "27.0")
	private double dayEndHours;

	/** One activity's realized slot on the timeline (seconds). */
	private record ActSchedule(double start, double end, double duration) {}

	public static void main( String[] args ) {
		new BestResponseReport().execute( args );
	}

	@Override
	public Integer call() throws Exception {
		if ( !Files.exists( input ) ) {
			log.error( "Input population does not exist: {}", input );
			return 2;
		}

		Population population = PopulationUtils.readPopulation( input.toString() );

		Config config = ConfigUtils.createConfig();
		config.scoring().setPerforming_utils_hr( performing );
		BestResponseSchedulePlanAlgorithm optimizer = new BestResponseSchedulePlanAlgorithm(
			config.scoring(), new LpScheduleSolver(), sigma, simulationPeriodInDays * 24. * 3600., new Random( seed ) );

		List<Double> endMoveMin = new ArrayList<>();
		List<Double> durChangeMin = new ArrayList<>();
		List<Double> lastStartBeforeH = new ArrayList<>();
		List<Double> lastStartAfterH = new ArrayList<>();
		int zeroBefore = 0, zeroAfter = 0, overnightBefore = 0, overnightAfter = 0;
		int processed = 0, skipped = 0;

		for ( Person person : population.getPersons().values() ) {
			if ( !subpopulation.isEmpty() && !subpopulation.equals( PopulationUtils.getSubpopulation( person ) ) ) {
				continue;
			}
			Plan plan = person.getSelectedPlan();
			List<Activity> activities = TripStructureUtils.getActivities( plan, TripStructureUtils.StageActivityHandling.ExcludeStageActivities );
			if ( activities.size() < 2 ) {
				skipped++;
				continue;
			}
			double[] travelBefore = BestResponseSchedulePlanAlgorithm.travelTimesBeforeEachActivity( plan, activities.size() );

			List<ActSchedule> before = schedule( activities, travelBefore );
			optimizer.run( plan ); // mutates the plan's activities in place
			List<ActSchedule> after = schedule( activities, travelBefore );

			int last = activities.size() - 1;
			for ( int i = 0; i < last; i++ ) { // non-last activities
				endMoveMin.add( Math.abs( after.get( i ).end() - before.get( i ).end() ) / 60. );
				durChangeMin.add( Math.abs( after.get( i ).duration() - before.get( i ).duration() ) / 60. );
				if ( before.get( i ).duration() < 60. ) zeroBefore++;
				if ( after.get( i ).duration() < 60. ) zeroAfter++;
			}
			double lastStartBefore = before.get( last ).start() / 3600.;
			double lastStartAfter = after.get( last ).start() / 3600.;
			lastStartBeforeH.add( lastStartBefore );
			lastStartAfterH.add( lastStartAfter );
			if ( lastStartBefore > dayEndHours ) overnightBefore++;
			if ( lastStartAfter > dayEndHours ) overnightAfter++;
			processed++;
		}

		StringBuilder sb = new StringBuilder( System.lineSeparator() );
		sb.append( "=== Best-response optimizer report ===" ).append( System.lineSeparator() );
		sb.append( "input:            " ).append( input ).append( System.lineSeparator() );
		sb.append( String.format( "persons:          %d processed, %d skipped (stay-home / single activity), subpopulation: %s%n",
			processed, skipped, subpopulation.isEmpty() ? "all" : subpopulation ) );
		sb.append( String.format( "sigma:            %.0f s (anchor perturbation)   seed: %d   performing: %.1f u/h%n", sigma, seed, performing ) );
		sb.append( System.lineSeparator() );
		sb.append( "Activity end-time movement (min):  " ).append( stats( endMoveMin ) ).append( System.lineSeparator() );
		sb.append( "Activity duration change  (min):   " ).append( stats( durChangeMin ) ).append( System.lineSeparator() );
		sb.append( System.lineSeparator() );
		sb.append( String.format( "Zero-duration activities (<60s, non-last):        before=%d  ->  after=%d%n", zeroBefore, zeroAfter ) );
		sb.append( String.format( "Overnight overshoot (last act starts after %.0fh): before=%d  ->  after=%d%n", dayEndHours, overnightBefore, overnightAfter ) );
		sb.append( String.format( "Last activity start (h):  before[max=%.1f p99=%.1f]  ->  after[max=%.1f p99=%.1f]%n",
			max( lastStartBeforeH ), pct( lastStartBeforeH, 0.99 ), max( lastStartAfterH ), pct( lastStartAfterH, 0.99 ) ) );

		log.info( sb.toString() );

		if ( output != null ) {
			log.info( "Writing optimized population to {}", output );
			PopulationUtils.writePopulation( population, output.toString() );
		}

		return 0;
	}

	/**
	 * Reconstruct the realized schedule of a plan by walking the activity chain with the fixed travel times, applying
	 * the {@code tryEndTimeThenDuration} semantics with a {@code max(0, ...)} clamp: an end-time-based activity whose end
	 * time falls before its (travel-determined) arrival is squeezed to zero duration -- exactly the pathology we detect.
	 */
	private static List<ActSchedule> schedule( List<Activity> activities, double[] travelBefore ) {
		List<ActSchedule> out = new ArrayList<>( activities.size() );
		double clock = 0.;
		for ( int i = 0; i < activities.size(); i++ ) {
			Activity activity = activities.get( i );
			double start = clock + travelBefore[i];
			double end;
			if ( activity.getEndTime().isDefined() ) {
				end = Math.max( start, activity.getEndTime().seconds() );
			} else if ( activity.getMaximumDuration().isDefined() ) {
				end = start + activity.getMaximumDuration().seconds();
			} else {
				end = start; // open (last) activity: absorbs the rest of the day; only its start is used.
			}
			out.add( new ActSchedule( start, end, end - start ) );
			clock = end;
		}
		return out;
	}

	private static String stats( List<Double> xs ) {
		return String.format( "mean=%.1f  median=%.1f  p90=%.1f  max=%.1f", mean( xs ), pct( xs, 0.5 ), pct( xs, 0.9 ), max( xs ) );
	}

	private static double mean( List<Double> xs ) {
		if ( xs.isEmpty() ) return Double.NaN;
		double total = 0.;
		for ( double x : xs ) total += x;
		return total / xs.size();
	}

	private static double max( List<Double> xs ) {
		double m = Double.NaN;
		for ( double x : xs ) m = Double.isNaN( m ) ? x : Math.max( m, x );
		return m;
	}

	private static double pct( List<Double> xs, double p ) {
		if ( xs.isEmpty() ) return Double.NaN;
		List<Double> sorted = new ArrayList<>( xs );
		Collections.sort( sorted );
		int idx = (int) Math.round( p * ( sorted.size() - 1 ) );
		return sorted.get( Math.min( sorted.size() - 1, Math.max( 0, idx ) ) );
	}
}
