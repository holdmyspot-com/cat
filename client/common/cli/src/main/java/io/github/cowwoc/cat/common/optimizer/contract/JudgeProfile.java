package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The model, effort and instructions of the judge that decides whether a trial meets requirements without a
 * validated deterministic check. These requirements are called semantic requirements.
 * <p>
 * The judge decides only a test's semantic requirements. Deterministic checks decide the mechanical requirements
 * first; the judge receives their results as evidence but cannot override them, and no judge runs when a check failed
 * or no semantic requirement remains (design &sect;6.4).
 *
 * @param profile the role profile that the judge session runs under (design &sect;3.1)
 * @param prompt the instructions that the judge session receives
 * @param rubric what the judge checks in a trial's text, file effects and tool effects
 * @param decisionRule how the judge turns its findings into a pass or a failure
 * @param resultSchema the JSON schema, as JSON text, that a judge result must satisfy
 */
public record JudgeProfile(TrialProfile profile, String prompt, String rubric, String decisionRule,
	String resultSchema)
{
	/**
	 * Creates a judge profile.
	 *
	 * @param profile the role profile that the judge session runs under (design &sect;3.1)
	 * @param prompt the instructions that the judge session receives
	 * @param rubric what the judge checks in a trial's text, file effects and tool effects
	 * @param decisionRule how the judge turns its findings into a pass or a failure
	 * @param resultSchema the JSON schema, as JSON text, that a judge result must satisfy
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code profile} selects neither a model nor an effort, or any other
	 *   argument is blank
	 */
	public JudgeProfile
	{
		requireThat(profile, "profile").isNotNull();
		requireThat(profile.isBound(), "profile.isBound()").isTrue();
		requireThat(prompt, "prompt").isNotBlank();
		requireThat(rubric, "rubric").isNotBlank();
		requireThat(decisionRule, "decisionRule").isNotBlank();
		requireThat(resultSchema, "resultSchema").isNotBlank();
	}
}
