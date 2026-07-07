package org.matsim.scoring;

import com.google.inject.Inject;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.scoring.ScoringFunctionFactory;
import org.matsim.core.scoring.SumScoringFunction;
import org.matsim.core.scoring.functions.CharyparNagelAgentStuckScoring;
import org.matsim.core.scoring.functions.CharyparNagelLegScoring;
import org.matsim.core.scoring.functions.CharyparNagelMoneyScoring;
import org.matsim.core.scoring.functions.ScoringParameters;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.core.scoring.functions.SubpopulationScoringParameters;

/**
 * A copy of {@link org.matsim.core.scoring.functions.CharyparNagelScoringFunctionFactory} that swaps the
 * activity term for {@link DresdenActivityScoring}, which reads the per-activity typical duration from the
 * {@value org.matsim.prepare.EncodeTypicalDuration#TYPICAL_DURATION} attribute when present. The leg, money and
 * agent-stuck terms are the unchanged Charypar-Nagel ones.
 * <p>
 * {@link DresdenActivityScoring} falls back to the classic type-based typical duration when an activity has no
 * such attribute, so it is used for every subpopulation: activities carrying the attribute (the "person"
 * population) are scored against their plan-derived typical duration, everything else (freight/commercial) is
 * scored exactly as before.
 * <p>
 * The base factory is {@code final}, so this is a copy rather than a subclass.
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

		SumScoringFunction sumScoringFunction = new SumScoringFunction();
		sumScoringFunction.addScoringFunction(new DresdenActivityScoring(parameters));
		sumScoringFunction.addScoringFunction(new CharyparNagelLegScoring(parameters, config.transit().getTransitModes()));
		sumScoringFunction.addScoringFunction(new CharyparNagelMoneyScoring(parameters));
		sumScoringFunction.addScoringFunction(new CharyparNagelAgentStuckScoring(parameters));
		return sumScoringFunction;
	}
}
