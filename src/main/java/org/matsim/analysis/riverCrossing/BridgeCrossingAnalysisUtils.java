package org.matsim.analysis.riverCrossing;

import java.util.HashSet;
import java.util.Set;

public class BridgeCrossingAnalysisUtils {
	// bridges
	static final String CAROLA_BRIDGE = "Carolabrücke";
	static final String ALBERT_BRIDGE = "Albertbrücke";
	static final String FLUEGELWEG_BRIDGE = "Flügelwegbrücke";
	static final String MARIEN_BRIDGE = "Marienbrücke";
	static final String AUGUSTUS_BRIDGE = "Augustusbrücke";
	static final String WSB ="Waldschlößchenbrücke";
	static final String BLAUES_WUNDER_BRIDGE = "Blaues Wunder";
	static final String ELBE_BRIDGE_A4 = "Elbebrücke";

	// relevant stops
	static final String SYNAGOGE = "Dresden Synagoge";
	static final String CAROLAPLATZ = "Dresden Carolaplatz";
	static final String PIRNAISCHER_PLATZ = "Dresden Pirnaischer Platz";
	static final String ALBERTPLATZ = "Dresden Albertplatz";
	static final String THEATHER_PLATZ = "Dresden Theaterplatz";
	static final String NEU_STAEDTER_MARKT = "Dresden Neustädter Markt";


	static final String OTHER_STOP = "other";

	static Set<String> getRelevantStops(){
		Set<String> relevantStops = new HashSet<>();
		relevantStops.add(SYNAGOGE);
		relevantStops.add(CAROLAPLATZ);
		relevantStops.add(PIRNAISCHER_PLATZ);
		relevantStops.add(ALBERTPLATZ);
		relevantStops.add(THEATHER_PLATZ);
		relevantStops.add(NEU_STAEDTER_MARKT);
		return relevantStops;
	};

	// checking relevant connections
	// ### Carola bridge
	static boolean isCarolaBridge(String fromStop, String toStop) {
		return isCarolaBridgeSouthToNorth(fromStop, toStop) || isCarolaBridgeNorthToSouth(fromStop, toStop);
	}

	static boolean isCarolaBridgeSouthToNorth(String fromStop, String toStop) {
		// tram 3 + 7
		if (fromStop.equals(SYNAGOGE) && toStop.equals(CAROLAPLATZ)){
			return true;
		}
		// bus 261
		return fromStop.equals(PIRNAISCHER_PLATZ) && toStop.equals(ALBERTPLATZ);
	}

	static boolean isCarolaBridgeNorthToSouth(String fromStop, String toStop) {
		// tram 3 + 7
		if (fromStop.equals(CAROLAPLATZ) && toStop.equals(SYNAGOGE)){
			return true;
		}
		// bus 261
		return fromStop.equals(ALBERTPLATZ) && toStop.equals(PIRNAISCHER_PLATZ);
	}

	// ### Augustus bridge
	static boolean isAugustusBridge(String fromStop, String toStop) {
		return isAugustusBridgeSouthToNorth(fromStop, toStop) || isAugustusBridgeNorthToSouth(fromStop, toStop);
	}

	static boolean isAugustusBridgeSouthToNorth (String fromStop, String toStop) {
		return fromStop.equals(THEATHER_PLATZ) && toStop.equals(NEU_STAEDTER_MARKT);
	}

	static boolean isAugustusBridgeNorthToSouth (String fromStop, String toStop) {
		return fromStop.equals(NEU_STAEDTER_MARKT) && toStop.equals(THEATHER_PLATZ);
	}

	// ### Albert bridge
	// tram 6 + 13


	// ### WSB
	// bus 64 + 520 (?)


	// ### Blaues Wunder bridge
	// bus 61 + 63 + 84 + 521

	// ### Augustus bridge


	// ### Marien bridge
	// tram 6 + 11


	// ### Flügelweg bridge
	// bus 70 + 80


	// ### Regional trains


	// ### Ferry


}
