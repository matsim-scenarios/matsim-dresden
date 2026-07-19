package org.matsim.analysis;

import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.controler.Controler;
import org.matsim.core.population.PopulationUtils;
import org.matsim.store.PlanSnapshotWriterDuckDB;
import picocli.CommandLine;

import java.nio.file.Path;

import static org.matsim.application.ApplicationUtils.globFile;

/**
 * Converts a MATSim plans XML (initial, output, or experienced plans; plain, .gz or .zst)
 * into the plan-snapshot parquet format written by {@link PlanSnapshotWriterDuckDB}.
 *
 * Two modes:
 * <ul>
 *   <li><b>CLI</b>: {@code plans-to-parquet INPUT OUTPUT} — a plain two-argument conversion of an
 *       arbitrary plans file.</li>
 *   <li><b>Post-processing</b>: constructed with a run output folder + runId + a controller output
 *       file name (see {@code DresdenModel#preparePostProcessing}), it locates the corresponding
 *       {@code <runId>.output_<name>} the controller wrote to the run output dir (e.g. the output
 *       plans or the experienced plans) and converts it to {@code <runId>.output_<stem>.parquet}
 *       next to it.</li>
 * </ul>
 *
 * The snapshot has no iteration field: an arbitrary plans file has no intrinsic iteration.
 */
@CommandLine.Command(
	name = "plans-to-parquet",
	description = "Convert a MATSim plans XML (initial or experienced) to the plan-snapshot parquet format.",
	mixinStandardHelpOptions = true
)
public class PlansToParquet implements MATSimAppCommand {

	@CommandLine.Parameters(index = "0", paramLabel = "INPUT", description = "Path to the input plans file (.xml, .xml.gz, .xml.zst).")
	private String input;

	@CommandLine.Parameters(index = "1", paramLabel = "OUTPUT", description = "Path to the output .parquet file.")
	private String output;

	// Post-processing mode: set via the constructor instead of the CLI parameters.
	private Path outputFolder;
	private String runId;
	/** Base name of the controller output file to convert, e.g. {@code Controler.DefaultFiles.population.getFilename()} ("plans.xml"). */
	private String plansFileName;

	public PlansToParquet() {}

	/**
	 * Programmatic constructor for running as a post-processing step of a DresdenModel run: converts the
	 * {@code <runId>.output_<plansFileName>} the controller wrote to {@code outputFolder} (not an ITERS dir)
	 * into {@code <runId>.output_<stem>.parquet} next to it. Pass e.g.
	 * {@code Controler.DefaultFiles.population.getFilename()} for the output plans or
	 * {@code Controler.DefaultFiles.experiencedPlans.getFilename()} for the experienced plans.
	 */
	public PlansToParquet(Path outputFolder, String runId, String plansFileName) {
		this.outputFolder = outputFolder;
		this.runId = runId;
		this.plansFileName = plansFileName;
	}

	public static void main(String[] args) {
		new PlansToParquet().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		String in = input;
		String out = output;
		if (outputFolder != null) {
			String runPrefix = runId != null ? runId + "." : "";
			// trailing "*" matches any (or no) compression suffix, like the VTTS analysis does
			in = globFile(outputFolder, runPrefix + "*output_" + plansFileName + "*").toString();
			String stem = plansFileName.endsWith(".xml") ? plansFileName.substring(0, plansFileName.length() - 4) : plansFileName;
			out = outputFolder.resolve(runPrefix + "output_" + stem + ".parquet").toString();
		}
		Population population = PopulationUtils.readPopulation(in);
		new PlanSnapshotWriterDuckDB().write(population, out);
		return 0;
	}
}
