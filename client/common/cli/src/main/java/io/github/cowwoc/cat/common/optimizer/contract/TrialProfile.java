package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * One model and reasoning-effort combination that a session runs under, optionally with a lower tool-output token
 * limit.
 * <p>
 * An empty model or effort is unbound: the session starts without selecting that value, so the harness applies its
 * default. When this profile restricts another profile, an empty value matches every value. A tool-output token limit
 * of {@code 0} leaves the harness's default limit; contract profiles always do.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param model the model, or empty for the harness's default
 * @param effort the reasoning effort, or empty for the harness's default
 * @param toolOutputTokenLimit the most tokens of each tool output that the session keeps, or {@code 0} for the
 *   harness's default
 */
public record TrialProfile(String model, String effort, int toolOutputTokenLimit)
{
	/**
	 * The profile that selects nothing, so the harness applies its defaults.
	 */
	public static final TrialProfile HARNESS_DEFAULT = new TrialProfile("", "");
	/**
	 * The label of an unbound dimension.
	 */
	private static final String DEFAULT_LABEL = "default";
	/**
	 * The text before the token count in the label of a tool-output token limit.
	 */
	static final String TOOL_OUTPUT_LABEL = "tool-output-";

	/**
	 * Creates a profile.
	 *
	 * @param model the model, or empty for the harness's default
	 * @param effort the reasoning effort, or empty for the harness's default
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
	 * @param model the model, or empty for the harness's default
	 * @param effort the reasoning effort, or empty for the harness's default
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if a dimension contains whitespace or the character {@code /}
	 */
	public TrialProfile(String model, String effort)
	{
		this(model, effort, 0);
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
	 * Indicates whether the profile selects both dimensions.
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
	 * @param pattern a profile whose empty dimensions match every value
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
	 * Returns the profile as {@code model/effort}, naming an unbound dimension {@code default}, followed by
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
			return DEFAULT_LABEL;
		return value;
	}

	@Override
	public String toString()
	{
		return label();
	}
}
