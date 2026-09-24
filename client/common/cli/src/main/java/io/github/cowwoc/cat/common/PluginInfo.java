package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Identifies a CAT plugin distribution for one supported AI coding harness and native platform.
 *
 * @param harness the supported harness identifier, such as {@code claude} or {@code codex}
 * @param version the CAT release version packaged in the distribution
 * @param platform the native platform targeted by the distribution
 */
public record PluginInfo(String harness, String version, Platform platform)
{
	/**
	 * Current CAT release version.
	 */
	public static final String CAT_VERSION = "1.1";

	/**
	 * Creates a CAT plugin distribution identity.
	 *
	 * @param harness the supported harness identifier
	 * @param version the CAT release version packaged in the distribution
	 * @param platform the native platform targeted by the distribution
	 * @throws NullPointerException if {@code harness}, {@code version}, or {@code platform} are null
	 * @throws IllegalArgumentException if {@code harness} or {@code version} are blank
	 */
	public PluginInfo
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(version, "version").isNotBlank();
		requireThat(platform, "platform").isNotNull();
	}

	/**
	 * Formats the harness identity for command-line output.
	 *
	 * @return the formatted identity
	 */
	public String toDisplayString()
	{
		return "harness=" + harness + "\nversion=" + version + "\nplatform=" + platform.id();
	}
}
