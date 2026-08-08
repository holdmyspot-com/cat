package io.github.cowwoc.cat.codex;

import io.github.cowwoc.cat.common.Platform;
import io.github.cowwoc.cat.common.PluginInfo;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Prints the identity of the Codex CAT harness.
 */
public final class CodexPluginInfo
{
	/**
	 * Prints the Codex harness identity.
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
	 * Returns the Codex harness identity.
	 *
	 * @return the harness identity
	 */
	public static PluginInfo info()
	{
		return new PluginInfo("codex", PluginInfo.CAT_VERSION, Platform.current());
	}

	/**
	 * Prevents instantiation.
	 */
	private CodexPluginInfo()
	{
	}
}
