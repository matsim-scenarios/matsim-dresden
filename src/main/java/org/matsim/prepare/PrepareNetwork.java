package org.matsim.prepare;

import com.google.common.collect.Sets;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.contrib.emissions.HbefaRoadTypeMapping;
import org.matsim.contrib.emissions.OsmHbefaMapping;
import org.matsim.core.network.NetworkUtils;
import picocli.CommandLine;

import java.util.Set;

import static org.matsim.run.scenarios.DresdenUtils.getFreightModes;

@CommandLine.Command(
		name = "network",
		description = "Prepare network / link attributes."
)
public class PrepareNetwork implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(PrepareNetwork.class);

	@CommandLine.Option(names = "--network", description = "Path to network file", required = true)
	private String networkFile;

	@CommandLine.Option(names = "--output", description = "Output path of the prepared network", required = true)
	private String outputPath;

	@CommandLine.Option(
		names = "--pt-link-freespeed",
		description = "Set freespeed (m/s) for PT links (mutually exclusive with --pt-link-min-freespeed and --pt-link-freespeed-factor)"
	)
	private Double ptLinkFreespeed;

	@CommandLine.Option(
		names = "--pt-link-min-freespeed",
		description = "Set freespeed to at least this value (m/s) for PT links (mutually exclusive with --pt-link-freespeed and --pt-link-freespeed-factor)"
	)
	private Double ptLinkMinFreespeed;

	@CommandLine.Option(
		names = "--pt-link-freespeed-factor",
		description = "Multiply freespeed for PT links by this factor (mutually exclusive with --pt-link-freespeed and --pt-link-min-freespeed)"
	)
	private Double ptLinkFreespeedFactor;

	@CommandLine.Option(
		names = "--pt-link-id-prefix",
		description = "Only adjust links with this id prefix (default: pt_)",
		defaultValue = "pt_"
	)
	private String ptLinkIdPrefix;

	@CommandLine.Option(
		names = "--pt-link-mode",
		description = "Also adjust links whose allowed modes contain this mode (optional)",
		defaultValue = ""
	)
	private String ptLinkMode;

	public static void main(String[] args) {
		new PrepareNetwork().execute(args);
	}

	@Override
	public Integer call() {

		Network network = NetworkUtils.readNetwork(networkFile);

		prepareFreightNetwork(network);
		prepareEmissionsAttributes(network);
		adjustPtLinkFreespeedIfConfigured(network);

		NetworkUtils.writeNetwork(network, outputPath);

		return 0;
	}

	/**
	 * prepare link attributes for freight and truck as allowed modes together with car.
	 */
	public static void prepareFreightNetwork(Network network) {
		int linkCount = 0;

		for (Link link : network.getLinks().values()) {
			Set<String> modes = Sets.newHashSet(link.getAllowedModes());
			modes.remove(TransportMode.truck);

			// allow freight traffic together with cars
			if (modes.contains(TransportMode.car)) {
				modes.addAll(getFreightModes());
				linkCount++;
			}
			link.setAllowedModes(modes);
		}

		log.info("For {} links the freight modes {} have been added as allowed modes.", linkCount, getFreightModes());
		NetworkUtils.cleanNetwork(network, getFreightModes());
	}

	/**
	 * add hbefa link attributes.
	 */
	public static void prepareEmissionsAttributes(Network network) {
//		do not use VspHbefaRoadTypeMapping() as it results in almost every road to mapped to "highway"!
		HbefaRoadTypeMapping roadTypeMapping = OsmHbefaMapping.build();
		roadTypeMapping.addHbefaMappings(network);
	}

	private void adjustPtLinkFreespeedIfConfigured(Network network) {
		int optionCount = 0;
		if (ptLinkFreespeed != null) optionCount++;
		if (ptLinkMinFreespeed != null) optionCount++;
		if (ptLinkFreespeedFactor != null) optionCount++;
		if (optionCount == 0) return;
		if (optionCount > 1) {
			throw new IllegalArgumentException("Use only one of --pt-link-freespeed, --pt-link-min-freespeed, --pt-link-freespeed-factor.");
		}

		if (ptLinkFreespeed != null && ptLinkFreespeed <= 0) {
			throw new IllegalArgumentException("--pt-link-freespeed must be > 0");
		}
		if (ptLinkMinFreespeed != null && ptLinkMinFreespeed <= 0) {
			throw new IllegalArgumentException("--pt-link-min-freespeed must be > 0");
		}
		if (ptLinkFreespeedFactor != null && ptLinkFreespeedFactor <= 0) {
			throw new IllegalArgumentException("--pt-link-freespeed-factor must be > 0");
		}

		String mode = ptLinkMode == null ? "" : ptLinkMode.trim();
		String prefix = ptLinkIdPrefix == null ? "" : ptLinkIdPrefix.trim();

		int matched = 0;
		int updated = 0;
		for (Link link : network.getLinks().values()) {
			if (!isPtLink(link, prefix, mode)) continue;
			matched++;

			double original = link.getFreespeed();
			double next = original;
			if (ptLinkFreespeed != null) {
				next = ptLinkFreespeed;
			} else if (ptLinkMinFreespeed != null) {
				next = Math.max(original, ptLinkMinFreespeed);
			} else if (ptLinkFreespeedFactor != null) {
				next = original * ptLinkFreespeedFactor;
			}

			if (Double.compare(original, next) != 0) {
				link.setFreespeed(next);
				updated++;
			}
		}

		log.info("PT link freespeed adjustment done. Matched: {}, updated: {}, mode: '{}', idPrefix: '{}', set: {}, min: {}, factor: {}",
			matched, updated, mode, prefix, ptLinkFreespeed, ptLinkMinFreespeed, ptLinkFreespeedFactor);
	}

	private static boolean isPtLink(Link link, String idPrefix, String mode) {
		boolean matchesPrefix = !idPrefix.isEmpty() && link.getId().toString().startsWith(idPrefix);
		boolean matchesMode = !mode.isEmpty() && link.getAllowedModes().contains(mode);
		return matchesPrefix || matchesMode;
	}
}
