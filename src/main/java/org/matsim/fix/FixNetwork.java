package org.matsim.fix;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkWriter;
import org.matsim.core.network.NetworkUtils;

import java.util.Set;

import static org.matsim.run.scenarios.DresdenUtils.getFreightModes;

/**
 * This is a simple script, fixing the known problem in the input network.
 */
public final class FixNetwork {

	private FixNetwork(){
		// prevent instantiation
	}

	public static void main(String[] args) {
		String output = args.length > 0 ? args[0] : "/Users/luchengqi/Documents/MATSimScenarios/Dresden/dresden-scenario/v1.1/dresden-v1.1.3-network-with-pt.xml.gz";
		Network network = NetworkUtils.readNetwork("https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/dresden/dresden-v1.1/calibrated-10pct/output/012.output_network.xml.gz");

//		// shut down Augustus bridge for car and freight (already fixed)
//		List<Link> augustusBridgeLinks = new ArrayList<>();
//		augustusBridgeLinks.add(network.getLinks().get(Id.createLinkId("1301573740")));
//		augustusBridgeLinks.add(network.getLinks().get(Id.createLinkId("237502199")));
//		augustusBridgeLinks.add(network.getLinks().get(Id.createLinkId("12497357")));
//		augustusBridgeLinks.add(network.getLinks().get(Id.createLinkId("1031454500")));
//		augustusBridgeLinks.add(network.getLinks().get(Id.createLinkId("4265202")));
//		augustusBridgeLinks.add(network.getLinks().get(Id.createLinkId("60611109#0")));
//
//		augustusBridgeLinks.add(network.getLinks().get(Id.createLinkId("-99478092")));
//		augustusBridgeLinks.add(network.getLinks().get(Id.createLinkId("-1329159900")));
//		augustusBridgeLinks.add(network.getLinks().get(Id.createLinkId("-264360404")));
//		augustusBridgeLinks.add(network.getLinks().get(Id.createLinkId("-12497357")));
//		augustusBridgeLinks.add(network.getLinks().get(Id.createLinkId("-376145739")));
//		augustusBridgeLinks.add(network.getLinks().get(Id.createLinkId("-376143083")));
//
//		for (Link augustusBridgeLink : augustusBridgeLinks) {
//			Set<String> allowedModes = new HashSet<>(augustusBridgeLink.getAllowedModes());
//			allowedModes.remove(TransportMode.car);
//			allowedModes.remove(TransportMode.ride);
//			allowedModes.remove(TransportMode.truck);
//			allowedModes.removeAll(getFreightModes());
//			augustusBridgeLink.setAllowedModes(allowedModes);
//		}

		// adjust the capacity of the bridges
		// Carola bridge
		network.getLinks().get(Id.createLinkId("4214230")).setCapacity(1000);
		network.getLinks().get(Id.createLinkId("4214230")).setNumberOfLanes(1);
		network.getLinks().get(Id.createLinkId("901959078")).setCapacity(1000);
		network.getLinks().get(Id.createLinkId("901959078")).setNumberOfLanes(1);

		network.getLinks().get(Id.createLinkId("657862430")).setCapacity(1000);
		network.getLinks().get(Id.createLinkId("657862430")).setNumberOfLanes(1);
		network.getLinks().get(Id.createLinkId("4214231")).setCapacity(1000);
		network.getLinks().get(Id.createLinkId("4214231")).setNumberOfLanes(1);

		// reduce the capacity of the Marienbrücke, to make everything consistent
		network.getLinks().get(Id.createLinkId("-488766980")).setCapacity(800);
		network.getLinks().get(Id.createLinkId("761288685")).setCapacity(1200);

		// Waldschlösschenbrücke
		network.getLinks().get(Id.createLinkId("132572494")).setCapacity(3000);
		network.getLinks().get(Id.createLinkId("277710971")).setCapacity(3000);
		network.getLinks().get(Id.createLinkId("132577857")).setCapacity(3000);
		network.getLinks().get(Id.createLinkId("235255131")).setCapacity(3000);
		network.getLinks().get(Id.createLinkId("277710970")).setCapacity(3000);
		network.getLinks().get(Id.createLinkId("132572486")).setCapacity(3000);
		network.getLinks().get(Id.createLinkId("132577861")).setCapacity(3000);

		network.getLinks().get(Id.createLinkId("132572494")).setFreespeed(50/3.6);
		network.getLinks().get(Id.createLinkId("277710971")).setFreespeed(50/3.6);
		network.getLinks().get(Id.createLinkId("132577857")).setFreespeed(50/3.6);
		network.getLinks().get(Id.createLinkId("235255131")).setFreespeed(50/3.6);
		network.getLinks().get(Id.createLinkId("277710970")).setFreespeed(50/3.6);
		network.getLinks().get(Id.createLinkId("132572486")).setFreespeed(50/3.6);
		network.getLinks().get(Id.createLinkId("132577861")).setFreespeed(50/3.6);

		// Albert bridge (Further reduce the capacity to match the traffic count data)
		network.getLinks().get(Id.createLinkId("505502627#0")).setCapacity(800);
		network.getLinks().get(Id.createLinkId("-264360396#1")).setCapacity(800);

		// clean network
		NetworkUtils.cleanNetwork(network, Set.of(TransportMode.car, TransportMode.ride, TransportMode.truck));
		NetworkUtils.cleanNetwork(network, getFreightModes());

		// write down the prepared network
		new NetworkWriter(network).write(output);
	}
}
