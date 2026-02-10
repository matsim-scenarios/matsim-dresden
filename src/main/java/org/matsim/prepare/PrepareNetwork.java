package org.matsim.prepare;

import com.google.common.collect.Sets;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.contrib.emissions.HbefaRoadTypeMapping;
import org.matsim.contrib.emissions.OsmHbefaMapping;
import org.matsim.core.network.NetworkUtils;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

	public static void main(String[] args) {
		new PrepareNetwork().execute(args);
	}

	@Override
	public Integer call() {

		Network network = NetworkUtils.readNetwork(networkFile);

		fixAugustusBridgeAllowedModes(network);
		prepareFreightNetwork(network);
		prepareEmissionsAttributes(network);

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

	/**
	 * do not allow car and truck on the Augustus bridge
	 */
	public static void fixAugustusBridgeAllowedModes(Network network){
		// The links to be closed down for car and truck (bike is still allowed)
		List<Link> linksToFix = new ArrayList<>();
		linksToFix.add(network.getLinks().get(Id.createLinkId("237502199")));
		linksToFix.add(network.getLinks().get(Id.createLinkId("12497357")));
		linksToFix.add(network.getLinks().get(Id.createLinkId("1031454500")));
		linksToFix.add(network.getLinks().get(Id.createLinkId("4265202")));
		linksToFix.add(network.getLinks().get(Id.createLinkId("60611109#0")));
		linksToFix.add(network.getLinks().get(Id.createLinkId("-99478092")));
		linksToFix.add(network.getLinks().get(Id.createLinkId("-1329159900")));
		linksToFix.add(network.getLinks().get(Id.createLinkId("-264360404")));
		linksToFix.add(network.getLinks().get(Id.createLinkId("-12497357")));
		linksToFix.add(network.getLinks().get(Id.createLinkId("-376145739")));

		for (Link link : linksToFix) {
			Set<String> originalAllowedModes = link.getAllowedModes();
			Set<String> updatedAllowedModes = new HashSet<>(originalAllowedModes);
			updatedAllowedModes.remove(TransportMode.car);
			updatedAllowedModes.remove(TransportMode.truck);
			updatedAllowedModes.remove(TransportMode.ride);
			link.setAllowedModes(updatedAllowedModes);
		}
	}
}
