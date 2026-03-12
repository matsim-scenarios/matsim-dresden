package org.matsim.fix;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkWriter;
import org.matsim.core.network.NetworkUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.matsim.utils.DresdenUtils.getFreightModes;

/**
 * This is a simple script, fixing the known problem in the input network.
 */
public class FixNetwork {
	public static void main(String[] args) {
		String output = args.length > 0 ? args[0] : "/Users/luchengqi/Documents/MATSimScenarios/Dresden/dresden-scenario/dresden-v1.1.1-network-with-pt.xml.gz";
		Network network = NetworkUtils.readNetwork("/Users/luchengqi/Documents/MATSimScenarios/Dresden/dresden-scenario/dresden-v1.1-network-with-pt.xml.gz");

		// shut down Augustus bridge for car and freight
		List<Link> augustusBridgeLinks = new ArrayList<>();
		augustusBridgeLinks.add(network.getLinks().get(Id.createLinkId("1301573740")));
		augustusBridgeLinks.add(network.getLinks().get(Id.createLinkId("237502199")));
		augustusBridgeLinks.add(network.getLinks().get(Id.createLinkId("12497357")));
		augustusBridgeLinks.add(network.getLinks().get(Id.createLinkId("1031454500")));
		augustusBridgeLinks.add(network.getLinks().get(Id.createLinkId("4265202")));
		augustusBridgeLinks.add(network.getLinks().get(Id.createLinkId("60611109#0")));

		augustusBridgeLinks.add(network.getLinks().get(Id.createLinkId("-99478092")));
		augustusBridgeLinks.add(network.getLinks().get(Id.createLinkId("-1329159900")));
		augustusBridgeLinks.add(network.getLinks().get(Id.createLinkId("-264360404")));
		augustusBridgeLinks.add(network.getLinks().get(Id.createLinkId("-12497357")));
		augustusBridgeLinks.add(network.getLinks().get(Id.createLinkId("-376145739")));
		augustusBridgeLinks.add(network.getLinks().get(Id.createLinkId("-376143083")));

		for (Link augustusBridgeLink : augustusBridgeLinks) {
			Set<String> allowedModes = new HashSet<>(augustusBridgeLink.getAllowedModes());
			allowedModes.remove(TransportMode.car);
			allowedModes.remove(TransportMode.ride);
			allowedModes.remove(TransportMode.truck);
			allowedModes.removeAll(getFreightModes());
			augustusBridgeLink.setAllowedModes(allowedModes);
		}

		// reduce the capacity of Carola:
		// 1. one lane per direction instead of two (before the collapse of the bridge)
		// 2. reduce the capacity to make everything consistent
		network.getLinks().get(Id.createLinkId("4214230")).setCapacity(800);
		network.getLinks().get(Id.createLinkId("4214230")).setNumberOfLanes(1);
		network.getLinks().get(Id.createLinkId("901959078")).setCapacity(800);
		network.getLinks().get(Id.createLinkId("901959078")).setNumberOfLanes(1);

		network.getLinks().get(Id.createLinkId("657862430")).setCapacity(800);
		network.getLinks().get(Id.createLinkId("657862430")).setNumberOfLanes(1);
		network.getLinks().get(Id.createLinkId("4214231")).setCapacity(800);
		network.getLinks().get(Id.createLinkId("4214231")).setNumberOfLanes(1);

		// reduce the capacity of the Marienbrücke, to make everything consistent
		network.getLinks().get(Id.createLinkId("-488766980")).setCapacity(800);
		network.getLinks().get(Id.createLinkId("761288685")).setCapacity(1600);

		// clean network
		NetworkUtils.cleanNetwork(network, Set.of(TransportMode.car, TransportMode.ride, TransportMode.truck));
		NetworkUtils.cleanNetwork(network, getFreightModes());

		// write down the prepared network
		new NetworkWriter(network).write(output);
	}
}
