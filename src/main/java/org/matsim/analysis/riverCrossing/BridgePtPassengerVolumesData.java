package org.matsim.analysis.riverCrossing;

import org.matsim.api.core.v01.Coord;

import static org.matsim.analysis.riverCrossing.BridgeCrossingAnalysisUtils.*;

public class BridgePtPassengerVolumesData {
	CountData carolaBridgeCountData = new CountData(CAROLA_BRIDGE, new Coord(832566.6, 5666813.65), new Coord(832689.11, 5666407.76));
	CountData augustusBridgeCountData = new CountData(AUGUSTUS_BRIDGE, new Coord(832161.05, 5666838.32), new Coord(832058.91, 5666558.62));
	CountData marienBridgeCountData = new CountData(MARIEN_BRIDGE);
	CountData fluegelwegBridgeCountData = new CountData(FLUEGELWEG_BRIDGE);
	CountData wsbBridgeCountData = new CountData(WSB);
	CountData blauesWunderBridgeCountData = new CountData(BLAUES_WUNDER_BRIDGE);
	CountData albertBridgeCountData = new CountData(ALBERT_BRIDGE);

	CountData localAndRegionalTrains = new CountData("Local and regional trains");
	CountData ferry = new CountData("Ferry");

	CountData elbeBridgeCountData = new CountData(ELBE_BRIDGE_A4);

	static class CountData {
		final String bridgeName;
		double total;
		double southToNorth;
		double northToSouth;
		Coord northEnd;
		Coord southEnd;

		CountData(String bridgeName) {
			this.bridgeName = bridgeName;
			this.southToNorth = 0;
			this.northToSouth = 0;
			this.total = 0;
		}

		CountData(String bridgeName, Coord northEnd, Coord southEnd) {
			this.bridgeName = bridgeName;
			this.southToNorth = 0;
			this.northToSouth = 0;
			this.total = 0;
			this.northEnd = northEnd;
			this.southEnd = southEnd;
		}
	}
}
