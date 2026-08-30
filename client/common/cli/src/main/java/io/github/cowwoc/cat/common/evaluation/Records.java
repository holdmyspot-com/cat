package io.github.cowwoc.cat.common.evaluation;

/**
 * Owns the evaluator's durable-record schema revision.
 */
public final class Records
{
	private static final int SCHEMA_VERSION = 2;

	/**
	 * Prevents construction.
	 */
	private Records()
	{
	}

	/**
	 * Returns the current durable-record schema revision.
	 *
	 * @return the current schema revision
	 */
	public static int schemaVersion()
	{
		return SCHEMA_VERSION;
	}
}
