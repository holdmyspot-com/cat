package io.github.cowwoc.cat.common.evaluation;

import java.io.IOException;
import java.util.Locale;

/**
 * Identifies the kind of decision required for one testcase assertion.
 */
public enum EvaluationKind
{
	SEMANTIC,
	DETERMINISTIC;

	/**
	 * Parses an assertion's declared evaluation kind.
	 *
	 * @param value the external owner name
	 * @return the constrained evaluation kind
	 * @throws IOException if the value is unsupported
	 */
	public static EvaluationKind parse(String value) throws IOException
	{
		try
		{
			return valueOf(value.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Suite has an unsupported evaluation kind: " + value, e);
		}
	}
}
