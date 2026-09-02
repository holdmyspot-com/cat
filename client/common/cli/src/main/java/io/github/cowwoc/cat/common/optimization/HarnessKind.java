package io.github.cowwoc.cat.common.optimization;

/** Identifies a supported coding-agent harness. */
public enum HarnessKind
{
	CODEX("codex"),
	CLAUDE("claude");

	private final String externalValue;

	/**
	 * Creates one harness value.
	 *
	 * @param externalValue stable serialized value
	 */
	HarnessKind(String externalValue)
	{
		this.externalValue = externalValue;
	}

	/**
	 * Returns the stable serialized value.
	 *
	 * @return stable serialized value
	 */
	public String externalValue()
	{
		return externalValue;
	}

	/**
	 * Parses one exact lowercase serialized value.
	 *
	 * @param value serialized value
	 * @return parsed harness
	 */
	public static HarnessKind fromExternalValue(String value)
	{
		if (value == null)
			throw new NullPointerException("value must not be null");
		for (HarnessKind kind : values())
			if (kind.externalValue.equals(value))
				return kind;
		throw new IllegalArgumentException("Unsupported harness: " + value);
	}
}
