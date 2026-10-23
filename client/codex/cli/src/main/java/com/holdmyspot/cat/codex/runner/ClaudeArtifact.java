package com.holdmyspot.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.release.ReleaseBundles;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Runs {@code claude-artifact}, which finds the Claude CAT artifact of this CAT version and platform for Codex's
 * {@code claude-runner} skill and prints it with the artifact's {@code claude-runner} launcher. The Codex plugin holds
 * no Claude classes or files, so the artifact is the Claude CAT plugin installed for Claude Code when its version
 * matches, otherwise a copy in CAT's per-user bundle directory, which this command downloads from the Claude release
 * archive of this CAT version when it is missing.
 */
public final class ClaudeArtifact
{
	/**
	 * Prints the artifact as {@code key=value} lines, or the reason that it cannot be found to standard error.
	 *
	 * @param args no arguments
	 */
	public static void main(String[] args)
	{
		if (args.length != 0)
		{
			System.err.println("Usage: claude-artifact");
			System.exit(2);
		}
		try
		{
			System.out.print(execute(Platform.current().environment(System.getenv())));
		}
		catch (IOException e)
		{
			System.err.println(e.getMessage());
			System.exit(1);
		}
	}

	/**
	 * Finds the Claude CAT artifact, downloading it when neither the installed plugin nor an earlier download is
	 * usable.
	 *
	 * @param environment the process environment: the user's home directory ({@code HOME}, or {@code USERPROFILE} on
	 *                    Windows); {@code CLAUDE_CONFIG_DIR}, the Claude Code configuration directory, which defaults
	 *                    to {@code .claude} in the home directory; {@code CAT_CLAUDE_MARKETPLACE_ROOT}, CAT's Claude
	 *                    marketplace, which defaults to {@code plugins/cat-marketplace} in the configuration
	 *                    directory; and {@code CAT_RELEASE_BASE_URL}, which replaces the release's location
	 * @return the {@code artifact}, {@code runner} and {@code source} lines
	 * @throws NullPointerException if {@code environment} is null
	 * @throws IOException          if the home directory is not set, or the artifact cannot be downloaded, verified or
	 *                              extracted
	 */
	public static String execute(Map<String, String> environment) throws IOException
	{
		requireThat(environment, "environment").isNotNull();
		Path home = Platform.current().homeDirectory(environment);
		Path marketplace;
		String configuredMarketplace = environment.get("CAT_CLAUDE_MARKETPLACE_ROOT");
		if (configuredMarketplace != null && !configuredMarketplace.isBlank())
			marketplace = Path.of(configuredMarketplace);
		else
		{
			String configuration = environment.get("CLAUDE_CONFIG_DIR");
			Path configurationDirectory;
			if (configuration != null && !configuration.isBlank())
				configurationDirectory = Path.of(configuration);
			else
				configurationDirectory = home.resolve(".claude");
			marketplace = configurationDirectory.resolve("plugins/cat-marketplace");
		}
		ReleaseBundles bundles = new ReleaseBundles(ReleaseBundles.directory(home),
			ReleaseBundles.releaseUri(environment), Platform.current());
		return bundles.resolve(marketplace.resolve("plugins/cat"), "claude", Path.of(".claude-plugin/plugin.json"),
			"claude-runner").format();
	}

	/**
	 * Prevents instantiation.
	 */
	private ClaudeArtifact()
	{
	}
}
