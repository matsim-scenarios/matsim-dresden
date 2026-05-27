package org.matsim.run.scenarios;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.Config;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.listener.BeforeMobsimListener;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.TripStructureUtils.StageActivityHandling;
import org.matsim.core.scoring.functions.CharyparNagelActivityScoring;
import org.matsim.core.scoring.functions.ScoringParameters;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * Iteration-0 warm-start that pre-relaxes the activity-timing dimension per agent, replacing
 * MATSim's slow co-evolutionary relaxation of departure times with independent per-agent
 * Metropolis-Hastings (MH) sampling. This is a Java port of the Python "simulator of the simulator"
 * (see predict_departures.py / compare_scenarios.py in the agimo/vibes repo): because the timing
 * dimension has no cross-agent coupling once travel times are fixed, each agent can sample its own
 * activity end-times against the real Charypar-Nagel scoring function.
 *
 * <p>For every {@code person}-subpopulation agent we run {@link #N_ITERATIONS} MH iterations whose
 * proposal kernel is MATSim's own {@code TimeAllocationMutator} (uniform shift within
 * {@code timeAllocationMutator.mutationRange}, see
 * {@link org.matsim.core.population.algorithms.MutateActivityTimeAllocation#mutateTime}) and whose
 * target is the genuine {@link CharyparNagelActivityScoring}. The plan is then overwritten with the
 * best-scoring timing found (per-agent optimum).
 *
 * <p>We hook at {@link BeforeMobsimListener} of the first iteration (after {@code PrepareForSim}
 * has routed the plans) so the timing chaining uses the routed leg travel times the mobsim will
 * actually simulate with.
 *
 * <p>Only the activity-scoring component is evaluated: leg/mode/money/pt-fare terms are invariant
 * under end-time mutation (fixed modes, routes, travel times), so they cancel in both the MH
 * acceptance ratio and the per-agent argmax. The sampled distribution and the optimum over timing
 * are therefore identical to those of the full score.
 */
public class MHTimeAllocationWarmStart implements BeforeMobsimListener {

	private static final Logger log = LogManager.getLogger(MHTimeAllocationWarmStart.class);

	/** Number of MH proposals per agent (cf. the Python sampler's 4000). */
	private static final int N_ITERATIONS = 4000;

	/** Throttle for the (info-level) diagnostic about infeasible input timings. */
	private static final AtomicInteger INFEASIBLE_LOGGED = new AtomicInteger();

	private final Population population;
	private final ScoringParametersForPerson scoringParametersForPerson;
	private final double mutationRange;
	private final double mutationRangeStep;
	private final double latestEnd;
	private final double beta;
	private final int numThreads;

	private boolean done = false;

	@Inject
	MHTimeAllocationWarmStart(Population population, ScoringParametersForPerson scoringParametersForPerson, Config config) {
		this.population = population;
		this.scoringParametersForPerson = scoringParametersForPerson;
		this.mutationRange = config.timeAllocationMutator().getMutationRange();
		this.mutationRangeStep = Math.max(1.0, config.timeAllocationMutator().getMutationRangeStep());
		this.latestEnd = config.timeAllocationMutator().getLatestActivityEndTime();
		this.beta = config.scoring().getBrainExpBeta();
		this.numThreads = Math.max(1, config.global().getNumberOfThreads());
	}

	@Override
	public void notifyBeforeMobsim(BeforeMobsimEvent event) {
		if (done) {
			return;
		}
		done = true;
		run();
	}

	private void run() {
//		Collect the cohort and snapshot each agent's ScoringParameters single-threaded: the bound
//		ScoringParametersForPerson (IncomeDependentUtilityOfMoneyPersonScoringParameters) caches into
//		a non-thread-safe IdMap and writes a person attribute as a side effect, so it must not be
//		called concurrently. The resulting ScoringParameters are immutable and safe to read in parallel.
		List<Person> cohort = new ArrayList<>();
		Map<Id<Person>, ScoringParameters> paramsByPerson = new HashMap<>();
		for (Person p : population.getPersons().values()) {
			if (!"person".equals(p.getAttributes().getAttribute("subpopulation"))) {
				continue;
			}
			if (p.getSelectedPlan().getPlanElements().size() == 1) {
//				stay-home agent: nothing to relax
				continue;
			}
			paramsByPerson.put(p.getId(), scoringParametersForPerson.getScoringParameters(p));
			cohort.add(p);
		}

		log.info("MH time-allocation warm-start: {} person-agents, {} MH iterations each, mutationRange={}s, beta={}, {} threads",
			cohort.size(), N_ITERATIONS, mutationRange, beta, numThreads);

		DoubleAdder scoreBefore = new DoubleAdder();
		DoubleAdder scoreAfter = new DoubleAdder();
		LongAdder processed = new LongAdder();
		LongAdder infeasibleInput = new LongAdder();

		ForkJoinPool pool = new ForkJoinPool(numThreads);
		try {
			pool.submit(() -> cohort.parallelStream().forEach(p -> {
				double[] beforeAfter = warmStartAgent(p, paramsByPerson.get(p.getId()));
				if (beforeAfter != null && Double.isFinite(beforeAfter[0]) && Double.isFinite(beforeAfter[1])) {
					scoreBefore.add(beforeAfter[0]);
					scoreAfter.add(beforeAfter[1]);
					processed.add(1);
				} else if (beforeAfter != null) {
//					routed input timing was infeasible (e.g. access/egress travel time exceeds an input
//					activity gap); plan left untouched unless a feasible timing was found
					infeasibleInput.add(1);
				}
			})).get();
		} catch (Exception e) {
			throw new RuntimeException("MH time-allocation warm-start failed", e);
		} finally {
			pool.shutdown();
		}

		long n = Math.max(1, processed.sum());
		log.info("MH warm-start done: relaxed {} agents (mean activity score before = {}, after = {}, mean delta = {}); {} agents had an infeasible routed input timing",
			processed.sum(), scoreBefore.sum() / n, scoreAfter.sum() / n, (scoreAfter.sum() - scoreBefore.sum()) / n, infeasibleInput.sum());
	}

	/**
	 * Runs the MH chain for one agent and writes the best-scoring timing back into its selected plan.
	 * Returns {@code {scoreBefore, scoreAfter}} (activity-only scores of the input timing and of the
	 * chosen optimum, both {@code NEGATIVE_INFINITY} if no feasible timing was ever found), or
	 * {@code null} if the agent has no free end time to optimise.
	 */
	double[] warmStartAgent(Person person, ScoringParameters params) {
		AgentChain chain = new AgentChain(person.getSelectedPlan());
		if (chain.nFreeVars() == 0) {
			return null;
		}

//		Per-agent RNG seeded from the person id: results are reproducible regardless of which thread
//		picks up the agent.
		Random rng = new Random(person.getId().toString().hashCode());

		double[] cur = chain.initialFreeEndTimes();
		double curScore = chain.score(params, cur);
		if (!Double.isFinite(curScore) && INFEASIBLE_LOGGED.getAndIncrement() < 25) {
			log.info("infeasible input {}: {}", person.getId(), chain.describeViolation(cur));
		}
		double scoreBefore = curScore;
		double[] best = cur.clone();
		double bestScore = curScore;

		double[] prop = new double[cur.length];
		int bins = (int) Math.ceil(mutationRange / mutationRangeStep);

		for (int it = 0; it < N_ITERATIONS; it++) {
//			Propose: mutate every free end time, mirroring MutateActivityTimeAllocation.mutateTime
//			(uniform on [-range, +range] in discrete steps), clamped to [0, latestActivityEndTime].
			for (int i = 0; i < cur.length; i++) {
				double t = cur[i] - mutationRange + (2.0 * rng.nextInt(bins) * mutationRangeStep);
				if (t < 0) {
					t = 0;
				}
				if (t > latestEnd) {
					t = latestEnd;
				}
				prop[i] = t;
			}
			double propScore = chain.score(params, prop);
			if (propScore >= curScore || rng.nextDouble() < Math.exp(beta * (propScore - curScore))) {
				System.arraycopy(prop, 0, cur, 0, cur.length);
				curScore = propScore;
				if (curScore > bestScore) {
					bestScore = curScore;
					System.arraycopy(cur, 0, best, 0, cur.length);
				}
			}
		}

//		Write back the best feasible timing. If no feasible state was ever scored (e.g. the routed
//		input timing is itself infeasible), the plan is left untouched rather than overwritten with a
//		degenerate timing.
		if (Double.isFinite(bestScore)) {
			chain.writeBack(best);
		}

		return new double[]{scoreBefore, bestScore};
	}

	/**
	 * Per-agent representation of the timing problem. Real (non-stage) activities are chained by fixed
	 * routed leg travel times. Activities with a defined end time are the free MH variables (exactly
	 * the set TimeAllocationMutator mutates with {@code affectingDuration=false}); activities encoded
	 * by a maximum duration keep that duration and float with their arrival; the overnight last
	 * activity is scored specially (its end is unset, combined with the first activity's end + 24h).
	 */
	private final class AgentChain {
		private final List<Activity> acts;
		private final int lastIdx;
		private final double[] legTravelTime;   // between real act i and i+1, length lastIdx
		private final boolean[] freeEnd;        // per act 0..lastIdx-1: has a defined end time
		private final double[] fixedDuration;   // per act 0..lastIdx-1: max duration when not freeEnd
		private final int[] freeActIdx;         // free-variable index -> activity index
		private final double[] departure;       // scratch: chained departure per act 0..lastIdx-1

		AgentChain(Plan plan) {
			this.acts = TripStructureUtils.getActivities(plan, StageActivityHandling.ExcludeStageActivities);
			int nActs = acts.size();
			this.lastIdx = nActs - 1;

//			Fixed travel time between consecutive real activities = sum of the routed legs' travel
//			times in the trip (stage / pt-interaction activities are excluded from the act list but
//			their legs still count toward the trip travel time).
			List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(plan);
			this.legTravelTime = new double[Math.max(0, lastIdx)];
			for (int i = 0; i < legTravelTime.length; i++) {
				double tt = 0.0;
				if (i < trips.size()) {
					for (Leg leg : trips.get(i).getLegsOnly()) {
						if (leg.getTravelTime().isDefined()) {
							tt += leg.getTravelTime().seconds();
						}
					}
				}
				legTravelTime[i] = tt;
			}

			this.freeEnd = new boolean[Math.max(0, lastIdx)];
			this.fixedDuration = new double[Math.max(0, lastIdx)];
			int nFree = 0;
			for (int i = 0; i < lastIdx; i++) {
				Activity a = acts.get(i);
				if (a.getEndTime().isDefined()) {
					freeEnd[i] = true;
					nFree++;
				} else if (a.getMaximumDuration().isDefined()) {
					fixedDuration[i] = a.getMaximumDuration().seconds();
				}
			}
			this.freeActIdx = new int[nFree];
			for (int i = 0, k = 0; i < lastIdx; i++) {
				if (freeEnd[i]) {
					freeActIdx[k++] = i;
				}
			}
			this.departure = new double[Math.max(0, lastIdx)];
		}

		int nFreeVars() {
			return freeActIdx.length;
		}

		double[] initialFreeEndTimes() {
			double[] x = new double[freeActIdx.length];
			for (int k = 0; k < x.length; k++) {
				x[k] = acts.get(freeActIdx[k]).getEndTime().seconds();
			}
			return x;
		}

		/**
		 * Chain departures for the given free end-time vector into {@link #departure}. Returns the
		 * arrival time at the overnight last activity, or {@code NaN} if the timing is infeasible:
		 * an out-of-bounds end time, an activity with non-positive performed duration, or a
		 * non-positive overnight duration — the V_agent constraints of the Python reference
		 * (predict_departures.py {@code _neg_score_at}). Without them the random walk drifts the
		 * unconstrained first activity to the end-time clamp and "skips" the day, because
		 * Charypar-Nagel performing utility increases monotonically with duration and a windowed
		 * activity performed outside its window collapses to a near-zero penalty.
		 */
		private double chain(double[] x) {
			int k = 0;
			for (int i = 0; i < lastIdx; i++) {
				double arrival = (i == 0) ? 0.0 : departure[i - 1] + legTravelTime[i - 1];
				double dep;
				if (freeEnd[i]) {
					dep = x[k++];
					if (dep <= 0.0 || dep > latestEnd || dep <= arrival) {
						return Double.NaN;
					}
				} else {
					dep = arrival + fixedDuration[i];
					if (dep > latestEnd) {
						return Double.NaN;
					}
				}
				departure[i] = dep;
			}
			double arrivalLast = departure[lastIdx - 1] + legTravelTime[lastIdx - 1];
			if (departure[0] + 24 * 3600.0 <= arrivalLast) {
				return Double.NaN;
			}
			return arrivalLast;
		}

		/**
		 * Activity-only Charypar-Nagel score for the given free end-time vector, driving the real
		 * {@link CharyparNagelActivityScoring} explicitly (handleFirst/handle/handleLast) so the
		 * wrap-around overnight term fires correctly even though the input first activity has a
		 * defined start time. Mutates the activity objects' times as scratch (restored by
		 * {@link #writeBack}).
		 */
		double score(ScoringParameters params, double[] x) {
			double arrivalLast = chain(x);
			if (Double.isNaN(arrivalLast)) {
				return Double.NEGATIVE_INFINITY;
			}
			CharyparNagelActivityScoring sf = new CharyparNagelActivityScoring(params);

			Activity first = acts.get(0);
			first.setEndTime(departure[0]);
			sf.handleFirstActivity(first);

			for (int i = 1; i < lastIdx; i++) {
				Activity a = acts.get(i);
				a.setStartTime(departure[i - 1] + legTravelTime[i - 1]);
				a.setEndTime(departure[i]);
				sf.handleActivity(a);
			}

//			Overnight (wrap-around) last activity: arrival set, departure unset; scored together with
//			the first activity's end + 24h inside handleOvernightActivity.
			Activity last = acts.get(lastIdx);
			last.setStartTime(arrivalLast);
			last.setEndTimeUndefined();
			sf.handleLastActivity(last);

			sf.finish();
			return sf.getScore();
		}

		/**
		 * Write the chosen end times into the plan in the post-state TimeAllocationMutator leaves: free
		 * activities get the sampled end time with their start time unset; duration-encoded activities
		 * keep their maximum duration (the scratch end time set during scoring is cleared); the
		 * overnight last activity is restored to fully unset times.
		 */
		void writeBack(double[] x) {
			for (int k = 0; k < freeActIdx.length; k++) {
				Activity a = acts.get(freeActIdx[k]);
				a.setEndTime(x[k]);
				a.setStartTimeUndefined();
			}
			for (int i = 0; i < lastIdx; i++) {
				if (!freeEnd[i]) {
					acts.get(i).setEndTimeUndefined();
					acts.get(i).setStartTimeUndefined();
				}
			}
			acts.get(lastIdx).setStartTimeUndefined();
		}

		/** Human-readable first feasibility violation, for diagnostics only. */
		String describeViolation(double[] x) {
			int k = 0;
			for (int i = 0; i < lastIdx; i++) {
				double arrival = (i == 0) ? 0.0 : departure[i - 1] + legTravelTime[i - 1];
				double dep;
				if (freeEnd[i]) {
					dep = x[k++];
					if (dep <= 0.0 || dep > latestEnd) {
						return String.format("bound: end of act %d = %.0f not in (0,%.0f]", i, dep, latestEnd);
					}
					if (dep <= arrival) {
						return String.format("order: act %d (%s) departs %.0f <= arrival %.0f (overshoot %.0fs)",
							i, acts.get(i).getType(), dep, arrival, arrival - dep);
					}
				} else {
					dep = arrival + fixedDuration[i];
				}
				departure[i] = dep;
			}
			double arrivalLast = departure[lastIdx - 1] + legTravelTime[lastIdx - 1];
			if (departure[0] + 24 * 3600.0 <= arrivalLast) {
				return String.format("wrap: firstEnd+24h=%.0f <= arrivalLast=%.0f", departure[0] + 86400.0, arrivalLast);
			}
			return "feasible";
		}
	}
}
