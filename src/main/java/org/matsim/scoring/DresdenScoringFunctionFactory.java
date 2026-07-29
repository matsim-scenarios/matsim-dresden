package org.matsim.scoring;

import com.google.inject.Inject;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.scoring.ScoringFunctionFactory;
import org.matsim.core.scoring.SumScoringFunction;
import org.matsim.core.scoring.functions.ActivityAttributeTypicalDurationCalculator;
import org.matsim.core.scoring.functions.CharyparNagelActivityScoring;
import org.matsim.core.scoring.functions.CharyparNagelAgentStuckScoring;
import org.matsim.core.scoring.functions.CharyparNagelLegScoring;
import org.matsim.core.scoring.functions.CharyparNagelMoneyScoring;
import org.matsim.core.scoring.functions.ScoringParameters;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.core.scoring.functions.SubpopulationScoringParameters;
import org.matsim.core.scoring.functions.TypicalDurationCalculator;

/**
 * A copy of {@link org.matsim.core.scoring.functions.CharyparNagelScoringFunctionFactory} (which is {@code final})
 * that enables the per-activity typical duration: the activity term is the stock
 * {@link CharyparNagelActivityScoring}, constructed with an {@link ActivityAttributeTypicalDurationCalculator} (each
 * activity is scored against its {@value org.matsim.prepare.EncodeTypicalDuration#TYPICAL_DURATION} attribute, see
 * {@link org.matsim.prepare.EncodeTypicalDuration}) and with the person, so the attributes are read from the selected
 * plan's main activities -- the activities handed to the scoring by the events machinery carry none, and the plan is
 * resolved lazily because scoring functions are created BEFORE replanning.
 * <p>
 * Person-subpopulation activities MUST carry survey-derived typical durations (the experiment's premise), enforced by
 * {@link RequiredTypicalDurationCalculator}; everything else (freight/commercial) falls back to the config typical
 * durations by design. When the schedule-delay corridor is armed, {@link DresdenActivityScoring} adds the corridor
 * terms on top. The leg, money and agent-stuck terms are the unchanged Charypar-Nagel ones.
 */
public final class DresdenScoringFunctionFactory implements ScoringFunctionFactory {

	private final Config config;
	private final Network network;

	private final ScoringParametersForPerson params;

	public DresdenScoringFunctionFactory(final Scenario sc) {
		this(sc.getConfig(), new SubpopulationScoringParameters(sc), sc.getNetwork());
	}

	@Inject
	DresdenScoringFunctionFactory(Config config, ScoringParametersForPerson params, Network network) {
		this.config = config;
		this.params = params;
		this.network = network;
	}

	@Override
	public ScoringFunction createNewScoringFunction(Person person) {

		final ScoringParameters parameters = params.getScoringParameters(person);
		DresdenScoringConfigGroup dresdenScoring = ConfigUtils.addOrGetModule(config, DresdenScoringConfigGroup.class);
		String subpopulation = PopulationUtils.getSubpopulation(person);

		TypicalDurationCalculator typicalDurationCalculator =
			"person".equals(subpopulation) && !dresdenScoring.isAllowConfigTypicalDurations()
				? new RequiredTypicalDurationCalculator(person)
				: new ActivityAttributeTypicalDurationCalculator();

		SumScoringFunction sumScoringFunction = new SumScoringFunction();
		sumScoringFunction.addScoringFunction(new CharyparNagelActivityScoring(parameters, typicalDurationCalculator, person));
		if (dresdenScoring.isScheduleDelayScoring()) {
			sumScoringFunction.addScoringFunction(new DresdenActivityScoring(parameters, person));
		}
		sumScoringFunction.addScoringFunction(new CharyparNagelLegScoring(parameters, config.transit().getTransitModes()));
		sumScoringFunction.addScoringFunction(new CharyparNagelMoneyScoring(parameters));
		sumScoringFunction.addScoringFunction(new CharyparNagelAgentStuckScoring(parameters));
		return sumScoringFunction;
	}
}
