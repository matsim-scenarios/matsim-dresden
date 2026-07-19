package org.matsim.analysis;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.application.MATSimAppCommand;
import picocli.CommandLine;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Plants the static DuckDB descriptor {@code queries.sql} (a classpath resource) into a run output dir,
 * next to the plan parquet files. The descriptor is run-agnostic — it names the parquet files via relative
 * globs and defines saved queries as views/macros — so it is a plain copy, not a template.
 *
 * Wired as a post-processing step (see {@code DresdenModel#preparePostProcessing}).
 */
@CommandLine.Command(
	name = "write-duckdb-queries",
	description = "Plant the DuckDB queries.sql descriptor (views + saved queries) into a run output dir.",
	mixinStandardHelpOptions = true
)
public class WriteDuckDbQueries implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(WriteDuckDbQueries.class);
	private static final String RESOURCE = "/queries.sql";

	@CommandLine.Option(names = "--output-dir", description = "Run output directory to write queries.sql into.", required = true)
	private Path outputFolder;

	public WriteDuckDbQueries() {}

	public WriteDuckDbQueries(Path outputFolder) {
		this.outputFolder = outputFolder;
	}

	@Override
	public Integer call() throws Exception {
		Path target = outputFolder.resolve("queries.sql");
		try (InputStream in = getClass().getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("Classpath resource " + RESOURCE + " not found");
			}
			Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
		}
		log.info("Wrote DuckDB descriptor to {}", target);
		return 0;
	}
}
