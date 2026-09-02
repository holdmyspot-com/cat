package io.github.cowwoc.cat.common.optimization;

/** Whether locally retained evidence remains available for reproduction. */
public enum ReproductionState
{
	AVAILABLE("available"), UNAVAILABLE("unavailable");

	private final String externalValue;

	/**
	 * Creates one state with its serialized value.
	 *
	 * @param externalValue serialized state
	 */
	ReproductionState(String externalValue)
	{
		this.externalValue = externalValue;
	}

	/**
	 * Returns the exact lowercase external value.
	 *
	 * @return serialized state
	 */
	public String externalValue()
	{
		return externalValue;
	}

	/**
	 * Parses one exact lowercase external value.
	 *
	 * @param value serialized state
	 * @return parsed state
	 */
	public static ReproductionState fromExternalValue(String value)
	{
		if (value == null)
			throw new NullPointerException("value must not be null");
		for (ReproductionState state : values())
			if (state.externalValue.equals(value))
				return state;
		throw new IllegalArgumentException("Unsupported reproduction state: " + value);
	}
}
