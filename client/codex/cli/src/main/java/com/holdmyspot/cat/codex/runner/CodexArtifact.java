package com.holdmyspot.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.release.RunnerArtifact;
import com.holdmyspot.cat.common.runner.HarnessArtifact;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Runs {@code codex-artifact}, which reports the Codex CAT artifact of this CAT version and platform for Codex's
 * {@code codex-runner} skill: the Codex CAT plugin that holds this launcher, with its {@code codex-runner}
 * launcher. The artifact is the plugin itself, reported with the source {@code installed}, so nothing is
 * downloaded. The plugin must pass the check of {@code codex-runner --verify-artifact} and hold an executable
 * {@code codex-runner}.
 */
public final class CodexArtifact
{
	/**
	 * The Codex plugin manifest, relative to the artifact.
	 */
	private static final Path MANIFEST = Path.of(".codex-plugin/plugin.json");

	/**
	 * Prints the artifact as {@code key=value} lines, or the reason that it cannot be used to standard error.
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
			System.out.print(execute(Path.of(System.getProperty("java.home")).toAbsolutePath().getParent()));
		}
		catch (IOException e)
		{
			System.err.println(e.getMessage());
			System.exit(1);
		}
	}

	/**
	 * Verifies the plugin that holds this launcher and reports it as the Codex CAT artifact.
	 *
	 * @param plugin the Codex CAT plugin's directory
	 * @return the {@code artifact}, {@code runner} and {@code source} lines, whose source is {@code installed}
	 * @throws NullPointerException if {@code plugin} is null
	 * @throws IOException          if the plugin is incomplete, of another CAT version, or lacks an executable
	 *                              {@code codex-runner} launcher
	 */
	public static String execute(Path plugin) throws IOException
	{
		requireThat(plugin, "plugin").isNotNull();
		Platform platform = Platform.current();
		Path runner = HarnessArtifact.runnerLauncher(plugin, MANIFEST, "codex-runner", platform);
		return new RunnerArtifact(runner.getParent().getParent(), runner, RunnerArtifact.Source.INSTALLED).format();
	}

	/**
	 * Prevents instantiation.
	 */
	private CodexArtifact()
	{
	}
}
