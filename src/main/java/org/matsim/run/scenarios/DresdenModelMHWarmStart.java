package org.matsim.run.scenarios;

import jakarta.annotation.Nullable;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.run.scenarios.DresdenUtils.EmissionsAnalysisHandling;
import org.matsim.simwrapper.SimWrapperConfigGroup.DefaultDashboardsMode;

/**
 * Dresden run that pre-relaxes activity timing per agent in iteration 0 via Metropolis-Hastings
 * sampling against the real Charypar-Nagel scoring function (see {@link MHTimeAllocationWarmStart}),
 * then proceeds with the normal co-evolutionary loop.
 *
 * <p>Extends {@link DresdenModel} directly (the wrap-around scoring path), so first/last activities
 * are scored as one combined overnight term, matching the warm-start's assumptions. Do not base this
 * on a variant that splits the wrap-around activity into morning/evening acts.
 */
public final class DresdenModelMHWarmStart extends DresdenModel {

	public DresdenModelMHWarmStart() {
	}

	/** Used by {@code MATSimApplication.execute(Class, Config, args)} (e.g. from tests). */
	public DresdenModelMHWarmStart(Config config) {
		super(config);
	}

	public static void main(String[] args) {
		MATSimApplication.execute(DresdenModelMHWarmStart.class, args);
	}

	@Nullable
	@Override
	protected Config prepareConfig(Config config) {
		super.prepareConfig(config);
		return config;
	}

	@Override
	protected void prepareControler(Controler controler) {
		super.prepareControler(controler);
		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				addControlerListenerBinding().to(MHTimeAllocationWarmStart.class);
			}
		});
	}

}
