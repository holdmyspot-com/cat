package com.holdmyspot.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The model, effort and instructions of the judge that decides a test's assertions that no built-in tool decides,
 * including the expected-outcome assertion that CAT adds to every test.
 * <p>
 * Built-in tools decide their assertions first; the judge receives their results as evidence but cannot override
 * them, and no judge runs when a tool failed.
 *
 * @param profile the role profile that the judge session runs under
 * @param prompt the instructions that the judge session receives
 * @param rubric what the judge checks in a trial's text, file effects and tool effects
 * @param decisionRule how the judge turns its findings into a pass or a failure
 * @param resultSchema the JSON schema, as JSON text, that a judge result must satisfy
 * @param responsePrompt the instructions that a session judging {@link EvidenceScope#RESPONSE} assertions receives,
 *   or {@code null} for a judge frozen before the scope existed, whose assertions are all
 *   {@link EvidenceScope#ACTIONS}
 */
public record JudgeProfile(TrialProfile profile, String prompt, String rubric, String decisionRule,
	String resultSchema, String responsePrompt)
{
	/**
	 * Creates a judge profile.
	 *
	 * @param profile the role profile that the judge session runs under
	 * @param prompt the instructions that the judge session receives
	 * @param rubric what the judge checks in a trial's text, file effects and tool effects
	 * @param decisionRule how the judge turns its findings into a pass or a failure
	 * @param resultSchema the JSON schema, as JSON text, that a judge result must satisfy
	 * @param responsePrompt the instructions that a session judging {@link EvidenceScope#RESPONSE} assertions
	 *   receives, or {@code null} for a judge frozen before the scope existed
	 * @throws NullPointerException if any argument other than {@code responsePrompt} is null
	 * @throws IllegalArgumentException if {@code profile} does not name both a model and an effort, or any other
	 *   argument is blank
	 */
	public JudgeProfile
	{
		requireThat(profile, "profile").isNotNull();
		requireThat(profile.isComplete(), "profile.isComplete()").withContext(profile, "profile").isTrue();
		requireThat(prompt, "prompt").isNotBlank();
		requireThat(rubric, "rubric").isNotBlank();
		requireThat(decisionRule, "decisionRule").isNotBlank();
		requireThat(resultSchema, "resultSchema").isNotBlank();
		if (responsePrompt != null)
			requireThat(responsePrompt, "responsePrompt").isNotBlank();
	}

	/**
	 * Creates a judge profile without a response prompt, as a judge frozen before the scope existed has.
	 *
	 * @param profile the role profile that the judge session runs under
	 * @param prompt the instructions that the judge session receives
	 * @param rubric what the judge checks in a trial's text, file effects and tool effects
	 * @param decisionRule how the judge turns its findings into a pass or a failure
	 * @param resultSchema the JSON schema, as JSON text, that a judge result must satisfy
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code profile} does not name both a model and an effort, or any other
	 *   argument is blank
	 */
	public JudgeProfile(TrialProfile profile, String prompt, String rubric, String decisionRule,
		String resultSchema)
	{
		this(profile, prompt, rubric, decisionRule, resultSchema, null);
	}

	/**
	 * Returns the instructions of a session that judges assertions of one scope.
	 *
	 * @param scope the scope of the session's assertions
	 * @return the response prompt for {@link EvidenceScope#RESPONSE}, otherwise the prompt
	 * @throws NullPointerException if {@code scope} is null
	 * @throws IllegalStateException if {@code scope} is {@link EvidenceScope#RESPONSE} and the judge has no response
	 *   prompt
	 */
	public String prompt(EvidenceScope scope)
	{
		requireThat(scope, "scope").isNotNull();
		return switch (scope)
		{
			case ACTIONS -> prompt;
			case RESPONSE ->
			{
				if (responsePrompt == null)
					throw new IllegalStateException("The judge was frozen without a response prompt");
				yield responsePrompt;
			}
		};
	}

	/**
	 * Returns the judge's instructions under another role profile.
	 *
	 * @param other the role profile that the judge session runs under
	 * @return the judge
	 * @throws NullPointerException if {@code other} is null
	 * @throws IllegalArgumentException if {@code other} does not name both a model and an effort
	 */
	public JudgeProfile withProfile(TrialProfile other)
	{
		return new JudgeProfile(other, prompt, rubric, decisionRule, resultSchema, responsePrompt);
	}
}
