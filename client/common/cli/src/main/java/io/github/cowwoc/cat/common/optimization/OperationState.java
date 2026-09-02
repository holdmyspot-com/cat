package io.github.cowwoc.cat.common.optimization;

/** Durable lifecycle state for one local operation. */
public enum OperationState
{
	PENDING("pending"),
	RUNNING("running"),
	COMPLETED("completed"),
	FAILED("failed");

	private final String externalValue;

	/**
	 * Creates one operation state.
	 *
	 * @param externalValue stable serialized value
	 */
	OperationState(String externalValue)
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
	 * @return parsed state
	 */
	public static OperationState fromExternalValue(String value)
	{
		if (value == null)
			throw new NullPointerException("value must not be null");
		for (OperationState state : values())
			if (state.externalValue.equals(value))
				return state;
		throw new IllegalArgumentException("Unsupported operation state: " + value);
	}
}
