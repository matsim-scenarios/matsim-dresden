/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2013 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.analysis.vtts;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.ActivityEndEvent;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.events.handler.ActivityEndEventHandler;
import org.matsim.api.core.v01.events.handler.ActivityStartEventHandler;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.api.core.v01.events.handler.TransitDriverStartsEventHandler;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.dvrp.vrpagent.VrpAgentLogic;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.gbl.Gbl;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.StageActivityTypeIdentifier;
import org.matsim.core.scoring.functions.ActivityUtilityParameters;
import org.matsim.core.scoring.functions.ScoringParameters;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.core.utils.misc.OptionalTime;
import org.matsim.core.population.algorithms.MutateActivityTimeAllocation;
import org.matsim.prepare.EncodeTypicalDuration;
import org.matsim.scoring.DresdenActivityScoring;
import tech.tablesaw.api.BooleanColumn;
import tech.tablesaw.api.DoubleColumn;
import tech.tablesaw.api.IntColumn;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;

import java.util.*;


/**
 * Local copy of {@code org.matsim.application.analysis.population.VTTSHandler} (author: ikaddoura), adapted for the
 * Dresden scenario. It computes the effective value of travel time savings (VTTS) for each agent and each trip by
 * repeating the activity scoring for an earlier arrival time and taking the score difference.
 * <p>
 * Two Dresden adaptations:
 * <ul>
 *   <li>the {@link MarginalSumScoringFunction} used here scores activities per-activity-parameterized (core
 *   {@code CharyparNagelActivityScoring} with an {@code ActivityAttributeTypicalDurationCalculator}, plus
 *   {@link org.matsim.scoring.DresdenActivityScoring}'s corridor terms when the analyzed run had them armed);</li>
 *   <li>each synthetic activity built from the events is stamped with the planned per-activity typical duration the
 *   run used ({@link EncodeTypicalDuration} inserts it into the initial population and it is threaded, never updated,
 *   through the iterations into the output population). We read it back from the output population, matched by
 *   selected-plan activity order, so the scoring here uses the same typical duration the run used. When it is absent
 *   (e.g. freight/commercial activities), the activity is not stamped and the scoring falls back to the activity
 *   type's config value.</li>
 * </ul>
 *
 * @author ikaddoura (original); adapted for per-activity typical duration
 */
public final class VTTSHandler implements ActivityStartEventHandler, ActivityEndEventHandler, PersonDepartureEventHandler, TransitDriverStartsEventHandler {
	// the constructor is package-private so having the class public final is ok. kai, nov'25

	public static class TripData {
		public double mUTTSh;
		public String actType;
		public double actTypDur_h;
		/**
		 * Duration of the activity following this trip in the <em>scoring's</em> notion, not the mobsim's: the interval
		 * over which the performing utility is integrated. For a middle activity the two coincide, but the last
		 * activity of the day has no mobsim end at all -- it is scored from its arrival to the day end (the wrap-around
		 * end for a wrap plan, otherwise simulationPeriodInDays*24h), a convention the mobsim knows nothing about.
		 * NaN until the activity has been seen, so an unscored trip is not reported as a zero-duration activity.
		 */
		public double actDur_h = Double.NaN;
		public double actScore;
		/**
		 * Scoring-domain class of the activity following this trip:
		 * <ul>
		 *   <li>{@code ok} -- realized duration on the logarithmic Charypar-Nagel branch; the marginal is a meaningful
		 *   value of time and enters the summary statistics;</li>
		 *   <li>{@code extrapolated} -- realized duration below the zero-utility duration, so the score sits on the
		 *   linear extension whose slope is a property of the extrapolation (beta*e^(10h/t*), explosive for short
		 *   typicals), not of preferences; finite, reported separately, excluded from means;</li>
		 *   <li>{@code degenerate} -- not computable at all (zero-utility duration underflow for a pathologically small
		 *   typical duration, or a non-finite score); expected ~0 after the preprocessing fixes, i.e. a pipeline alarm.</li>
		 * </ul>
		 */
		public String scoringInputClass;
		/** Last activity arrived at/after its scoring window end (wrap: first end + 24h, else simulationPeriodInDays*24h).
		 * Informational indicator only -- such trips are still scored and classified by the rules above. */
		public boolean afterDayEnd;
		double VTTSh;
		double musl_h;
		String mode;
		double departureTime = Double.NaN;
	}

	/** Per-person planned per-activity scoring inputs, in selected-plan activity order (NaN where the plan carries no
	 * attribute): typical durations plus the schedule-delay anchors, all read from the output population. */
	public record PlannedActivities(double[] typicalDurations, double[] initialStartTimes, double[] initialEndTimes) {}

	private static class SimData {
		public double margUtlOfMoney;
		double currentActivityStartTime = Double.NaN;
		String currentActivityType;
//		String currentOrIncomingTripMode;
		List<TripData> trips = new ArrayList<>();
		double firstActivityEndTime = Double.NaN;
		String firstActivityType ;
	}
	private final Map<Id<Person>,SimData> simDataMap = new HashMap<>();

	private static final Logger log = LogManager.getLogger( VTTSHandler.class );
	private static int incompletedPlanWarning = 0;
	private static int noCarVTTSWarning = 0;
	private static int noTripVTTSWarning = 0;
	private static int noTripNrWarning = 0;

	private final Scenario scenario;
	private int currentIteration;

	private final Set<Id<Person>> personIdsToBeIgnored = new HashSet<>();

	private final Set<Id<Person>> departedPersonIds = new HashSet<>();

	private final double defaultVTTS_moneyPerHour; // for the car mode!

	private final ScoringParametersForPerson scoringParametersForPerson;

	/**
	 * Per-person planned typical durations (seconds), in selected-plan activity order (ExcludeStageActivities), read
	 * from the run's output population; {@code NaN} where the run did not assign one (e.g. freight/commercial acts).
	 */
	private final Map<Id<Person>, PlannedActivities> plannedActivities;
	/** Whether the run scored with the schedule-delay corridor armed (from the output config's dresdenScoring group);
	 * the offline scoring must mirror it so the marginals include the schedule-delay components the agents experienced. */
	private final boolean scheduleDelayScoring;


	VTTSHandler( Scenario scenario, ScoringParametersForPerson scoringParametersForPerson,
				 Map<Id<Person>, PlannedActivities> plannedActivities, boolean scheduleDelayScoring ) {
		// yyyy it would (presumably) be much better to pull the scoring function from injection.  Rather than self-constructing the
		// scoring function here, where we need to rely on having the same ("default") scoring function in the model implementation.
		// Which we almost surely do not have (e.g. bicycle scoring addition, bus penalty addition, ...).  Also see a similar comment further
		// down, where the local scoring fct is constructed.  kai, gr, jul'25

		if (scenario.getConfig().scoring().getMarginalUtilityOfMoney() == 0.) {
			log.warn("The marginal utility of money must not be 0.0. The VTTS is computed in Money per Time.");
		}

		this.scenario = scenario;
		this.scoringParametersForPerson = scoringParametersForPerson;
		this.plannedActivities = plannedActivities;
		this.scheduleDelayScoring = scheduleDelayScoring;
		this.currentIteration = Integer.MIN_VALUE;
		this.defaultVTTS_moneyPerHour =
				(this.scenario.getConfig().scoring().getPerforming_utils_hr()
						 + this.scenario.getConfig().scoring().getModes().get( TransportMode.car ).getMarginalUtilityOfTraveling() * (-1.0)
				) / this.scenario.getConfig().scoring().getMarginalUtilityOfMoney();
	}

	public Map<Id<Person>,List<TripData>> getTripDataMap() {
		Map<Id<Person>,List<TripData>> tripDataMap = new LinkedHashMap<>();
		for( Map.Entry<Id<Person>, SimData> simDataEntry : simDataMap.entrySet() ){
			Id<Person> personId = simDataEntry.getKey();
			List<TripData> tripData = simDataEntry.getValue().trips;
			tripDataMap.put( personId, Collections.unmodifiableList( tripData ) );
		}
		return Collections.unmodifiableMap( tripDataMap );
	}

	@Override
	public void reset(int iteration) {

		this.currentIteration = iteration;
		log.warn("Resetting VTTS information from previous iteration.");

		incompletedPlanWarning = 0;
		noCarVTTSWarning = 0;

		this.personIdsToBeIgnored.clear();
		this.departedPersonIds.clear();

		this.simDataMap.clear();
	}

	@Override
	public void handleEvent(TransitDriverStartsEvent event) {
		personIdsToBeIgnored.add(event.getDriverId());
	}

	@Override
	public void handleEvent(PersonDepartureEvent event) {

		final Id<Person> personId = event.getPersonId();
		if ( this.personIdsToBeIgnored.contains( personId )){
			return;
		}

		if( isToBeIgnored( personId ) ) return;

		SimData simData = simDataMap.computeIfAbsent( personId, k -> new SimData() );
		// (freight departs w/o a preceeding activity end)

		if ( !this.departedPersonIds.contains( personId ) ){
			this.departedPersonIds.add( personId );

			// in this way, there is only one trip record per trip.  I don't know how this was achieved in the previous code.
			TripData tripData = new TripData();
			simData.trips.add( tripData );
			tripData.departureTime = event.getTime();
			tripData.mode = event.getRoutingMode();
		}
	}
	private boolean isToBeIgnored( Id<Person> personId ){
		Person person = scenario.getPopulation().getPersons().get( personId );
		if ( person==null ) {
			personIdsToBeIgnored.add( personId );
			return true;
		}
		return false;
	}

	@Override
	public void handleEvent(ActivityStartEvent event) {

		final Id<Person> personId = event.getPersonId();

		if ( StageActivityTypeIdentifier.isStageActivity( event.getActType() ) || this.personIdsToBeIgnored.contains( personId )) {
			return;
		}

		if( isToBeIgnored( personId ) ) return;

		SimData simData = simDataMap.get( personId );
		simData.currentActivityStartTime = event.getTime();
		simData.currentActivityType = event.getActType();
	}

	@Override
	public void handleEvent(ActivityEndEvent event) {
		final Id<Person> personId = event.getPersonId();

		if (event.getActType().equals(VrpAgentLogic.BEFORE_SCHEDULE_ACTIVITY_TYPE )) {
			this.personIdsToBeIgnored.add( personId );
		}

		if ( StageActivityTypeIdentifier.isStageActivity( event.getActType() ) || this.personIdsToBeIgnored.contains( personId )) {
			return;
		}

		if( isToBeIgnored( personId ) ) return;
		// (as in the other handlers: an agent that is not in the population is not analysed, and must not even get a
		// SimData record here -- this is the first event we see for an agent.)

		SimData simData = simDataMap.get( personId );
		if ( simData==null ) {
			// not seen this person before; is also first activity!
			simData = new SimData();
			simData.firstActivityEndTime = event.getTime();
			simData.firstActivityType = event.getActType();
			simDataMap.put( personId, simData );
		} else {
			// this is NOT the first activity ...

			// ... now process the events thrown during the trip to the activity which has just ended, ...
			computeVTTS( personId, OptionalTime.defined( event.getTime() ) );

			simData.currentActivityType = null;
			simData.currentActivityStartTime = Double.NaN;

			this.departedPersonIds.remove( personId );
		}

	}

	/*
	 * This method has to be called after parsing the events. Here, the the last / overnight activity is taken into account.
	 */
	public void computeFinalVTTS() {
		for (Id<Person> affectedPersonId : this.departedPersonIds) {
			computeVTTS( affectedPersonId, OptionalTime.undefined() );
		}
	}

	public Table getTablesawTripsTable() {
		StringColumn personIds = StringColumn.create( HeadersKN.personId );
		IntColumn tripIndices = IntColumn.create( HeadersKN.tripIdx );
		StringColumn modes = StringColumn.create( HeadersKN.mode );
		StringColumn acts = StringColumn.create( HeadersKN.activity );
		DoubleColumn typDurs = DoubleColumn.create( HeadersKN.typicalDuration );
		DoubleColumn actDurs = DoubleColumn.create( HeadersKN.activityDuration );
		DoubleColumn muslValues = DoubleColumn.create( HeadersKN.muslh );
		DoubleColumn muttsValues = DoubleColumn.create( HeadersKN.muttsh );
		DoubleColumn vttsValues = DoubleColumn.create( HeadersKN.vttsh );
		DoubleColumn mUoMs = DoubleColumn.create( HeadersKN.mUoM );
		StringColumn scoringInputClasses = StringColumn.create( HeadersKN.scoringInputClass );
		BooleanColumn afterDayEnds = BooleanColumn.create( HeadersKN.afterDayEnd );
		Table table = Table.create( personIds, mUoMs, tripIndices, modes, acts, actDurs, typDurs, muslValues, muttsValues, vttsValues, scoringInputClasses, afterDayEnds );

		for( Map.Entry<Id<Person>, SimData> entry : simDataMap.entrySet() ){
			Id<Person> personId = entry.getKey();
			SimData simData = entry.getValue();
			for ( int ii=0 ; ii<simData.trips.size(); ii++ ) {
				TripData trip = simData.trips.get( ii );
				personIds.append( personId.toString() );
				tripIndices.append( ii );
				modes.append( trip.mode );
				muttsValues.append( trip.mUTTSh);
				vttsValues.append( trip.VTTSh );
				acts.append( trip.actType );
				actDurs.append( trip.actDur_h );
				typDurs.append( trip.actTypDur_h );
				mUoMs.append( simData.margUtlOfMoney );
				muslValues.append( trip.musl_h );
				scoringInputClasses.append( trip.scoringInputClass != null ? trip.scoringInputClass : "degenerate" );
				afterDayEnds.append( trip.afterDayEnd );
			}
		}

		return table;
	}

	private static int mUoMCnt =0;

	private void computeVTTS(Id<Person> personId, OptionalTime activityEndTime ){

		final SimData simData = this.simDataMap.get( personId );
		if ( simData.trips.getLast().mode == null ) {
			// No mode stored for this person and trip. This indicates that the current trip mode was skipped.
			// Thus, do not compute any VTTS for this trip.
			return;
		}

		double activityDelayDisutility_h;
		double typicalDurationUsed_s = Double.NaN; // the per-activity typical duration actually applied (NaN => config fallback)
		final ScoringConfigGroup scoringConfigGroup = this.scenario.getConfig().scoring();
		if ( simData.currentActivityType==null || Double.isNaN( simData.currentActivityStartTime ) ) {
			// the second condition was already tested earlier. (??)

			log.warn("incomplete plan; personId={}; actType={}; actStartTime={}", personId, simData.currentActivityType, simData.currentActivityStartTime );

			// removing the trip record since otherwise it will have entries that destroy taking averages:
			simData.trips.removeLast();

			return;
			// yyyy returning here will probably crash later. However, otherwise it may create garbage, and do this silently.  kai, nov'25

			// there is no information about the current activity which indicates that the trip (with the delay) was not completed.
//			activityDelayDisutility_h = 3600. * handleIncompletePlan( personId, scenario.getConfig().scoring().getPerforming_utils_hr() );
		} else {
			Person person = this.scenario.getPopulation().getPersons().get( personId );
			String subpop = PopulationUtils.getSubpopulation( person );

			final ScoringConfigGroup.ScoringParameterSet scoringParameterSet = scoringConfigGroup.getScoringParameters( subpop );
			final MarginalSumScoringFunction marginalSumScoringFunction = new MarginalSumScoringFunction(
							new ScoringParameters.Builder( scoringConfigGroup, scoringParameterSet, scenario.getConfig().scenario() ).build(),
							scheduleDelayScoring );
			// yyyy it would (presumably) be much better to pull the scoring function from injection.  Rather than self-constructing the
			// scoring function here, where we need to rely on having the same ("default") scoring function in the model implementation.
			// Which we almost surely do not have (e.g. bicycle scoring addition, bus penalty addition, ...).  kai, gr, jul'25

			PlannedActivities planned = this.plannedActivities.get( personId );
			boolean underflow;
			// The interval the performing utility is integrated over, i.e. the duration in the scoring's own terms:
			// arrival to departure for a middle activity, arrival to the day end for the last one. Drives both the
			// reported actDur_h and the scoring-input classification, so those cannot disagree about which interval
			// they describe. Caveat for both consumers: the Charypar-Nagel activity scoring additionally clips this
			// interval to the activity type's opening hours (and collapses it when the type is closed throughout),
			// which is not reproduced here -- exact as long as no opening/closing times are configured, as in this
			// scenario, and the place to extend if they ever are.
			double scoringWindow_s;
			MarginalSumScoringFunction.Scores scores = null;
			simData.trips.getLast().afterDayEnd = false;
			if( activityEndTime.isUndefined() ){
				// The end time is undefined...

				// ... now handle the first and last OR overnight activity. This is figured out by the scoring function itself (depending on the activity types).

				Activity activityMorning = PopulationUtils.createActivityFromLinkId( simData.firstActivityType, null );
				activityMorning.setEndTime( simData.firstActivityEndTime );

				Activity activityEvening = PopulationUtils.createActivityFromLinkId( simData.currentActivityType, null );
				activityEvening.setStartTime( simData.currentActivityStartTime );

				// Dresden: both activities are matched to the plan by position -- the morning one is activity index 0,
				// the evening one the index the realized sequence has reached, exactly like the middle-activity branch
				// below and exactly like the plan alignment the run's own activity scoring walks. Do NOT reach for
				// the plan's terminal slot instead: an agent is scored here whenever the simulation ended while it was
				// at an activity, which is not the same as it being at its last activity. One that stalled mid-plan
				// would otherwise be scored against the typical duration of an activity it never reached (silently,
				// when the two happen to share a type). The planned typical durations already encode
				// EncodeTypicalDuration's wrap-around / end-of-period rules and come straight from the output
				// population, so this scores the pair as the run did.
				double typMorning = plannedValue( planned == null ? null : planned.typicalDurations(), 0 );
				int eveningIndex = simData.trips.size();
				double typEvening = plannedValue( planned == null ? null : planned.typicalDurations(), eveningIndex );
				typicalDurationUsed_s = typEvening; // currentActivityType is the evening (last-reached) activity

				// The last activity is scored from its arrival to the end of the day (the wrap-around end for a wrap
				// plan, otherwise simulationPeriodInDays*24h). Arriving at or after that end (window <= 0) is flagged
				// as an indicator -- the day-end constraint binds there -- but the trip is still scored: middle
				// activities never involve the day end, and for the last activity the (finite) linear-extension
				// marginal is classified by the same below-zero-utility rule as everything else.
				double lastActivityScoringEnd = simData.firstActivityType.equals( simData.currentActivityType )
					? simData.firstActivityEndTime + 24. * 3600.                                                         // wrap-around
					: this.scenario.getConfig().scenario().getSimulationPeriodInDays() * 24. * 3600.;                    // non-wrap
				simData.trips.getLast().afterDayEnd = simData.currentActivityStartTime >= lastActivityScoringEnd;
				// classification window: the evening side (the marginal shifts the evening arrival; the morning
				// activity contributes equally to both variants and cancels out of the delta)
				scoringWindow_s = lastActivityScoringEnd - simData.currentActivityStartTime;

				underflow = isUnderflowingTypicalDuration( scoringParameterSet, simData.firstActivityType, typMorning )
					|| isUnderflowingTypicalDuration( scoringParameterSet, simData.currentActivityType, typEvening );

				if ( !underflow ) {
					stampTypicalDuration( activityMorning, typMorning );
					stampTypicalDuration( activityEvening, typEvening );
					if ( planned != null ) {
						stampAnchors( activityMorning, planned, 0 );
						stampAnchors( activityEvening, planned, eveningIndex );
					}
					scores = marginalSumScoringFunction.getOvernightActivityDelayDisutility( activityMorning, activityEvening, 1.0 );
				}

			} else{
				// The activity has an end time indicating a 'normal' activity.

				// Edge case: for an agent that ends its last activity and then stalls on the following trip, that
				// activity has an end time and lands here, i.e. it is scored as a middle activity (over its realized
				// start-to-end window) rather than by the last-activity rule the run's own scoring applied to it. The
				// stalled trip itself produces no row (it is dropped as an incomplete plan further up), so the
				// discrepancy is confined to this one row per stalled agent. Rare, and not worth reconstructing the
				// run's stuck-agent treatment offline; noted so the rows are not mistaken for last activities.

				Activity activity = PopulationUtils.createActivityFromLinkId( simData.currentActivityType, null );
				activity.setStartTime( simData.currentActivityStartTime );
				activity.setEndTime( activityEndTime.seconds() );

				// Dresden: this middle activity is activity index trips.size() of the plan (one trip precedes each
				// activity from index 1 on); its planned typical duration comes from the output population, so
				// DresdenActivityScoring uses it instead of the activity type's config typical duration.
				int activityIndex = simData.trips.size();
				double typ = plannedValue( planned == null ? null : planned.typicalDurations(), activityIndex );
				typicalDurationUsed_s = typ;
				underflow = isUnderflowingTypicalDuration( scoringParameterSet, simData.currentActivityType, typ );
				scoringWindow_s = activityEndTime.seconds() - simData.currentActivityStartTime;

				if ( !underflow ) {
					stampTypicalDuration( activity, typ );
					if ( planned != null ) {
						stampAnchors( activity, planned, activityIndex );
					}
					scores = marginalSumScoringFunction.getNormalActivityDelayDisutility( personId, activity, 1.0 );
				}
			}

			// Report the same interval that was just classified, rather than recomputing the duration independently:
			// the column is the scoring's notion of activity duration (see TripData#actDur_h), so it has to follow the
			// day-end rule the scoring actually applied. Computing it separately had applied the wrap-around end
			// unconditionally, which in this scenario (EncodeTypicalDuration splits home into home_morning /
			// home_evening, so the first and last type never match, and the wrap branch never fires) inflated every
			// last activity by the morning activity's duration.
			simData.trips.getLast().actDur_h = scoringWindow_s / 3600.;

			// Classify the scoring input (see TripData.scoringInputClass): "degenerate" = not computable (zero-utility
			// underflow or a non-finite score; a pipeline alarm, expected ~0); "extrapolated" = realized duration below
			// the zero-utility duration, i.e. the score sits on the linear extension and the marginal reflects the
			// extrapolation slope rather than preferences (excluded from means, reported separately); "ok" = the
			// logarithmic branch, where the marginal is a meaningful value of time.
			if ( underflow || scores == null || scores.isDegenerate() ) {
				activityDelayDisutility_h = Double.NaN;
				simData.trips.getLast().actScore = Double.NaN;
				simData.trips.getLast().scoringInputClass = "degenerate";
			} else {
				activityDelayDisutility_h = 3600. * scores.deltaScore();
				simData.trips.getLast().actScore = scores.scoreNormal();
				double effectiveTypical_s = typicalDurationUsed_s > 0 ? typicalDurationUsed_s
					: scoringConfigGroup.getActivityParams( simData.currentActivityType ).getTypicalDuration().seconds();
				double zeroUtilityDuration_s = zeroUtilityDuration_h( scoringParameterSet, simData.currentActivityType, effectiveTypical_s ) * 3600.;
				simData.trips.getLast().scoringInputClass = scoringWindow_s < zeroUtilityDuration_s ? "extrapolated" : "ok";
			}
		}

		// Calculate the agent's trip delay disutility.
		// (Could be done similarly to the activity delay disutility. As long as it is computed linearly, the following should be okay.)
		String mode = simData.trips.getLast().mode;
		double directMarginalUtilityOfTraveling = 0.;
		if( scoringConfigGroup.getModes().get( mode ) != null ){
			directMarginalUtilityOfTraveling = scoringConfigGroup.getModes().get( mode ).getMarginalUtilityOfTraveling();
		} else{
			log.warn( "Could not identify the marginal utility of traveling for mode={}. Setting this value to zero. (Probably using subpopulations...)", mode );
		}
		double tripDelayDisutility_h = directMarginalUtilityOfTraveling * (-1);

		simData.trips.getLast().musl_h = activityDelayDisutility_h;

		final double mUTTS_h = (activityDelayDisutility_h + tripDelayDisutility_h) ;

		simData.trips.getLast().mUTTSh = mUTTS_h;

		// Translate the disutility into monetary units.
		double marginalUtilityOfMoney = scoringParametersForPerson.getScoringParameters(scenario.getPopulation().getPersons().get(personId)).marginalUtilityOfMoney;
		simData.margUtlOfMoney = marginalUtilityOfMoney;
		if ( mUoMCnt < 10 ){
			mUoMCnt++;
			log.info( "personId={}, actDelayDisutil={}; tripDelayDisutl={}; mUM={}", personId, activityDelayDisutility_h, tripDelayDisutility_h, marginalUtilityOfMoney );
			if ( mUoMCnt == 10 ) {
				log.info( Gbl.FUTURE_SUPPRESSED );
			}
		}

		simData.trips.getLast().VTTSh = mUTTS_h / marginalUtilityOfMoney;

		simData.trips.getLast().actType = simData.currentActivityType;
		// Report the per-activity typical duration actually used in the scoring; fall back to the activity type's
		// config typical duration when the activity carried none (so the scoring used the config value too).
		double configTypicalDuration_s = scoringConfigGroup.getActivityParams( simData.currentActivityType ).getTypicalDuration().seconds();
		simData.trips.getLast().actTypDur_h = ( typicalDurationUsed_s > 0 ? typicalDurationUsed_s : configTypicalDuration_s ) / 3600. ;

	}

	/**
	 * Put the typical duration (seconds) onto the activity, unless it is not a positive number (NaN when the run did
	 * not assign one), in which case the activity is left unstamped and the scoring falls back to the activity
	 * type's config typical duration.
	 */
	private static void stampTypicalDuration( Activity activity, double typicalDuration_s ) {
		if ( typicalDuration_s > 0 ) {
			activity.getAttributes().putAttribute( EncodeTypicalDuration.TYPICAL_DURATION, typicalDuration_s );
		}
	}

	/** Put the planned schedule-delay anchors onto the analysis activity where the run's population carries them, so an
	 * armed {@link org.matsim.scoring.DresdenActivityScoring} reproduces the run's corridor terms. Harmless disarmed. */
	private static void stampAnchors( Activity activity, PlannedActivities planned, int index ) {
		double start = plannedValue( planned.initialStartTimes(), index );
		if ( !Double.isNaN( start ) ) {
			activity.getAttributes().putAttribute( DresdenActivityScoring.INITIAL_START_TIME_ATTRIBUTE, start );
		}
		double end = plannedValue( planned.initialEndTimes(), index );
		if ( !Double.isNaN( end ) ) {
			activity.getAttributes().putAttribute( MutateActivityTimeAllocation.INITIAL_END_TIME_ATTRIBUTE, end );
		}
	}

	private static double plannedValue( double[] values, int index ) {
		return ( values != null && index >= 0 && index < values.length ) ? values[index] : Double.NaN;
	}

	/**
	 * Whether a planned typical duration makes the scoring input non-computable for the given activity type: the
	 * Charypar-Nagel zero-utility duration -- computed exactly as
	 * {@link org.matsim.core.scoring.functions.ActivityUtilityParameters} does -- underflows to zero for a
	 * pathologically small typical duration, after which the logarithmic performing utility is undefined
	 * ({@code log(duration/0)}). A non-positive/NaN value means no per-activity typical duration was stamped, so the
	 * activity is scored with its (normal-sized) config typical duration and does not underflow.
	 */
	private static boolean isUnderflowingTypicalDuration( ScoringConfigGroup.ScoringParameterSet scoringParameterSet, String activityType, double typicalDuration_s ) {
		if ( !(typicalDuration_s > 0) ) {
			return false;
		}
		return !(zeroUtilityDuration_h( scoringParameterSet, activityType, typicalDuration_s ) > 0);
	}

	/** The Charypar-Nagel zero-utility duration (hours), computed exactly as {@link org.matsim.core.scoring.functions.ActivityUtilityParameters} does. */
	private static double zeroUtilityDuration_h( ScoringConfigGroup.ScoringParameterSet scoringParameterSet, String activityType, double typicalDuration_s ) {
		ScoringConfigGroup.ActivityParams activityParams = scoringParameterSet.getActivityParams( activityType );
		ActivityUtilityParameters.ZeroUtilityComputation computation = switch ( activityParams.getTypicalDurationScoreComputation() ) {
			case relative -> new ActivityUtilityParameters.SameRelativeScore();
			case uniform -> new ActivityUtilityParameters.SameAbsoluteScore();
		};
		return computation.computeZeroUtilityDuration_s( activityParams.getPriority(), typicalDuration_s ) / 3600.0;
	}

	private static double handleIncompletePlan( Id<Person> personId, double performing_utils_hr ){
		double activityDelayDisutilityOneSec;
		if( incompletedPlanWarning <= 10 ){
			log.warn( "Agent " + personId + " has not yet completed the plan/trip (the agent is probably stucking). Cannot compute the disutility of being late at this activity. "
							  + "Something like the disutility of not arriving at the activity is required. Try to avoid this by setting a smaller stuck time period." );
			log.warn( "Setting the disutilty of being delayed on the previous trip using the config parameters; assuming the marginal disutility of being delayed at the " +
						  "(hypothetical) activity to be equal to beta_performing: " + performing_utils_hr );

			if( incompletedPlanWarning == 10 ){
				log.warn( Gbl.FUTURE_SUPPRESSED );
			}
			incompletedPlanWarning++;
		}
		activityDelayDisutilityOneSec = (1.0 / 3600.) * performing_utils_hr;
		return activityDelayDisutilityOneSec;
	}

}
