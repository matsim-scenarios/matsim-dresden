package org.matsim.analysis.vtts;

// These are "excel" column headers so we use: (1) no spaces; (2) underscores instead of camel case.
// Local copy of the (package-private) HeadersKN from matsim's org.matsim.application.analysis.population,
// reduced to the columns used by the VTTS classes in this package.
class HeadersKN {
	public static final String personId = "personId";
	public static final String tripIdx = "tripIdx";
	public static final String mode = "mode";
	public static final String vttsh = "VTTS_[Eu/h]";
	public static final String muttsh = "mUTTS_[u/h]";
	public static final String activity = "activity";
	public static final String activityDuration = "actDur";
	public static final String typicalDuration = "typDur";
	public static final String mUoM = "mUoM";
	public static final String muslh = "mUSL_[u/h]";
	public static final String scoringInputClass = "scoringInput";
	public static final String afterDayEnd = "afterDayEnd";

	// do not instantiate
	private HeadersKN() {}
}
