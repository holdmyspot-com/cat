package com.holdmyspot.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;

/**
 * One model and reasoning-effort combination that a session runs under, optionally with a lower tool-output token
 * limit, or a pattern over such combinations.
 * <p>
 * A session's profile names both its model and its effort, so that no session runs under the harness's default
 * model or effort; the effort {@value #NO_REASONING} selects no reasoning. In a pattern, such as a prompt file's
 * binding or the user's restriction, an empty model or effort is open and matches every value. A tool-output token
 * limit of {@code 0} leaves the harness's default limit; contract profiles always do.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param model the model, or empty for a pattern that leaves it open
 * @param effort the reasoning effort, or empty for a pattern that leaves it open
 * @param toolOutputTokenLimit the most tokens of each tool output that the session keeps, or {@code 0} for the
 *   harness's default
 */
public record TrialProfile(String model, String effort, int toolOutputTokenLimit)
{
	/**
	 * The effort of a session that runs without reasoning, which the profile inventories give a model that lists no
	 * reasoning effort.
	 */
	public static final String NO_REASONING = HarnessRunnerOptions.NO_REASONING;
	/**
	 * The pattern that leaves both dimensions open.
	 */
	public static final TrialProfile UNBOUND = new TrialProfile("", "");
	/**
	 * The label of an open dimension.
	 */
	private static final String OPEN_LABEL = "default";
	/**
	 * The text before the token count in the label of a tool-output token limit.
	 */
	static final String TOOL_OUTPUT_LABEL = "tool-output-";

	/**
	 * Creates a profile.
	 *
	 * @param model the model, or empty for a pattern that leaves it open
	 * @param effort the reasoning effort, or empty for a pattern that leaves it open
	 * @param toolOutputTokenLimit the most tokens of each tool output that the session keeps, or {@code 0} for the
	 *   harness's default
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if a dimension contains whitespace or the character {@code /}, or the
	 *   tool-output token limit is negative
	 */
	public TrialProfile
	{
		requireThat(model, "model").doesNotContainWhitespace().doesNotContain("/");
		requireThat(effort, "effort").doesNotContainWhitespace().doesNotContain("/");
		requireThat(toolOutputTokenLimit, "toolOutputTokenLimit").isNotNegative();
	}

	/**
	 * Creates a profile with the harness's default tool-output token limit.
	 *
	 * @param model the model, or empty for a pattern that leaves it open
	 * @param effort the reasoning effort, or empty for a pattern that leaves it open
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if a dimension contains whitespace or the character {@code /}
	 */
	public TrialProfile(String model, String effort)
	{
		this(model, effort, 0);
	}

	/**
	 * Returns the session profile that stored data names. A stored empty effort stands for a model without reasoning
	 * efforts and is read as {@value #NO_REASONING}.
	 *
	 * @param model the stored model
	 * @param effort the stored effort
	 * @return the profile
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if a dimension contains whitespace or the character {@code /}
	 */
	public static TrialProfile stored(String model, String effort)
	{
		requireThat(effort, "effort").isNotNull();
		if (effort.isEmpty())
			return new TrialProfile(model, NO_REASONING);
		return new TrialProfile(model, effort);
	}

	/**
	 * Returns this profile with a tool-output token limit.
	 *
	 * @param tokens the most tokens of each tool output that the session keeps
	 * @return the limited profile
	 * @throws IllegalArgumentException if {@code tokens} is not positive
	 */
	public TrialProfile withToolOutputTokenLimit(int tokens)
	{
		requireThat(tokens, "tokens").isPositive();
		return new TrialProfile(model, effort, tokens);
	}

	/**
	 * Indicates whether the profile selects both dimensions, as a session's profile does.
	 *
	 * @return {@code true} if neither dimension is empty
	 */
	public boolean isComplete()
	{
		return !model.isEmpty() && !effort.isEmpty();
	}

	/**
	 * Indicates whether the profile selects at least one dimension.
	 *
	 * @return {@code true} if a dimension is not empty
	 */
	public boolean isBound()
	{
		return !model.isEmpty() || !effort.isEmpty();
	}

	/**
	 * Indicates whether this profile matches a pattern. The tool-output token limit is not compared.
	 *
	 * @param pattern a profile whose empty dimensions are open and match every value
	 * @return {@code true} if every non-empty dimension of {@code pattern} equals this profile's
	 * @throws NullPointerException if {@code pattern} is null
	 */
	public boolean matches(TrialProfile pattern)
	{
		requireThat(pattern, "pattern").isNotNull();
		return (pattern.model.isEmpty() || pattern.model.equals(model)) &&
			(pattern.effort.isEmpty() || pattern.effort.equals(effort));
	}

	/**
	 * Returns the profile as {@code model/effort}, naming an open dimension {@code default}, followed by
	 * {@code /tool-output-} and the token count if the profile limits tool output.
	 *
	 * @return the label
	 */
	public String label()
	{
		String label = dimension(model) + "/" + dimension(effort);
		if (toolOutputTokenLimit == 0)
			return label;
		return label + "/" + TOOL_OUTPUT_LABEL + toolOutputTokenLimit;
	}

	/**
	 * Returns the label of one dimension.
	 *
	 * @param value the dimension's value
	 * @return {@code value}, or {@code default} if it is empty
	 */
	private static String dimension(String value)
	{
		if (value.isEmpty())
			return OPEN_LABEL;
		return value;
	}

	@Override
	public String toString()
	{
		return label();
	}
}
