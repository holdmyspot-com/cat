package com.holdmyspot.cat.codex;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PluginInfo;
import com.holdmyspot.cat.common.runner.HarnessArtifact;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import java.io.IOException;
import java.nio.file.Path;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Prints the identity of the Codex CAT harness.
 */
public final class CodexPluginInfo
{
	/**
	 * The Codex harness identifier used in CAT records and runtime ownership.
	 */
	public static final String HARNESS_ID = "codex";

	/**
	 * Prints the Codex harness identity of the plugin that holds this code, with the full plugin version that the
	 * plugin's {@code bin/VERSION} states. If the plugin cannot be identified, prints the reason to the error stream and
	 * exits with status 1.
	 *
	 * @param args ignored command-line arguments
	 * @throws NullPointerException if {@code args} is null
	 */
	public static void main(String[] args)
	{
		requireThat(args, "args").isNotNull();
		try
		{
			System.out.println(info(PluginDirectory.of(CodexPluginInfo.class)).toDisplayString());
		}
		catch (IOException | IllegalStateException e)
		{
			System.err.println(e.getMessage());
			System.exit(1);
		}
	}

	/**
	 * Returns the Codex harness identity of a plugin.
	 *
	 * @param plugin the plugin directory
	 * @return the harness identity, whose version is the full plugin version that the plugin's {@code bin/VERSION}
	 *   states, such as {@code 1.0+b0123456789ab}
	 * @throws NullPointerException if {@code plugin} is null
	 * @throws IOException if the plugin's {@code bin/VERSION} cannot be read or does not state a full plugin version
	 */
	public static PluginInfo info(Path plugin) throws IOException
	{
		return new PluginInfo(HARNESS_ID, HarnessArtifact.version(plugin).toString(), Platform.current());
	}

	/**
	 * Prevents instantiation.
	 */
	private CodexPluginInfo()
	{
	}
}
