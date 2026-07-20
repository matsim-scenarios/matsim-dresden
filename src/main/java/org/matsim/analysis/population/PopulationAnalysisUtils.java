package org.matsim.analysis.population;

public class PopulationAnalysisUtils {
	static final double age0to17 = 0.9/100;
	static final double age18to25 = 0.0/100;
	static final double age26to35 = 2.3/100;
	static final double age36to50 = 1.4/100;
	static final double age51to60 = 5.2/100;
	static final double age61to70 = 14.9/100;
	static final double age71AndAbove = 34.4/100;

	static final double altstadt = 6.6/100;
	static final double neustadt = 3.7/100;
	static final double pieschen = 8.4/100;
	static final double klotzsche = 7.2/100;
	static final double Loschwitz = 13.1/100;
	static final double blasewitz = 10.0/100;
	static final double leuben = 8.8/100;
	static final double prohlis = 11.3/100;
	static final double plauen = 6.9/100;
	static final double cotta = 8.1/100;

	static final double overall = 8.4/100;

	public static double getAgeFactor(double age){
		if(age <= 17) return age0to17;
		if(age <= 25) return age18to25;
		if(age <= 35) return age26to35;
		if(age <= 50) return age36to50;
		if(age <= 60) return age51to60;
		if(age <= 70) return age61to70;
		return age71AndAbove;
	}

	public static double getHomeLocationFactor(String locationCode){
		if (locationCode.startsWith("0")) return altstadt;
		else if (locationCode.startsWith("1")) return neustadt;
		else if (locationCode.startsWith("2")) return pieschen;
		else if (locationCode.startsWith("3")) return klotzsche;
		else if (locationCode.startsWith("4")) return Loschwitz;
		else if (locationCode.startsWith("5")) return blasewitz;
		else if (locationCode.startsWith("6")) return leuben;
		else if (locationCode.startsWith("7")) return prohlis;
		else if (locationCode.startsWith("8")) return plauen;
		else if (locationCode.startsWith("9")) return cotta;
		return 0;
	}

}
