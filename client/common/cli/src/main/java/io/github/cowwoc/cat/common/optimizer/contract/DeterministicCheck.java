package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.util.Map;

/**
 * A condition that decides one test requirement directly from trial evidence, without a judge. Its known-good
 * control supplies evidence it must accept; its known-bad control supplies evidence it must reject (design
 * &sect;1.2, &sect;6.4).
 * <p>
 * The check belongs to the test that lists it, so its identity within the contract is the test and the requirement.
 *
 * @param requirementId the requirement that the check decides
 * @param criterion the kind and its parameters
 * @param knownGood evidence that the check must accept
 * @param knownBad evidence that the check must reject
 */
public record DeterministicCheck(String requirementId, CheckCriterion criterion, ControlEvidence knownGood,
	ControlEvidence knownBad)
{
	/**
	 * Creates a check.
	 *
	 * @param requirementId the requirement that the check decides
	 * @param criterion the kind and its parameters
	 * @param knownGood evidence that the check must accept
	 * @param knownBad evidence that the check must reject
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code requirementId} is blank
	 */
	public DeterministicCheck
	{
		requireThat(requirementId, "requirementId").isNotBlank();
		requireThat(criterion, "criterion").isNotNull();
		requireThat(knownGood, "knownGood").isNotNull();
		requireThat(knownBad, "knownBad").isNotNull();
	}

	/**
	 * Decides the check from evidence.
	 *
	 * @param evidence the evidence
	 * @param textByDimension the text of the variation value that the evidence drew for each dimension that the
	 *   parameters name
	 * @return the result
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if the evidence cannot be read
	 * @throws UndecidableCheckException if the evidence does not record what the check's kind observes
	 */
	public CheckResult decide(CheckEvidence evidence, Map<String, String> textByDimension)
		throws IOException, UndecidableCheckException
	{
		requireThat(evidence, "evidence").isNotNull();
		requireThat(textByDimension, "textByDimension").isNotNull();
		CheckCriterion.Decision decision = criterion.decide(evidence, textByDimension);
		return new CheckResult(this, decision.passed(), decision.observed());
	}
}
