package com.holdmyspot.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.release.ReleaseBundles;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Runs {@code codex-artifact}, which finds the Codex CAT artifact of this CAT version and platform for Claude's
 * {@code codex-runner} skill and prints it with the artifact's {@code codex-runner} launcher. The Claude plugin holds
 * no Codex classes or files, so the artifact is the Codex CAT plugin installed for Codex when its version matches,
 * otherwise a copy in CAT's per-user bundle directory, which this command downloads from the Codex release archive of
 * this CAT version when it is missing.
 */
public final class CodexArtifact
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
			System.err.println("Usage: codex-artifact");
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
	 * Finds the Codex CAT artifact, downloading it when neither the installed plugin nor an earlier download is
	 * usable.
	 *
	 * @param environment the process environment: the user's home directory ({@code HOME}, or {@code USERPROFILE} on
	 *                    Windows); {@code CODEX_HOME}, the Codex home, which defaults to {@code .codex} in the home
	 *                    directory; {@code CAT_CODEX_MARKETPLACE_ROOT}, CAT's Codex marketplace, which defaults to
	 *                    {@code plugins/cat-marketplace} in the Codex home; and {@code CAT_RELEASE_BASE_URL}, which
	 *                    replaces the release's location
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
		String configuredMarketplace = environment.get("CAT_CODEX_MARKETPLACE_ROOT");
		if (configuredMarketplace != null && !configuredMarketplace.isBlank())
			marketplace = Path.of(configuredMarketplace);
		else
		{
			String configuredHome = environment.get("CODEX_HOME");
			Path codexHome;
			if (configuredHome != null && !configuredHome.isBlank())
				codexHome = Path.of(configuredHome);
			else
				codexHome = home.resolve(".codex");
			marketplace = codexHome.resolve("plugins/cat-marketplace");
		}
		ReleaseBundles bundles = new ReleaseBundles(ReleaseBundles.directory(home),
			ReleaseBundles.releaseUri(environment), Platform.current());
		return bundles.resolve(marketplace.resolve("plugins/cat"), "codex", Path.of(".codex-plugin/plugin.json"),
			"codex-runner").format();
	}

	/**
	 * Prevents instantiation.
	 */
	private CodexArtifact()
	{
	}
}
