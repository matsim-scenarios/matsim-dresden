package org.matsim.utils;

import java.util.Set;

/**
 * Utils class for Dresden scenario with often used parameters and/or methods.
 */
public final class DresdenUtils {
	public static final String HEAVY_MODE = "truck40t";
	public static final String MEDIUM_MODE = "truck18t";
	public static final String LIGHT_MODE = "truck8t";
	public static final Set<String> FREIGHT_MODES = Set.of(HEAVY_MODE, MEDIUM_MODE, LIGHT_MODE);

	//	To decrypt hbefa input files set MATSIM_DECRYPTION_PASSWORD as environment variable. ask VSP for access.
	public static final String HBEFA_2020_PATH = "https://svn.vsp.tu-berlin.de/repos/public-svn/3507bb3997e5657ab9da76dbedbb13c9b5991d3e/0e73947443d68f95202b71a156b337f7f71604ae/";
	public static final String HBEFA_FILE_COLD_DETAILED = HBEFA_2020_PATH + "82t7b02rc0rji2kmsahfwp933u2rfjlkhfpi2u9r20.enc";
	public static final String HBEFA_FILE_WARM_DETAILED = HBEFA_2020_PATH + "944637571c833ddcf1d0dfcccb59838509f397e6.enc";
	public static final String HBEFA_FILE_COLD_AVERAGE = HBEFA_2020_PATH + "r9230ru2n209r30u2fn0c9rn20n2rujkhkjhoewt84202.enc" ;
	public static final String HBEFA_FILE_WARM_AVERAGE = HBEFA_2020_PATH + "7eff8f308633df1b8ac4d06d05180dd0c5fdf577.enc";

	private DresdenUtils() {

	}

	/**
	 * Helper enum to enable/disable functionalities.
	 */
	public enum FunctionalityHandling {ENABLED, DISABLED}
}
