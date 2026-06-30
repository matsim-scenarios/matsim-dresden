/* *********************************************************************** *
 * project: org.matsim.*
 * PougalaScoringFunctionFactory.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2026 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.scoring;

import com.google.inject.Inject;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.scoring.ScoringFunctionFactory;
import org.matsim.core.scoring.SumScoringFunction;
import org.matsim.core.scoring.functions.CharyparNagelActivityScoring;
import org.matsim.core.scoring.functions.CharyparNagelAgentStuckScoring;
import org.matsim.core.scoring.functions.CharyparNagelLegScoring;
import org.matsim.core.scoring.functions.CharyparNagelMoneyScoring;
import org.matsim.core.scoring.functions.ScoringParameters;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.core.scoring.functions.SubpopulationScoringParameters;

/**
 * A copy of {@link org.matsim.core.scoring.functions.CharyparNagelScoringFunctionFactory} that swaps
 * the {@link org.matsim.core.scoring.functions.CharyparNagelActivityScoring} activity term for our
 * {@link PougalaActivityScoring} (piecewise-linear deviation penalties driven by the plan-derived
 * activity-type tags), while keeping the Charypar-Nagel leg, money and agent-stuck terms.
 *
 * <p>Only agents of the {@value #PERSON_SUBPOPULATION} subpopulation get the Pougala activity term;
 * everyone else (commercial traffic, freight, ...) keeps the classic logarithmic Charypar-Nagel
 * activity scoring, since the plan-derived opening-time tags are only minted for that subpopulation
 * (cf. {@link org.matsim.run.scenarios.DresdenActivities}).
 *
 * @author michaz with claude
 */
public final class PougalaScoringFunctionFactory implements ScoringFunctionFactory {

	/** The subpopulation whose activities carry the plan-derived opening-time tags. */
	private static final String PERSON_SUBPOPULATION = "person";

	private final Config config;
	private Network network;

	private final ScoringParametersForPerson params;

	public PougalaScoringFunctionFactory(final Scenario sc) {
		this(sc.getConfig(), new SubpopulationScoringParameters(sc), sc.getNetwork());
	}

	@Inject
	PougalaScoringFunctionFactory(Config config, ScoringParametersForPerson params, Network network) {
		this.config = config;
		this.params = params;
		this.network = network;
	}

	@Override
	public ScoringFunction createNewScoringFunction(Person person) {

		final ScoringParameters parameters = params.getScoringParameters(person);

		SumScoringFunction sumScoringFunction = new SumScoringFunction();
		if (PERSON_SUBPOPULATION.equals(PopulationUtils.getSubpopulation(person))) {
			sumScoringFunction.addScoringFunction(new PougalaActivityScoring(parameters));
		} else {
			sumScoringFunction.addScoringFunction(new CharyparNagelActivityScoring(parameters));
		}
		sumScoringFunction.addScoringFunction(new CharyparNagelLegScoring(parameters, config.transit().getTransitModes()));
		sumScoringFunction.addScoringFunction(new CharyparNagelMoneyScoring(parameters));
		sumScoringFunction.addScoringFunction(new CharyparNagelAgentStuckScoring(parameters));
		return sumScoringFunction;
	}
}
