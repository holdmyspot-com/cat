package io.github.cowwoc.cat.common.optimization;

/** The only formal outcomes an evaluator may return for one applicable assertion. */
public enum EvaluatorVerdict
{
	PASS("pass"),
	FAIL("fail");

	private final String externalValue;

	/**
	 * Creates one evaluator verdict.
	 *
	 * @param externalValue stable serialized value
	 */
	EvaluatorVerdict(String externalValue)
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
	 * @return parsed verdict
	 */
	public static EvaluatorVerdict fromExternalValue(String value)
	{
		if (value == null)
			throw new NullPointerException("value must not be null");
		for (EvaluatorVerdict verdict : values())
			if (verdict.externalValue.equals(value))
				return verdict;
		throw new IllegalArgumentException("Unsupported evaluator verdict: " + value);
	}
}
