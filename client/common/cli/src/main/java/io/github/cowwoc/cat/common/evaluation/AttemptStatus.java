package io.github.cowwoc.cat.common.evaluation;

import java.io.IOException;
import java.util.Locale;

/**
 * Identifies the terminal or pending outcome of one retained testcase attempt.
 */
public enum AttemptStatus
{
	PASSED,
	FAILED,
	CANCELED,
	UNRUN,
	BLOCKED;

	/**
	 * Parses one persisted attempt-status value.
	 *
	 * @param value the persisted status spelling
	 * @return the constrained attempt status
	 * @throws IOException if the value is unsupported
	 */
	public static AttemptStatus parse(String value) throws IOException
	{
		try
		{
			return valueOf(value.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Invalid trial status: " + value, e);
		}
	}
}
