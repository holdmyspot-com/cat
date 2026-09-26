package io.github.cowwoc.cat.codex;

/**
 * Codex runtimes whose response-usage records have been checked for beta metering.
 */
public enum CodexMeteringVersion
{
	V_0_155_0("0.155.0"),
	V_0_157_0_ALPHA_11("0.157.0-alpha.11"),
	V_0_157_0("0.157.0");

	private final String runtimeValue;

	/**
	 * Binds a validated runtime spelling to its domain choice.
	 *
	 * @param runtimeValue exact Codex version text
	 */
	CodexMeteringVersion(String runtimeValue)
	{
		this.runtimeValue = runtimeValue;
	}

	/**
	 * Returns the exact Codex runtime version used in a usage report.
	 *
	 * @return validated version text
	 */
	public String runtimeValue()
	{
		return runtimeValue;
	}

	/**
	 * Converts external Codex version text at the ingestion boundary.
	 *
	 * @param runtimeValue exact version reported by Codex
	 * @return supported metering version
	 * @throws IllegalArgumentException if this version has not been validated
	 */
	public static CodexMeteringVersion fromRuntime(String runtimeValue)
	{
		for (CodexMeteringVersion version: values())
		{
			if (version.runtimeValue.equals(runtimeValue))
				return version;
		}
		throw new IllegalArgumentException("Codex version is unsupported for metering");
	}
}
