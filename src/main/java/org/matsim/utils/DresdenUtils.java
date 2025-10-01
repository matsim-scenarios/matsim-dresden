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

	private DresdenUtils() {

	}

	/**
	 * Helper enum to enable/disable functionalities.
	 */
	public enum FunctionalityHandling {ENABLED, DISABLED}
}
