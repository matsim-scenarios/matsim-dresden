package org.matsim.analysis.riverCrossing;

import org.matsim.api.core.v01.Coord;

import static org.matsim.analysis.riverCrossing.BridgeCrossingAnalysisUtils.*;

public class BridgePtPassengerVolumesData {
	CountData carolaBridgeCountData = new CountData(CAROLA_BRIDGE, new Coord(832566.6, 5666813.65), new Coord(832689.11, 5666407.76));
	CountData augustusBridgeCountData = new CountData(AUGUSTUS_BRIDGE, new Coord(832161.05, 5666838.32), new Coord(832058.91, 5666558.62));
	CountData marienBridgeCountData = new CountData(MARIEN_BRIDGE, new Coord(831691.88, 5667345.4), new Coord(831344.38, 5667103.88));
	CountData fluegelwegBridgeCountData = new CountData(FLUEGELWEG_BRIDGE, new Coord(828509.2, 5667643.9), new Coord(828586.31, 5667313.32));
	CountData albertBridgeCountData = new CountData(ALBERT_BRIDGE, new Coord(833036.49, 5667070.41), new Coord(833243.24, 5666807.09));
	CountData wsbBridgeCountData = new CountData(WSB, new Coord(834602.08, 5668117.93), new Coord(834694.65, 5667441.53));
	CountData blauesWunderBridgeCountData = new CountData(BLAUES_WUNDER_BRIDGE, new Coord(837162.67, 5666855.47), new Coord(836940.64, 5666696.95));

	CountData trainMarienBridge = new CountData(TRAIN_MARIEN_BRIDGE, new Coord(832085.45, 5667844.89), new Coord(831026.32, 5666703.9));
	CountData rb31 = new CountData("RB31");
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
