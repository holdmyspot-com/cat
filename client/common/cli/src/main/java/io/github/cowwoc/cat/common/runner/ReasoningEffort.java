package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Identifies one reasoning-effort value that a harness adapter may apply to a model invocation.
 */
public enum ReasoningEffort
{
	MINIMAL("minimal"),
	LOW("low"),
	MEDIUM("medium"),
	HIGH("high"),
	XHIGH("xhigh"),
	MAX("max");

	private final String externalValue;

	/**
	 * Associates one domain value with the exact spelling supplied to a harness.
	 *
	 * @param externalValue the non-blank harness spelling
	 */
	ReasoningEffort(String externalValue)
	{
		this.externalValue = externalValue;
	}

	/**
	 * Converts one untyped harness value into the supported reasoning-effort domain.
	 *
	 * @param externalValue the exact non-blank harness spelling
	 * @return the matching supported reasoning effort
	 * @throws IllegalArgumentException if {@code externalValue} is blank or unsupported
	 */
	public static ReasoningEffort fromExternalValue(String externalValue)
	{
		requireThat(externalValue, "externalValue").isNotBlank();
		return switch (externalValue)
		{
			case "minimal" -> MINIMAL;
			case "low" -> LOW;
			case "medium" -> MEDIUM;
			case "high" -> HIGH;
			case "xhigh" -> XHIGH;
			case "max" -> MAX;
			default -> throw new IllegalArgumentException("Unsupported reasoning effort: " + externalValue);
		};
	}

	/**
	 * Returns the exact value supplied to a harness adapter.
	 *
	 * @return the external reasoning-effort spelling
	 */
	public String externalValue()
	{
		return externalValue;
	}
}
