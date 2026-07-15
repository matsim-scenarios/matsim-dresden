package org.matsim.scoring;

import org.matsim.core.config.ReflectiveConfigGroup;

/**
 * Config for the Dresden scoring extensions in {@link DresdenActivityScoring}, so the switch state is recorded in the
 * output config. Currently only the schedule-delay corridor switch; see {@code DresdenModel}'s
 * {@code --schedule-delay-scoring}.
 */
public final class DresdenScoringConfigGroup extends ReflectiveConfigGroup {

	public static final String GROUP_NAME = "dresdenScoring";

	/** Arm the schedule-delay corridor: per-activity anchors (initialStartTime/initialEndTime attributes) act as
	 * latestStartTime/earliestEndTime. Default false = only the (undefined) type-level config values apply. */
	private boolean scheduleDelayScoring = false;

	/** Allow person-subpopulation activities without a typicalDuration attribute to score against the config typical
	 * duration. Default false = such an activity aborts the run: the experiment requires every person activity to be
	 * scored against its survey-derived typical duration. Enable only for legacy type-encoded populations (v1.1 and
	 * earlier), whose typicals live in the activity type names. */
	private boolean allowConfigTypicalDurations = false;

	public DresdenScoringConfigGroup() {
		super(GROUP_NAME);
	}

	@StringGetter("allowConfigTypicalDurations")
	public boolean isAllowConfigTypicalDurations() {
		return allowConfigTypicalDurations;
	}

	@StringSetter("allowConfigTypicalDurations")
	public void setAllowConfigTypicalDurations(boolean allowConfigTypicalDurations) {
		this.allowConfigTypicalDurations = allowConfigTypicalDurations;
	}

	@StringGetter("scheduleDelayScoring")
	public boolean isScheduleDelayScoring() {
		return scheduleDelayScoring;
	}

	@StringSetter("scheduleDelayScoring")
	public void setScheduleDelayScoring(boolean scheduleDelayScoring) {
		this.scheduleDelayScoring = scheduleDelayScoring;
	}
}
