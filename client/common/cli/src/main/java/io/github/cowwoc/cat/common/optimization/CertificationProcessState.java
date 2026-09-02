package io.github.cowwoc.cat.common.optimization;

/** Lifecycle state of one local certification process. */
public enum CertificationProcessState
{
	NOT_STARTED("not_started"),
	ACTIVE("active"),
	CERTIFIED("certified"),
	NOT_CERTIFIED("not_certified");

	private final String externalValue;

	/**
	 * Creates one process state.
	 *
	 * @param externalValue stable serialized value
	 */
	CertificationProcessState(String externalValue)
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
	 * @return parsed process state
	 */
	public static CertificationProcessState fromExternalValue(String value)
	{
		if (value == null)
			throw new NullPointerException("value must not be null");
		for (CertificationProcessState state : values())
			if (state.externalValue.equals(value))
				return state;
		throw new IllegalArgumentException("Unsupported certification process state: " + value);
	}
}
