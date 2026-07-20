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
	static final String WSB = "Waldschlößchenbrücke";
	static final String BLAUES_WUNDER_BRIDGE = "Loschwitzer Brücke";

	static final String ELBE_BRIDGE_A4 = "Elbebrücke Dresden (A4)";
	static final String TRAIN_MARIEN_BRIDGE = "Trains (Marienbrücke)";
	static final String TRAIN_NIEDERWARTHA_BRIDGE = "Trains (Niederwarthaer Brücke)";

	// relevant stops
	// trams + buses
	static final String SYNAGOGE = "Dresden Synagoge";
	static final String CAROLAPLATZ = "Dresden Carolaplatz";
	static final String PIRNAISCHER_PLATZ = "Dresden Pirnaischer Platz";
	static final String ALBERTPLATZ = "Dresden Albertplatz";

	static final String THEATHER_PLATZ = "Dresden Theaterplatz";
	static final String NEU_STAEDTER_MARKT = "Dresden Neustädter Markt";

	static final String KONGRESSZENTRUM = "DD Kongresszentrum/H d Presse";
	static final String ANTON_STRASSE_LEIPZIGER_STRASSE = "Dresden Anton-/Leipziger Str.";

	static final String FLUEGELWEG = "Dresden Flügelweg";
	static final String WERFTSTRASSE = "Dresden Werftstraße";

	static final String SACHSENALLEE = "Dresden Sachsenallee";
	static final String ROSA_LUXEMBURG_PLATZ = "Dresden Rosa-Luxemburg-Platz";

	static final String KAETHE_KOLLWITZ_UFER = "Dresden Käthe-Kollwitz-Ufer";
	static final String WALDSCHLOSSCHEN = "Dresden Waldschlößchen";

	static final String SCHILLERPLATZ = "Dresden Schillerplatz";
	static final String KOERNERPLATZ = "Dresden Körnerplatz";

	// trains
	static final String BAHNHOF_MITTE = "Dresden Mitte";
	static final String BAHNHOF_NEUSTADT = "Dresden-Neustadt";

	static final String BAHNHOF_NIEDERWARTHA = "Niederwartha";
	static final String BAHNHOF_RADEBEUL_NAUNDORF = "Radebeul-Naundorf";

	// ferries
	static final String FERRY_JOHANNSTADT = "Dresden Fährstelle Johannstadt";
	static final String FERRY_NEUSTADT = "Dresden Fährstelle Neustadt";
	static final String FERRY_LAUBEGAST = "Dresden Fährstelle Laubegast";
	static final String FERRY_NIEDERPOYRITZ = "DD Fährstelle Niederpoyritz";
	static final String FERRY_KLEINZCHACHWITZ = "DD Fährstelle Kleinzschachwitz";
	static final String FERRY_PILLNITZ = "Dresden Fährstelle Pillnitz";


	static Set<String> getRelevantStops() {
		Set<String> relevantStops = new HashSet<>();
		relevantStops.add(SYNAGOGE);
		relevantStops.add(CAROLAPLATZ);
		relevantStops.add(PIRNAISCHER_PLATZ);
		relevantStops.add(ALBERTPLATZ);
		relevantStops.add(THEATHER_PLATZ);
		relevantStops.add(NEU_STAEDTER_MARKT);
		relevantStops.add(KONGRESSZENTRUM);
		relevantStops.add(ANTON_STRASSE_LEIPZIGER_STRASSE);
		relevantStops.add(FLUEGELWEG);
		relevantStops.add(WERFTSTRASSE);
		relevantStops.add(SACHSENALLEE);
		relevantStops.add(ROSA_LUXEMBURG_PLATZ);
		relevantStops.add(KAETHE_KOLLWITZ_UFER);
		relevantStops.add(WALDSCHLOSSCHEN);
		relevantStops.add(SCHILLERPLATZ);
		relevantStops.add(KOERNERPLATZ);

		relevantStops.add(BAHNHOF_MITTE);
		relevantStops.add(BAHNHOF_NEUSTADT);
		relevantStops.add(BAHNHOF_NIEDERWARTHA);
		relevantStops.add(BAHNHOF_RADEBEUL_NAUNDORF);

		relevantStops.add(FERRY_JOHANNSTADT);
		relevantStops.add(FERRY_NEUSTADT);
		relevantStops.add(FERRY_LAUBEGAST);
		relevantStops.add(FERRY_NIEDERPOYRITZ);
		relevantStops.add(FERRY_KLEINZCHACHWITZ);
		relevantStops.add(FERRY_PILLNITZ);

		return relevantStops;
	}

	// checking relevant connections
	// ### Carola bridge
	static boolean isCarolaBridge(String fromStop, String toStop) {
		return isCarolaBridgeSouthToNorth(fromStop, toStop) || isCarolaBridgeNorthToSouth(fromStop, toStop);
	}

	static boolean isCarolaBridgeSouthToNorth(String fromStop, String toStop) {
		// tram 3 + 7
		if (fromStop.equals(SYNAGOGE) && toStop.equals(CAROLAPLATZ)) {
			return true;
		}
		// bus 261
		return fromStop.equals(PIRNAISCHER_PLATZ) && toStop.equals(ALBERTPLATZ);
	}

	static boolean isCarolaBridgeNorthToSouth(String fromStop, String toStop) {
		// tram 3 + 7
		if (fromStop.equals(CAROLAPLATZ) && toStop.equals(SYNAGOGE)) {
			return true;
		}
		// bus 261
		return fromStop.equals(ALBERTPLATZ) && toStop.equals(PIRNAISCHER_PLATZ);
	}

	// ### Augustus bridge
	static boolean isAugustusBridge(String fromStop, String toStop) {
		return isAugustusBridgeSouthToNorth(fromStop, toStop) || isAugustusBridgeNorthToSouth(fromStop, toStop);
	}

	static boolean isAugustusBridgeSouthToNorth(String fromStop, String toStop) {
		return fromStop.equals(THEATHER_PLATZ) && toStop.equals(NEU_STAEDTER_MARKT);
	}

	static boolean isAugustusBridgeNorthToSouth(String fromStop, String toStop) {
		return fromStop.equals(NEU_STAEDTER_MARKT) && toStop.equals(THEATHER_PLATZ);
	}

	// ### Marien bridge
	// tram 6 + 11
	static boolean isMarienBridge(String fromStop, String toStop) {
		return isMarienBridgeSouthToNorth(fromStop, toStop) || isMarienBridgeNorthToSouth(fromStop, toStop);
	}

	static boolean isMarienBridgeSouthToNorth(String fromStop, String toStop) {
		return fromStop.equals(KONGRESSZENTRUM) && toStop.equals(ANTON_STRASSE_LEIPZIGER_STRASSE);
	}

	static boolean isMarienBridgeNorthToSouth(String fromStop, String toStop) {
		return fromStop.equals(ANTON_STRASSE_LEIPZIGER_STRASSE) && toStop.equals(KONGRESSZENTRUM);
	}

	// ### Flügelweg bridge
	// bus 70 + 80
	static boolean isFluegelwegBridge(String fromStop, String toStop) {
		return isFluegelwegBridgeSouthToNorth(fromStop, toStop) || isFluegelwegBridgeNorthToSouth(fromStop, toStop);
	}

	static boolean isFluegelwegBridgeSouthToNorth(String fromStop, String toStop) {
		return fromStop.equals(FLUEGELWEG) && toStop.equals(WERFTSTRASSE);
	}

	static boolean isFluegelwegBridgeNorthToSouth(String fromStop, String toStop) {
		return fromStop.equals(WERFTSTRASSE) && toStop.equals(FLUEGELWEG);
	}

	// ### Albert bridge
	// tram 6 + 13
	static boolean isAlbertBridge(String fromStop, String toStop) {
		return isAlbertBridgeSouthToNorth(fromStop, toStop) || isAlbertBridgeNorthToSouth(fromStop, toStop);
	}

	static boolean isAlbertBridgeSouthToNorth(String fromStop, String toStop) {
		return fromStop.equals(SACHSENALLEE) && toStop.equals(ROSA_LUXEMBURG_PLATZ);
	}

	static boolean isAlbertBridgeNorthToSouth(String fromStop, String toStop) {
		return fromStop.equals(ROSA_LUXEMBURG_PLATZ) && toStop.equals(SACHSENALLEE);
	}

	// ### WSB
	// bus 64 + 520
	static boolean isWsbBridge(String fromStop, String toStop) {
		return isWsbBridgeSouthToNorth(fromStop, toStop) || isWsbBridgeNorthToSouth(fromStop, toStop);
	}

	static boolean isWsbBridgeSouthToNorth(String fromStop, String toStop) {
		return fromStop.equals(KAETHE_KOLLWITZ_UFER) && toStop.equals(WALDSCHLOSSCHEN);
	}

	static boolean isWsbBridgeNorthToSouth(String fromStop, String toStop) {
		return fromStop.equals(WALDSCHLOSSCHEN) && toStop.equals(KAETHE_KOLLWITZ_UFER);
	}

	// ### Blaues Wunder bridge
	// bus 61 + 63 + 84 + 521
	static boolean isBlauesWunderBridge(String fromStop, String toStop) {
		return isBlauesWunderBridgeSouthToNorth(fromStop, toStop) || isBlauesWunderBridgeNorthToSouth(fromStop, toStop);
	}

	static boolean isBlauesWunderBridgeSouthToNorth(String fromStop, String toStop) {
		return fromStop.equals(SCHILLERPLATZ) && toStop.equals(KOERNERPLATZ);
	}

	static boolean isBlauesWunderBridgeNorthToSouth(String fromStop, String toStop) {
		return fromStop.equals(KOERNERPLATZ) && toStop.equals(SCHILLERPLATZ);
	}

	// ### Local and egional trains
	static boolean isTrainMarienBridge(String fromStop, String toStop) {
		return isTrainMarienBridgeNorthToSouth(fromStop, toStop) || isTrainMarienBridgeSouthToNorth(fromStop, toStop);
	}

	static boolean isTrainMarienBridgeSouthToNorth(String fromStop, String toStop) {
		return fromStop.equals(BAHNHOF_MITTE) && toStop.equals(BAHNHOF_NEUSTADT);
	}

	static boolean isTrainMarienBridgeNorthToSouth(String fromStop, String toStop) {
		return fromStop.equals(BAHNHOF_NEUSTADT) && toStop.equals(BAHNHOF_MITTE);
	}

	// ### RB 31 Niederwartha bridge
	static boolean isRB31NiederwarthaBridge(String fromStop, String toStop) {
		return isRB31NiederwarthaBridgeSouthToNorth(fromStop, toStop) || isRB31NiederwarthaBridgeNorthToSouth(fromStop, toStop);
	}

	static boolean isRB31NiederwarthaBridgeSouthToNorth(String fromStop, String toStop) {
		return fromStop.equals(BAHNHOF_NIEDERWARTHA) && toStop.equals(BAHNHOF_RADEBEUL_NAUNDORF);
	}

	static boolean isRB31NiederwarthaBridgeNorthToSouth(String fromStop, String toStop) {
		return fromStop.equals(BAHNHOF_RADEBEUL_NAUNDORF) && toStop.equals(BAHNHOF_NIEDERWARTHA);
	}

	// ### Ferry
	static boolean isFerry(String fromStop, String toStop) {
		return isFerrySouthToNorth(fromStop, toStop) || isFerryNorthToSouth(fromStop, toStop);
	}

	static boolean isFerrySouthToNorth(String fromStop, String toStop) {
		return (fromStop.equals(FERRY_JOHANNSTADT) && toStop.equals(FERRY_NEUSTADT))
			|| (fromStop.equals(FERRY_LAUBEGAST) && toStop.equals(FERRY_NIEDERPOYRITZ))
			|| (fromStop.equals(FERRY_KLEINZCHACHWITZ) && toStop.equals(FERRY_PILLNITZ));
	}

	static boolean isFerryNorthToSouth(String fromStop, String toStop) {
		return (fromStop.equals(FERRY_NEUSTADT) && toStop.equals(FERRY_JOHANNSTADT))
			|| (fromStop.equals(FERRY_NIEDERPOYRITZ) && toStop.equals(FERRY_LAUBEGAST))
			|| (fromStop.equals(FERRY_PILLNITZ) && toStop.equals(FERRY_KLEINZCHACHWITZ));
	}
}
