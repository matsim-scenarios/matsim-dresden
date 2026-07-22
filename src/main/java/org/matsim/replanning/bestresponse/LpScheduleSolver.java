package org.matsim.replanning.bestresponse;

import org.apache.commons.math3.exception.MathRuntimeException;
import org.apache.commons.math3.optim.MaxIter;
import org.apache.commons.math3.optim.PointValuePair;
import org.apache.commons.math3.optim.linear.LinearConstraint;
import org.apache.commons.math3.optim.linear.LinearConstraintSet;
import org.apache.commons.math3.optim.linear.LinearObjectiveFunction;
import org.apache.commons.math3.optim.linear.NonNegativeConstraint;
import org.apache.commons.math3.optim.linear.Relationship;
import org.apache.commons.math3.optim.linear.SimplexSolver;
import org.apache.commons.math3.optim.nonlinear.scalar.GoalType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Solves the {@link ScheduleProblem} exactly as a linear program (Apache Commons Math {@link SimplexSolver}).
 *
 * <h3>Formulation</h3>
 * With m = n-1 scheduled activities (the open last activity has no variable, see {@link ScheduleProblem}), the
 * variables are the durations {@code d_0 .. d_{m-1}} plus, per piecewise-linear penalty term, a non-negative
 * under/over split pair. All variables are non-negative; every constraint is an equality defining one split:
 * <ul>
 *   <li>duration term of activity i: {@code d_i + u1 + u2 - o = t*_i} with {@code u1 <= t*_i/2}, cost
 *   {@code durShortSlope*u1 + durVeryShortSlope*u2 + durLongSlope*o} (the convex two-segment under-run penalty; the
 *   cheaper {@code u1} fills up first in the minimization, so the split is automatically consistent);</li>
 *   <li>duration term of the last activity (non-wrap-around plans): the last duration is not a free variable but
 *   follows from the others as {@code d_last = dayEnd - start_last = (dayEnd - dayStart - totalTravel) - sum_i d_i},
 *   so its deviation from its typical duration enters the objective like everyone else's:
 *   {@code -sum_(i=0..m-1) d_i + u1 + u2 - o = t*_last - (dayEnd - dayStart - totalTravel)}, again with
 *   {@code u1 <= t*_last/2};</li>
 *   <li>joint wrap-around term (replaces the individual terms of both the first and the last activity): the pair's
 *   total duration {@code D = d_0 + (dayEnd - start_last)} is affine with {@code d_0} cancelling, so
 *   {@code -sum_(i=1..m-1) d_i + u1 + u2 - o = t*_wrap - (dayEnd - dayStart - totalTravel)}, again with
 *   {@code u1 <= t*_wrap/2};</li>
 *   <li>end-time anchor of activity i: {@code end_i = dayStart + sum_(j<=i)(travel_j + d_j)}, split as
 *   {@code sum_(j<=i) d_j + a - b = e*_i - dayStart - sum_(j<=i) travel_j}, cost
 *   {@code endEarlySlope*a + endLateSlope*b}.</li>
 * </ul>
 * All costs are non-negative, so the LP is bounded; the splits make every equality satisfiable, so it is feasible.
 * The problems are tiny (a handful of activities), so a simplex solve is far below a millisecond. Should the solver
 * fail anyway, the typical durations are returned as a fallback (the separable optimum of the duration terms alone).
 * <p>
 * Stateless and thread-safe: a fresh {@link SimplexSolver} is created per call.
 */
public final class LpScheduleSolver implements ScheduleSolver {

	private static final Logger log = LogManager.getLogger( LpScheduleSolver.class );

	private static final int MAX_ITERATIONS = 1000;

	@Override
	public double[] solve( ScheduleProblem problem ) {
		int n = problem.activities.size();
		int m = n - 1; // scheduled activities; the last one has no variable

		double totalTravel = 0.;
		for ( ScheduleProblem.Act act : problem.activities ) {
			totalTravel += act.travelTimeBefore;
		}

		// The joint wrap term is only a term when it contains a variable (some middle activity); for a two-activity
		// wrap plan it is a constant and drops out.
		boolean jointWrapTerm = problem.wrapAround && m >= 2;
		// Without wrap-around the last activity is a separate activity whose duration follows from the others
		// (dayEnd - start_last); its deviation from its typical duration is penalized like everyone else's.
		boolean lastDurationTerm = !problem.wrapAround && m >= 1;

		int nVars = m;
		for ( int i = 0; i < m; i++ ) {
			if ( !( i == 0 && problem.wrapAround ) ) {
				nVars += 3; // individual duration term
			}
			if ( problem.activities.get( i ).targetEndTime.isDefined() ) {
				nVars += 2; // end-time anchor term
			}
		}
		if ( jointWrapTerm ) {
			nVars += 3;
		}
		if ( lastDurationTerm ) {
			nVars += 3;
		}

		double[] cost = new double[nVars];
		List<LinearConstraint> constraints = new ArrayList<>();
		int nextVar = m;

		double cumTravel = 0.;
		for ( int i = 0; i < m; i++ ) {
			ScheduleProblem.Act act = problem.activities.get( i );
			cumTravel += act.travelTimeBefore;

			if ( !( i == 0 && problem.wrapAround ) ) {
				// d_i + u1 + u2 - o = t*_i, u1 <= t*_i/2
				int u1 = nextVar++, u2 = nextVar++, o = nextVar++;
				double[] row = new double[nVars];
				row[i] = 1.;
				row[u1] = 1.;
				row[u2] = 1.;
				row[o] = -1.;
				constraints.add( new LinearConstraint( row, Relationship.EQ, act.typicalDuration ) );
				double[] cap = new double[nVars];
				cap[u1] = 1.;
				constraints.add( new LinearConstraint( cap, Relationship.LEQ, act.typicalDuration / 2. ) );
				cost[u1] = act.durShortSlope;
				cost[u2] = act.durVeryShortSlope;
				cost[o] = act.durLongSlope;
			}

			if ( act.targetEndTime.isDefined() ) {
				// sum_(j<=i) d_j + a - b = e*_i - dayStart - sum_(j<=i) travel_j
				int a = nextVar++, b = nextVar++;
				double[] row = new double[nVars];
				for ( int j = 0; j <= i; j++ ) {
					row[j] = 1.;
				}
				row[a] = 1.;
				row[b] = -1.;
				constraints.add( new LinearConstraint( row, Relationship.EQ,
					act.targetEndTime.seconds() - problem.dayStart - cumTravel ) );
				cost[a] = act.endEarlySlope;
				cost[b] = act.endLateSlope;
			}
		}

		if ( jointWrapTerm ) {
			// -sum_(i=1..m-1) d_i + u1 + u2 - o = t*_wrap - (dayEnd - dayStart - totalTravel), u1 <= t*_wrap/2
			ScheduleProblem.Act first = problem.activities.get( 0 );
			int u1 = nextVar++, u2 = nextVar++, o = nextVar++;
			double[] row = new double[nVars];
			for ( int i = 1; i < m; i++ ) {
				row[i] = -1.;
			}
			row[u1] = 1.;
			row[u2] = 1.;
			row[o] = -1.;
			constraints.add( new LinearConstraint( row, Relationship.EQ,
				first.typicalDuration - ( problem.dayEnd - problem.dayStart - totalTravel ) ) );
			double[] cap = new double[nVars];
			cap[u1] = 1.;
			constraints.add( new LinearConstraint( cap, Relationship.LEQ, first.typicalDuration / 2. ) );
			cost[u1] = first.durShortSlope;
			cost[u2] = first.durVeryShortSlope;
			cost[o] = first.durLongSlope;
		}

		if ( lastDurationTerm ) {
			// d_last = (dayEnd - dayStart - totalTravel) - sum_(i=0..m-1) d_i, so:
			// -sum_(i=0..m-1) d_i + u1 + u2 - o = t*_last - (dayEnd - dayStart - totalTravel), u1 <= t*_last/2
			ScheduleProblem.Act last = problem.activities.get( n - 1 );
			int u1 = nextVar++, u2 = nextVar++, o = nextVar++;
			double[] row = new double[nVars];
			for ( int i = 0; i < m; i++ ) {
				row[i] = -1.;
			}
			row[u1] = 1.;
			row[u2] = 1.;
			row[o] = -1.;
			constraints.add( new LinearConstraint( row, Relationship.EQ,
				last.typicalDuration - ( problem.dayEnd - problem.dayStart - totalTravel ) ) );
			double[] cap = new double[nVars];
			cap[u1] = 1.;
			constraints.add( new LinearConstraint( cap, Relationship.LEQ, last.typicalDuration / 2. ) );
			cost[u1] = last.durShortSlope;
			cost[u2] = last.durVeryShortSlope;
			cost[o] = last.durLongSlope;
		}

		double[] durations;
		if ( constraints.isEmpty() ) {
			durations = fallback( problem ); // degenerate (e.g. two-activity wrap plan without any anchor)
		} else {
			try {
				PointValuePair solution = new SimplexSolver().optimize(
					new MaxIter( MAX_ITERATIONS ),
					new LinearObjectiveFunction( cost, 0. ),
					new LinearConstraintSet( constraints ),
					GoalType.MINIMIZE,
					new NonNegativeConstraint( true ) );
				durations = new double[n];
				for ( int i = 0; i < m; i++ ) {
					durations[i] = Math.max( 0., solution.getPoint()[i] ); // clamp numerical noise
				}
			} catch ( MathRuntimeException e ) {
				log.warn( "Simplex failed on a {}-activity schedule ({}); falling back to typical durations.", n, e.toString() );
				durations = fallback( problem );
			}
		}

		// The last activity's duration follows from the others: from its arrival to the end of the day. Write-back
		// leaves the activity itself unscheduled (open overnight activity), but the value is the one whose deviation
		// from the typical duration was penalized above (non-wrap plans) resp. entered the joint wrap term.
		double startLast = problem.dayStart + totalTravel;
		for ( int i = 0; i < m; i++ ) {
			startLast += durations[i];
		}
		durations[n - 1] = Math.max( 0., problem.dayEnd - startLast );
		return durations;
	}

	/** The separable optimum of the duration terms alone: every activity at its typical duration. */
	private static double[] fallback( ScheduleProblem problem ) {
		double[] durations = new double[problem.activities.size()];
		for ( int i = 0; i < durations.length; i++ ) {
			durations[i] = Math.max( 0., problem.activities.get( i ).typicalDuration );
		}
		return durations;
	}
}
