package io.github.cowwoc.cat.claude;

import io.github.cowwoc.cat.common.Platform;
import io.github.cowwoc.cat.common.PluginInfo;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Prints the identity of the Claude CAT harness.
 */
public final class ClaudePluginInfo
{
	/**
	 * The Claude harness identifier used in CAT records and runtime ownership.
	 */
	public static final String HARNESS_ID = "claude";

	/**
	 * Prints the Claude harness identity.
	 *
	 * @param args ignored command-line arguments
	 * @throws NullPointerException if {@code args} is null
	 */
	public static void main(String[] args)
	{
		requireThat(args, "args").isNotNull();
		System.out.println(info().toDisplayString());
	}

	/**
	 * Returns the Claude harness identity.
	 *
	 * @return the harness identity
	 */
	public static PluginInfo info()
	{
		return new PluginInfo(HARNESS_ID, PluginInfo.CAT_VERSION, Platform.current());
	}

	/**
	 * Prevents instantiation.
	 */
	private ClaudePluginInfo()
	{
	}
}
