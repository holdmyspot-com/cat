package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * One model and reasoning-effort combination that a session runs under (design &sect;6.6).
 * <p>
 * An empty dimension is unbound: the session starts without selecting it, so the harness applies its default. As a
 * restriction pattern, an empty dimension matches every value.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param model the model, or empty for the harness's default
 * @param effort the reasoning effort, or empty for the harness's default
 */
public record TrialProfile(String model, String effort)
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
	 * Creates a profile.
	 *
	 * @param model the model, or empty for the harness's default
	 * @param effort the reasoning effort, or empty for the harness's default
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if a dimension contains whitespace or the character {@code /}
	 */
	public TrialProfile
	{
		requireThat(model, "model").doesNotContainWhitespace().doesNotContain("/");
		requireThat(effort, "effort").doesNotContainWhitespace().doesNotContain("/");
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
	 * Indicates whether this profile matches a pattern.
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
	 * Returns the profile as {@code model/effort}, naming an unbound dimension {@code default}.
	 *
	 * @return the label
	 */
	public String label()
	{
		return dimension(model) + "/" + dimension(effort);
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
