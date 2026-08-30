package io.github.cowwoc.cat.common.evaluation;

import java.io.IOException;
import java.util.Locale;

/**
 * Identifies whether a durable suite measures ordinary use or one bounded diagnostic.
 */
public enum SuitePurpose
{
	CERTIFICATION,
	DIAGNOSTIC;

	/**
	 * Parses one externally declared suite purpose.
	 *
	 * @param value the manifest purpose name
	 * @return the supported purpose
	 * @throws IOException if the purpose is unsupported
	 */
	public static SuitePurpose parse(String value) throws IOException
	{
		try
		{
			return valueOf(value.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Suite has an unsupported purpose: " + value, e);
		}
	}
}
