package org.matsim.replanning.bestresponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.population.algorithms.MutateActivityTimeAllocation;
import org.matsim.core.population.algorithms.PlanAlgorithm;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.misc.OptionalTime;
import org.matsim.prepare.EncodeTypicalDuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Best-response replanning algorithm: instead of randomly mutating one end time (as
 * {@code MutateActivityTimeAllocation} does), it re-plans the whole day at once by extracting a linearized scheduling
 * problem ({@link ScheduleProblem}) from the plan, solving it ({@link ScheduleSolver}), and writing the resulting
 * schedule back -- keeping each activity in its class (end-time-based activities get an end time, duration-based ones a
 * maximum duration). Travel times are read from the plan and taken as fixed (no time-dependent travel times yet).
 * <p>
 * One instance per worker thread (see {@link BestResponseScheduleModule#getPlanAlgoInstance()}); each carries its own
 * {@link Random} so it is thread-safe.
 */
public final class BestResponseSchedulePlanAlgorithm implements PlanAlgorithm {

	private static final Logger log = LogManager.getLogger( BestResponseSchedulePlanAlgorithm.class );

	/** Fallback preferred duration (s) when neither the typical-duration attribute nor the config carries one. */
	private static final double FALLBACK_TYPICAL_DURATION = 2 * 3600.;

	private final ScoringConfigGroup scoringConfigGroup;
	private final ScheduleSolver solver;
	private final double randomErrorSigma;
	private final Random random;

	// Charypar-Nagel slopes translated to utils/second, computed once.
	private final double durSlope;       // marginal utility of performing (beta_perf); used for both under- and over-run in the mockup
	private final double endLateSlope;   // |late arrival| penalty
	private final double endEarlySlope;  // |early departure| penalty

	public BestResponseSchedulePlanAlgorithm( ScoringConfigGroup scoringConfigGroup, ScheduleSolver solver,
											  double randomErrorSigma, Random random ) {
		this.scoringConfigGroup = scoringConfigGroup;
		this.solver = solver;
		this.randomErrorSigma = randomErrorSigma;
		this.random = random;
		this.durSlope = scoringConfigGroup.getPerforming_utils_hr() / 3600.;
		this.endLateSlope = Math.abs( scoringConfigGroup.getLateArrival_utils_hr() ) / 3600.;
		this.endEarlySlope = Math.abs( scoringConfigGroup.getEarlyDeparture_utils_hr() ) / 3600.;
	}

	@Override
	public void run( Plan plan ) {
		List<Activity> activities = TripStructureUtils.getActivities( plan, TripStructureUtils.StageActivityHandling.ExcludeStageActivities );
		if ( activities.size() < 2 ) {
			return; // stay-home / single activity: nothing to schedule.
		}

		double[] travelBefore = travelTimesBeforeEachActivity( plan, activities.size() );

		ScheduleProblem problem = extractProblem( activities, travelBefore );
		double[] durations = solver.solve( problem );

		writeBack( activities, travelBefore, durations, problem.dayStart );
	}

	/** Travel time (s) of the trip arriving at each activity; index 0 is the first activity and is always 0. */
	private static double[] travelTimesBeforeEachActivity( Plan plan, int nActivities ) {
		double[] travelBefore = new double[nActivities];
		List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips( plan );
		for ( int j = 0; j < trips.size() && j + 1 < nActivities; j++ ) {
			double t = 0.;
			for ( Leg leg : trips.get( j ).getLegsOnly() ) {
				t += leg.getTravelTime().orElse( 0. );
			}
			travelBefore[j + 1] = t;
		}
		return travelBefore;
	}

	private ScheduleProblem extractProblem( List<Activity> activities, double[] travelBefore ) {
		List<ScheduleProblem.Act> acts = new ArrayList<>( activities.size() );
		for ( int i = 0; i < activities.size(); i++ ) {
			Activity activity = activities.get( i );
			boolean lastActivity = i == activities.size() - 1;
			boolean endTimeBased = activity.getEndTime().isDefined() ||
				( !activity.getMaximumDuration().isDefined() && !lastActivity );

			double typicalDuration = typicalDuration( activity );
			double randomError = lastActivity ? 0. : random.nextGaussian() * randomErrorSigma;

			acts.add( new ScheduleProblem.Act(
				activity.getType(), endTimeBased, lastActivity, travelBefore[i], typicalDuration,
				durSlope, durSlope, targetEndTime( activity ), endEarlySlope, endLateSlope, randomError ) );
		}
		return new ScheduleProblem( acts, 0. );
	}

	/** Preferred duration: the per-activity typical-duration attribute (see {@link EncodeTypicalDuration}), else the type's config value, else a fallback. */
	private double typicalDuration( Activity activity ) {
		Object attribute = activity.getAttributes().getAttribute( EncodeTypicalDuration.TYPICAL_DURATION );
		if ( attribute instanceof Number number && number.doubleValue() > 0 ) {
			return number.doubleValue();
		}
		ScoringConfigGroup.ActivityParams params = scoringConfigGroup.getActivityParams( activity.getType() );
		if ( params != null && params.getTypicalDuration().isDefined() ) {
			return params.getTypicalDuration().seconds();
		}
		return FALLBACK_TYPICAL_DURATION;
	}

	/**
	 * Desired end time (scheduling anchor): the {@code initialEndTime} attribute (stored by the random time mutator when
	 * mutate-around-initial is on), else the activity's current end time. TODO(project): stamp a stable target-end-time
	 * attribute in preprocessing (as {@link EncodeTypicalDuration} does for typical durations) rather than relying on a
	 * mutator side effect or the drifting current value.
	 */
	private static OptionalTime targetEndTime( Activity activity ) {
		Object attribute = activity.getAttributes().getAttribute( MutateActivityTimeAllocation.INITIAL_END_TIME_ATTRIBUTE );
		if ( attribute instanceof Number number ) {
			return OptionalTime.defined( number.doubleValue() );
		}
		return activity.getEndTime();
	}

	/** Rebuild the schedule from the chosen durations and write it back, each activity staying in its class. */
	private static void writeBack( List<Activity> activities, double[] travelBefore, double[] durations, double dayStart ) {
		double clock = dayStart;
		for ( int i = 0; i < activities.size(); i++ ) {
			Activity activity = activities.get( i );
			double start = clock + travelBefore[i];
			double end = start + durations[i];
			clock = end;

			boolean lastActivity = i == activities.size() - 1;
			if ( lastActivity ) {
				// the open last (overnight) activity absorbs the rest of the day; leave it unscheduled.
				activity.setEndTimeUndefined();
				activity.setMaximumDurationUndefined();
				activity.setStartTimeUndefined();
			} else if ( activity.getEndTime().isDefined() || !activity.getMaximumDuration().isDefined() ) {
				// end-time-based activity
				activity.setEndTime( end );
				activity.setMaximumDurationUndefined();
				activity.setStartTimeUndefined();
			} else {
				// duration-based activity
				activity.setMaximumDuration( durations[i] );
				activity.setEndTimeUndefined();
				activity.setStartTimeUndefined();
			}
		}
	}
}
