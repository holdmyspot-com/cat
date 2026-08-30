package io.github.cowwoc.cat.common.evaluation;

import java.io.IOException;
import java.util.Locale;

/**
 * Identifies the bounded question answered by a generated diagnostic suite.
 */
public enum DiagnosticKind
{
	DIRECT_APPLICATION,
	ROUTING_ONLY;

	/**
	 * Parses one external diagnostic kind.
	 *
	 * @param value the declared kind
	 * @return the supported kind
	 * @throws IOException if the value is unsupported
	 */
	public static DiagnosticKind parse(String value) throws IOException
	{
		try
		{
			return valueOf(value.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Suite has an unsupported diagnostic_kind: " + value, e);
		}
	}
}
