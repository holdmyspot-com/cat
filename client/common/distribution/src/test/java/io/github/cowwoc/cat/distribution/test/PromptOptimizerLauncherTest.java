package io.github.cowwoc.cat.distribution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.distribution.bundle.JlinkImageBuilder;
import io.github.cowwoc.cat.distribution.secrets.DistributionMainSecrets;
import org.testng.annotations.Test;

import java.util.List;

/**
 * Tests that each harness's jlink image registers the {@code prompt-optimizer} launcher.
 */
public final class PromptOptimizerLauncherTest
{
	/**
	 * Creates the test.
	 */
	public PromptOptimizerLauncherTest()
	{
	}

	/**
	 * Verifies that the Claude image registers the launcher with Claude's main class.
	 *
	 * @throws ClassNotFoundException if the registered main class does not exist
	 * @throws NoSuchMethodException if the registered main class has no {@code main} method
	 */
	@Test
	public void claudeImageHasLauncher() throws ClassNotFoundException, NoSuchMethodException
	{
		// Requirement: R-I4
		verify(DistributionMainSecrets.access().claudeLaunchers(), "io.github.cowwoc.cat.claude.cli",
			"io.github.cowwoc.cat.claude.optimizer.ClaudePromptOptimizer");
	}

	/**
	 * Verifies that the Codex image registers the launcher with Codex's main class.
	 *
	 * @throws ClassNotFoundException if the registered main class does not exist
	 * @throws NoSuchMethodException if the registered main class has no {@code main} method
	 */
	@Test
	public void codexImageHasLauncher() throws ClassNotFoundException, NoSuchMethodException
	{
		// Requirement: R-I4
		verify(DistributionMainSecrets.access().codexLaunchers(), "io.github.cowwoc.cat.codex.cli",
			"io.github.cowwoc.cat.codex.optimizer.CodexPromptOptimizer");
	}

	/**
	 * Verifies that a launcher list holds exactly one {@code prompt-optimizer} launcher, and that its main class exists.
	 *
	 * @param launchers the image's launchers
	 * @param module the expected module
	 * @param mainClass the expected main class
	 * @throws ClassNotFoundException if the main class does not exist
	 * @throws NoSuchMethodException if the main class has no {@code main} method
	 */
	private static void verify(List<JlinkImageBuilder.Launcher> launchers, String module, String mainClass)
		throws ClassNotFoundException, NoSuchMethodException
	{
		List<JlinkImageBuilder.Launcher> matches = launchers.stream().
			filter(launcher -> launcher.name().equals("prompt-optimizer")).toList();
		requireThat(matches.size(), "matches.size()").isEqualTo(1);
		requireThat(matches.getFirst().module(), "module").isEqualTo(module);
		requireThat(matches.getFirst().mainClass(), "mainClass").isEqualTo(mainClass);
		Class.forName(mainClass).getDeclaredMethod("main", String[].class);
	}
}
