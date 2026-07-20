package org.matsim.analysis.vtts;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.Injector;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.prepare.EncodeTypicalDuration;
import org.matsim.replanning.bestresponse.BestResponseScheduleConfigGroup;
import org.matsim.replanning.bestresponse.BestResponseScheduleStrategy;
import org.matsim.core.population.algorithms.MutateActivityTimeAllocation;
import org.matsim.run.scenarios.DresdenModel;
import org.matsim.scoring.DresdenActivityScoring;
import org.matsim.scoring.DresdenScoringConfigGroup;
import org.matsim.utils.tablesaw.TablesawUtils;
import picocli.CommandLine;
import playground.vsp.scoring.IncomeDependentUtilityOfMoneyPersonScoringParameters;
import tech.tablesaw.api.DoubleColumn;
import tech.tablesaw.api.NumberColumn;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;
import tech.tablesaw.columns.Column;
import tech.tablesaw.io.csv.CsvWriteOptions;
import tech.tablesaw.plotly.components.Figure;
import tech.tablesaw.plotly.components.Layout;
import tech.tablesaw.plotly.traces.HistogramTrace;

import java.nio.file.Path;
import java.time.Duration;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static java.lang.Math.exp;
import static org.matsim.application.ApplicationUtils.globFile;
import static tech.tablesaw.aggregate.AggregateFunctions.*;

/**
 * Dresden-local copy of {@code org.matsim.application.analysis.population.AddVttsEtcToActivities} that drives the
 * local {@link VTTSHandler}, which in turn scores activities with {@link org.matsim.scoring.DresdenActivityScoring}
 * (per-activity typical duration) instead of the stock Charypar-Nagel scoring. Behaviour is otherwise identical to
 * the library command, including the compression-agnostic input file resolution.
 */
@CommandLine.Command(name = "run-vtts-analysis", description = "")
public class DresdenAddVttsEtcToActivities implements MATSimAppCommand {
	private static final Logger log = LogManager.getLogger( DresdenAddVttsEtcToActivities.class );

	/** Plotting window for the VTTS histogram. Outliers beyond this are kept in the stats but excluded from the plot. */
	private static final double VTTS_HISTOGRAM_MIN = 0.;
	private static final double VTTS_HISTOGRAM_MAX = 50.;

	@CommandLine.Option(names = "--path", description = "Path to output folder", required = true)
	private Path path;

	@CommandLine.Option(names = "--runId", description = "Run id (i.e. prefixes of files)")
	private String runId;

	@CommandLine.Option(names = "--output", description = "Overwrite output folder defined by the application")
	protected Path output;

//	@CommandLine.Option(names = "--prefix", description = "Prefix for filtered events output file, optional." )
//	private String prefix;

	@CommandLine.Option(names = "--threads", description = "Number of threads to use for processing events", defaultValue = "1")
	private int numberOfThreads = 1;

	@CommandLine.Option(names = "--simulation-period-in-days", description = "Effective end of day for the non-wrap-" +
		"around last-activity scoring, as a multiple of 24h. NOT persisted to the output config, so it must be " +
		"supplied to match the analyzed run: current runs use 1.125 (27:00, the default here); v1.1 used 1.0 (24:00).")
	private double simulationPeriodInDays = DresdenModel.DEFAULT_SIMULATION_PERIOD_IN_DAYS;

	/**
	 * Wall-clock start of the run this analysis post-processes ({@link System#nanoTime}), or null when the analysis is
	 * invoked standalone and there is no run to time. Only set through the programmatic constructor.
	 */
	private Long runStartNanoTime;

	public DresdenAddVttsEtcToActivities() {}

	/**
	 * Programmatic constructor for running this analysis as a post-processing step of a DresdenModel run (see
	 * {@link DresdenModel#preparePostProcessing}), rather than as a standalone CLI command. The {@code path} is the run
	 * output folder, {@code runId} its run id, and {@code simulationPeriodInDays} the value the run scored with -- it is
	 * not persisted to the output config, so it must be threaded in from the model (see the option's description).
	 */
	public DresdenAddVttsEtcToActivities( Path path, String runId, double simulationPeriodInDays ) {
		this( path, runId, simulationPeriodInDays, null );
	}

	/**
	 * As above, plus the run's wall-clock start ({@link System#nanoTime}), so the metrics this writes can report how
	 * long the run took. See {@code DresdenModel#startNanoTime}.
	 */
	public DresdenAddVttsEtcToActivities( Path path, String runId, double simulationPeriodInDays, Long runStartNanoTime ) {
		this.path = path;
		this.runId = runId;
		this.simulationPeriodInDays = simulationPeriodInDays;
		this.runStartNanoTime = runStartNanoTime;
	}

	public static void main(String[] args) {
		new DresdenAddVttsEtcToActivities().execute(args );
	}

	@Override
	public Integer call() throws Exception {
		String runPrefix = Objects.nonNull(runId ) ? runId + "." : "";
		Path configPath = path.resolve(runPrefix + "output_" + Controler.DefaultFiles.config.getFilename() );

		Path eventsPath = globFile( path, runPrefix + "*output_" + Controler.DefaultFiles.events.getFilename() + "*" );
		// (trailing "*" instead of a hardcoded ".gz"/".zst" so any compression (gz/lz4/zst) or none is matched)
//		if ( prefix!=null ){
//			eventsPath = ApplicationUtils.globFile( path, "*" + prefix + "output_events_filtered.xml.gz" );
//		}

		Config config = ConfigUtils.loadConfig(configPath.toString());
		config.eventsManager().setNumberOfThreads(numberOfThreads);
		config.controller().setOverwriteFileSetting( OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles );
		config.counts().setInputFile( null );

		// The run sets a non-default simulationPeriodInDays (27:00) that controls the non-wrap-around overnight
		// scoring clamp, but that value is not persisted to the output config, so reloading it here would silently
		// fall back to the 24:00 default and produce negative last-activity durations (and exploding VTTS) for
		// agents whose last activity ends between 24:00 and 27:00. It must be supplied per analyzed run (see the
		// --simulation-period-in-days option); the default matches the current runs, v1.1 needs 1.0.
		config.scenario().setSimulationPeriodInDays( simulationPeriodInDays );

		// ---

		Path outputDir;
		if ( output != null ) {
			outputDir = output;
			// (if "output" comes from the testutils, then it is relative to the IDE java root)
		} else {
			outputDir = path ; // outputDir = inputDir
			// the original was eventsPath.getParent() instead of just "path", where the getParent() presumably just strips the filename.  Don't know why it used that indirection.
		}

		Path outputExpPlansPath = outputDir.resolve(config.controller().getRunId() + ".vtts_" + Controler.DefaultFiles.experiencedPlans.getFilename() + ".gz");

		// ---

		// prefer the postproc_ experienced plans, fall back to output_; match any (or no) compression suffix:
		Path populationFilename;
		try {
			populationFilename = globFile( path, runPrefix + "*postproc_" + Controler.DefaultFiles.experiencedPlans.getFilename() + "*" );
		} catch ( IllegalStateException e ) {
			populationFilename = globFile( path, runPrefix + "*output_" + Controler.DefaultFiles.experiencedPlans.getFilename() + "*" );
		}

		Population experiencedPlans = PopulationUtils.readPopulation( populationFilename.toString() );
		retainAnalyzedSubpopulation( experiencedPlans );

		Scenario scenario = new ScenarioUtils.ScenarioBuilder(config)
//								.setNetwork(NetworkUtils.readNetwork(path.resolve(runPrefix + "output_" + Controler.DefaultFiles.network.getFilename() + ".gz").toString()))
								.setPopulation( experiencedPlans )
								.build();

//		new TransitScheduleReader(scenario).readFile(path.resolve(runPrefix + "output_" + Controler.DefaultFiles.transitSchedule.getFilename() + ".gz").toString());

		ConfigUtils.addOrGetModule(config, BestResponseScheduleConfigGroup.class);
		com.google.inject.Injector injector = new Injector.InjectorBuilder( scenario )
												  .addStandardModules()
												  .addOverridingModule( new AbstractModule(){
													  @Override public void install(){
														  bind( ScoringParametersForPerson.class ).to( IncomeDependentUtilityOfMoneyPersonScoringParameters.class );
														  addPlanStrategyBinding(BestResponseScheduleStrategy.STRATEGY_NAME).toProvider(BestResponseScheduleStrategy.class);
													  }
												  } )
												  .build();

		ScoringParametersForPerson scoringParametersForPerson = injector.getInstance( ScoringParametersForPerson.class );

		// Read the planned per-activity typical durations from the output population. EncodeTypicalDuration inserts
		// them into the initial population and they are threaded (never updated) through the iterations into the
		// output population; the experienced plans do not carry them (there is no feature to thread them there yet).
		Path outputPlansFile = globFile( path, runPrefix + "*output_" + Controler.DefaultFiles.population.getFilename() + "*" );
		log.info("Reading planned typical durations and schedule-delay anchors from output population: {}", outputPlansFile);
		Population outputPopulation = PopulationUtils.readPopulation( outputPlansFile.toString() );
		Map<Id<Person>, VTTSHandler.PlannedActivities> plannedActivities = extractPlannedActivities( outputPopulation );

		// Whether the run scored with the schedule-delay corridor armed; recorded in the output config's
		// dresdenScoring group (absent in older runs => default false). The offline scoring mirrors it so the
		// marginals include the same schedule-delay components the agents experienced.
		boolean scheduleDelayScoring = ConfigUtils.addOrGetModule( config, DresdenScoringConfigGroup.class ).isScheduleDelayScoring();
		log.info( "schedule-delay corridor in the analyzed run: {}", scheduleDelayScoring ? "armed" : "off" );

		// ===

		EventsManager eventsManager = EventsUtils.createEventsManager(config);

		VTTSHandler vttsHandler = new VTTSHandler( scenario, scoringParametersForPerson, plannedActivities, scheduleDelayScoring );
		eventsManager.addHandler( vttsHandler );

		eventsManager.initProcessing();
		log.info("Reading events from file: {}", eventsPath);
		EventsUtils.readEvents(eventsManager, eventsPath.toString());
		eventsManager.finishProcessing();
		vttsHandler.computeFinalVTTS();

		// ===

		Population population = scenario.getPopulation();

		Map<Id<Person>, List<VTTSHandler.TripData>> tripDataMap = vttsHandler.getTripDataMap();

		// The following is a really complicated way to do a join.  Maybe first convert to tablesaw and then do this?
		for( Person person : population.getPersons().values() ){
			final List<Activity> activities = TripStructureUtils.getActivities( person.getSelectedPlan(),
				TripStructureUtils.StageActivityHandling.ExcludeStageActivities );
			List<VTTSHandler.TripData> tripDataList = tripDataMap.get( person.getId() );
			double sumMuse = 0.;
			double cntMuse = 0.;
			for( int ii = 1 ; ii < activities.size() ; ii++ ){
				Activity activity = activities.get( ii );
				VTTSHandler.TripData tripData = tripDataList.get( ii - 1 );  // activity # 1 belongs to trip # 0!
				setVTTS_h( activity, tripData.VTTSh );
				setMUTTS_h( activity, tripData.mUTTSh );
				setMUSE_h( activity, tripData.musl_h );
				setActScore( activity, tripData.actScore );
				if( tripData.musl_h > 0. && tripData.musl_h < 6 * exp( 1. ) ){
					// There are acts that start long after their end time, and in consequence immediately end again.  If they start
					// earlier, they will also just end earlier, but Ihab's calculation gives them a meaningful MUSE.  This is then
					// in the linear regime, and in consequence beta_perf * e - beta_trav(mode).
					// yyyy For the time being we assume that beta_perf=6 and beta_trav(mode) = 0.
					sumMuse += tripData.musl_h;
					cntMuse++;
				}
				if ( cntMuse >0 ) {
					setMUSE_h( person.getSelectedPlan(), sumMuse / cntMuse );
				} else {
					setMUSE_h( person.getSelectedPlan(), 6);
					// yyyy I don't like this.  Maybe set nothing and compensate later? kai, dec'25
				}
			}
		}

		log.info("Writing experienced plans to file: {}", outputExpPlansPath);
		PopulationUtils.writePopulation( population, outputExpPlansPath.toString() );

		// ===

		NumberFormat format1 = NumberFormat.getNumberInstance( Locale.GERMAN );
		format1.setMaximumFractionDigits( 1 );
		format1.setMinimumFractionDigits( 1 );

		Table tripsTable = vttsHandler.getTablesawTripsTable();

		for( Column<?> column : tripsTable.columns() ){
			if ( column instanceof DoubleColumn ) {
				((NumberColumn<?, ?>) column).setPrintFormatter( format1, "n/a" );
			}
		}

		log.info( "print table:");
		System.out.println( System.lineSeparator() + tripsTable + System.lineSeparator() );
		{
			var options = CsvWriteOptions.builder( outputDir.resolve( config.controller().getRunId() + ".tablesaw.tsv" ).toString() ).separator( '\t' );
			tripsTable.write().usingOptions( options.build() );
		}

		// Split the trips by scoring-input class (see VTTSHandler.TripData#scoringInputClass): "ok" (logarithmic
		// branch, meaningful marginals -> the summary statistics), "extrapolated" (realized duration below the
		// zero-utility duration: the finite marginal reflects the linear-extension slope, explosive for short
		// typicals, and would poison the mean), "degenerate" (not computable; a pipeline alarm, expected ~0). All
		// classes stay in the full tablesaw.tsv via the "scoringInput" column; the non-ok classes are also written
		// out separately. "afterDayEnd" (last activity arriving after its scoring window) is an indicator column,
		// not a class: those trips classify by the same rules as everything else.
		Table okTrips = tripsTable.where( tripsTable.stringColumn( HeadersKN.scoringInputClass ).isEqualTo( "ok" ) );
		Table extrapolatedTrips = tripsTable.where( tripsTable.stringColumn( HeadersKN.scoringInputClass ).isEqualTo( "extrapolated" ) );
		Table degenerateTrips = tripsTable.where( tripsTable.stringColumn( HeadersKN.scoringInputClass ).isEqualTo( "degenerate" ) );
		int afterDayEndCount = tripsTable.booleanColumn( HeadersKN.afterDayEnd ).countTrue();
		log.info( "scoring-input classes: ok={}, extrapolated (below zero-utility duration, excluded from means)={}, "
				+ "degenerate (not computable)={}; afterDayEnd indicator: {}",
			okTrips.rowCount(), extrapolatedTrips.rowCount(), degenerateTrips.rowCount(), afterDayEndCount );
		{
			var options = CsvWriteOptions.builder( outputDir.resolve( config.controller().getRunId() + ".extrapolatedScoringTrips.tsv" ).toString() ).separator( '\t' );
			extrapolatedTrips.write().usingOptions( options.build() );
		}
		{
			var options = CsvWriteOptions.builder( outputDir.resolve( config.controller().getRunId() + ".degenerateScoringTrips.tsv" ).toString() ).separator( '\t' );
			degenerateTrips.write().usingOptions( options.build() );
		}

		// (the following does not need to be separated by subpopulation: only ANALYZED_SUBPOPULATION is in here)

		// The explosive linear-extension marginals now live in the "extrapolated" class, but the branch boundary is
		// about the scoring's domain, not magnitude: "ok" trips just above their zero-utility duration carry marginals
		// up to beta*typ/t0, which can still dwarf the plotting window. The clip therefore stays for the histogram's
		// auto-scaled x-axis; clipped-off trips are logged, not silently dropped.
		Table plotTrips = okTrips.where( okTrips.doubleColumn( HeadersKN.vttsh ).isBetweenInclusive( VTTS_HISTOGRAM_MIN, VTTS_HISTOGRAM_MAX ) );
		log.info( "histogram plotted over VTTS in [{}, {}] Eu/h: {} of {} ok trips shown, {} outside the window (excluded from the plot only).",
			VTTS_HISTOGRAM_MIN, VTTS_HISTOGRAM_MAX, plotTrips.rowCount(), okTrips.rowCount(), okTrips.rowCount() - plotTrips.rowCount() );

		HistogramTrace histogramTrace = HistogramTrace.builder( plotTrips.doubleColumn( HeadersKN.vttsh ) ).build();
		final Layout.LayoutBuilder layoutBuilder = Layout.builder().width( 1000 ).title( "VTTS [Eu/h]" );
		Figure figure = new Figure( layoutBuilder.build(), histogramTrace );

		Path htmlPath = outputDir.resolve(runPrefix + "histogram.html" );
		TablesawUtils.writeFigureToHtmlFile( htmlPath.toString(), figure );

		log.info( "print summary statistics:");

		// Headline numbers, all counted over the full trips table (each agent's first activity is not in the table,
		// since an activity enters it via its incoming trip). The zero-duration count is in the scoring's notion of
		// activity duration, like the column it counts (see VTTSHandler.TripData#actDur_h): an activity is 0.0 long
		// when the performing utility was integrated over an empty interval, which for the last activity of the day
		// means arriving exactly at the day end. It includes every scoring-input class, since that interval is
		// determined by the arrival and the day-end rule alone, not by whether the marginal came out computable.
		int total = tripsTable.rowCount();
		int zeroDurationActs = tripsTable.doubleColumn( HeadersKN.activityDuration ).isEqualTo( 0. ).size();

		// Split the zero-duration count by whether the agent's day involves pt at all. The best-response scheduler
		// treats each trip's travel time as a constant, which is exact for the teleported modes and near enough for
		// car, but wrong for pt, where travel time is a step function of departure time -- so a retiming that looks
		// like a repair may just move the agent onto a service an hour later. Splitting the metric makes the two
		// regimes separately visible: the pt-free count is what a travel-time-constant rescheduler can honestly claim,
		// the pt-exposed count is where the departure-time sensitivity decides the outcome.
		int zeroDurationActsPtFree = countZeroDurationActs( tripsTable, false );
		int zeroDurationActsPtExposed = countZeroDurationActs( tripsTable, true );
		StringBuilder summary = new StringBuilder( System.lineSeparator() );
		summary.append( countLine( "scoring ok (log branch; in the stats)", okTrips.rowCount(), total ) );
		summary.append( countLine( "scoring extrapolated (below zero-utility duration; excluded from means)", extrapolatedTrips.rowCount(), total ) );
		summary.append( countLine( "scoring degenerate (not computable; pipeline alarm, expected 0)", degenerateTrips.rowCount(), total ) );
		summary.append( countLine( "last activity arriving at/after day end (indicator, not a class)", afterDayEndCount, total ) );
		summary.append( countLine( "activities with duration 0.0", zeroDurationActs, total ) );
		summary.append( countLine( "  ... of agents whose day has no pt trip", zeroDurationActsPtFree, total ) );
		summary.append( countLine( "  ... of agents whose day has a pt trip", zeroDurationActsPtExposed, total ) );
		System.out.println( summary );

		// Track the zero-duration count as a DVC metric. metrics.json lives at the top of the run output folder (which
		// the "run" DVC stage owns), so it is produced by the run itself now that the analysis runs as a post-processing
		// step of DresdenModel -- no separate stage. The key matches the metric declared in dvc.yaml.
		Path metricsPath = outputDir.resolve( "metrics.json" );
		log.info( "Writing DVC metrics to file: {}", metricsPath );
		Map<String, Number> metrics = new LinkedHashMap<>();
		metrics.put( "zero_duration_activity_count", zeroDurationActs );
		metrics.put( "zero_duration_activity_count_pt_free", zeroDurationActsPtFree );
		metrics.put( "zero_duration_activity_count_pt_exposed", zeroDurationActsPtExposed );
		if ( runStartNanoTime != null ) {
			// Wall clock of the whole model command, from its construction to here: scenario preparation, all
			// iterations, and the post-processing that runs before this analysis. Not just the mobsim -- this is the
			// number that decides how long an experiment takes, and comparing it across experiments is only meaningful
			// alongside the "threads" parameter, which pins the parallelism the run was allowed.
			double wallClockSeconds = ( System.nanoTime() - runStartNanoTime ) / 1_000_000_000.;
			metrics.put( "run_wall_clock_seconds", Math.round( wallClockSeconds ) );
			log.info( "Run wall clock: {} s ({})", Math.round( wallClockSeconds ),
				Duration.ofSeconds( Math.round( wallClockSeconds ) ) );
		}
		new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue( metricsPath.toFile(), metrics );

		final Table muttsStats = okTrips.summarize( HeadersKN.muttsh, mean, quartile1, median, quartile3, percentile95 ).apply();
		System.out.println( System.lineSeparator() + muttsStats + System.lineSeparator() );
		final Table vttsStats = okTrips.summarize( HeadersKN.vttsh, mean, quartile1, median, quartile3, percentile95 ).apply();
		System.out.println( vttsStats + System.lineSeparator() );


		{
			var options = CsvWriteOptions.builder( outputDir.resolve( config.controller().getRunId() + ".muttsStats.tsv" ).toString() ).separator( '\t' );
			muttsStats.write().usingOptions( options.build() );
		}
		{
			var options = CsvWriteOptions.builder( outputDir.resolve( config.controller().getRunId() + ".vttsStats.tsv" ).toString() ).separator( '\t' );
			vttsStats.write().usingOptions( options.build() );
		}

		return 0;
	}

	/**
	 * The only subpopulation this analysis looks at. The others (freight, commercial traffic) do not have the
	 * person-like activity scoring this analysis is about.
	 */
	private static final String ANALYZED_SUBPOPULATION = "person";

	/**
	 * Drop everyone outside the analyzed subpopulation from the experienced plans. That is all it takes to keep them
	 * out of the whole analysis: the {@link VTTSHandler} ignores every agent that is not in the population, so they
	 * end up in neither the trips table nor the statistics, and they are not written to the vtts experienced plans.
	 */
	private static void retainAnalyzedSubpopulation( Population population ) {
		List<Id<Person>> otherSubpopulations = population.getPersons().values().stream()
									   .filter( person -> !ANALYZED_SUBPOPULATION.equals( PopulationUtils.getSubpopulation( person ) ) )
									   .map( Person::getId )
									   .toList();
		otherSubpopulations.forEach( population::removePerson );
		log.info( "analyzing subpopulation '{}': {} persons; ignoring {} agents of other subpopulations.",
			ANALYZED_SUBPOPULATION, population.getPersons().size(), otherSubpopulations.size() );
	}

	/**
	 * For every person, the planned per-activity scoring inputs of each selected-plan activity (excluding stage
	 * activities), in order, as threaded through the run into the output population: the typical duration plus the
	 * schedule-delay anchors (initialStartTime/initialEndTime). Slots for which the run did not assign a value
	 * (e.g. freight/commercial activities, or runs predating the anchors) are {@link Double#NaN}. The order matches
	 * the activity order the {@link VTTSHandler} sees in the events, so it can be indexed by activity position.
	 */
	private static Map<Id<Person>, VTTSHandler.PlannedActivities> extractPlannedActivities( Population outputPopulation ) {
		Map<Id<Person>, VTTSHandler.PlannedActivities> result = new HashMap<>();
		for ( Person person : outputPopulation.getPersons().values() ) {
			List<Activity> activities = TripStructureUtils.getActivities( person.getSelectedPlan(),
				TripStructureUtils.StageActivityHandling.ExcludeStageActivities );
			double[] typicalDurations = new double[activities.size()];
			double[] initialStartTimes = new double[activities.size()];
			double[] initialEndTimes = new double[activities.size()];
			for ( int i = 0; i < activities.size(); i++ ) {
				var attributes = activities.get( i ).getAttributes();
				typicalDurations[i] = asDouble( attributes.getAttribute( EncodeTypicalDuration.TYPICAL_DURATION ) );
				initialStartTimes[i] = asDouble( attributes.getAttribute( DresdenActivityScoring.INITIAL_START_TIME_ATTRIBUTE ) );
				initialEndTimes[i] = asDouble( attributes.getAttribute( MutateActivityTimeAllocation.INITIAL_END_TIME_ATTRIBUTE ) );
			}
			result.put( person.getId(), new VTTSHandler.PlannedActivities( typicalDurations, initialStartTimes, initialEndTimes ) );
		}
		return result;
	}

	private static double asDouble( Object attribute ) {
		return (attribute instanceof Number number) ? number.doubleValue() : Double.NaN;
	}

	/**
	 * Zero-duration activities of the agents whose day does (or does not) contain a pt trip. "Contains pt" is a
	 * property of the whole day, not of the incoming trip alone: the schedule is a chain, so retiming anywhere shifts
	 * every downstream departure, and one pt leg is enough to make the constant-travel-time assumption unsafe for all
	 * of them.
	 */
	private static int countZeroDurationActs( Table tripsTable, boolean withPt ) {
		Set<String> personsWithPt = new HashSet<>();
		StringColumn persons = tripsTable.stringColumn( HeadersKN.personId );
		StringColumn modes = tripsTable.stringColumn( HeadersKN.mode );
		for ( int row = 0; row < tripsTable.rowCount(); row++ ) {
			if ( TransportMode.pt.equals( modes.get( row ) ) ) {
				personsWithPt.add( persons.get( row ) );
			}
		}
		DoubleColumn durations = tripsTable.doubleColumn( HeadersKN.activityDuration );
		int count = 0;
		for ( int row = 0; row < tripsTable.rowCount(); row++ ) {
			if ( durations.get( row ) == 0. && personsWithPt.contains( persons.get( row ) ) == withPt ) {
				count++;
			}
		}
		return count;
	}

	private static String countLine( String label, int count, int total ) {
		NumberFormat format = NumberFormat.getNumberInstance( Locale.GERMAN );
		format.setMaximumFractionDigits( 1 );
		return String.format( "%-75s %6d of %d (%s%%)%n", label + ":", count, total, format.format( 100. * count / total ) );
	}

	private static final String MUTTS_H = "mUTTS_h (incoming trip)";
	public static void setMUTTS_h( Activity activity, double mUTTSh ){
		activity.getAttributes().putAttribute( MUTTS_H, mUTTSh );
	}
	public static Double getMUTTS_h( Activity activity ) {
		return (Double) activity.getAttributes().getAttribute( MUTTS_H );
	}

	private static final String VTTS_H = "VTTS_h (incoming trip)";
	public static void setVTTS_h( Activity activity, double vttSh ){
		activity.getAttributes().putAttribute( VTTS_H, vttSh );
	}
	public static Double getVTTS_h( Activity activity ) {
		return (Double) activity.getAttributes().getAttribute( VTTS_H );
	}

	private static final String MUSE_H = "marginal_utility_of_starting_earlier_h";
	public static void setMUSE_h( Activity activity, double muse_h ){
		activity.getAttributes().putAttribute( MUSE_H, muse_h );
	}
	public static Double getMUSE_h( Activity activity ) {
		Double muse = (Double) activity.getAttributes().getAttribute( MUSE_H );
		if ( muse!=null ){
			return muse;
		} else {
			return (Double) activity.getAttributes().getAttribute( "marginal_utility_of_starting_later_h" );
		}
	}
	public static void setMUSE_h( Plan plan, double muse_h ) {
		plan.getAttributes().putAttribute( MUSE_H, muse_h );
	}
	public static Double getMUSE_h( Plan plan ) {
		return (Double) plan.getAttributes().getAttribute( MUSE_H );
	}

	public static void setActScore( Activity activity, double score ) {
		activity.getAttributes().putAttribute( "activityScore", score );
	}
}
