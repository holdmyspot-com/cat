package com.holdmyspot.cat.distribution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.distribution.bundle.JlinkImageBuilder;
import com.holdmyspot.cat.distribution.secrets.DistributionMainAccess;
import com.holdmyspot.cat.distribution.secrets.DistributionMainSecrets;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests the launchers and skills through which each plugin runs the other harness's CAT artifact: Claude's
 * {@code codex-runner} skill and Codex's {@code claude-runner} skill. Each plugin finds the other artifact with a
 * launcher of its own harness and runs the runner launcher in the other artifact's {@code bin/}, so neither plugin
 * holds the other harness's classes.
 */
public final class RunnerSkillLaunchersTest
{
	private static final String CLAUDE_MODULE = "com.holdmyspot.cat.claude.cli";
	private static final String CODEX_MODULE = "com.holdmyspot.cat.codex.cli";
	private static final String COMMON_MODULE = "com.holdmyspot.cat.common.cli";

	/** Creates a test instance. */
	public RunnerSkillLaunchersTest()
	{
	}

	/**
	 * Verifies that the Claude plugin's {@code bin/} holds {@code codex-artifact}, which finds the Codex artifact, and
	 * {@code claude-runner}, which the Codex plugin's skill runs.
	 */
	@Test
	public void claudePluginHoldsRunnerLaunchers()
	{
		// Requirement: R-I9
		DistributionMainAccess access = DistributionMainSecrets.getDistributionMainAccess();
		requireThat(access.claudeLaunchers(), "claudeLaunchers").
			contains(new JlinkImageBuilder.Launcher("codex-artifact", CLAUDE_MODULE,
				"com.holdmyspot.cat.claude.runner.CodexArtifact")).
			contains(new JlinkImageBuilder.Launcher("claude-runner", CLAUDE_MODULE,
				"com.holdmyspot.cat.claude.runner.ClaudeRunnerCli")).
			contains(new JlinkImageBuilder.Launcher("request-session", "com.holdmyspot.cat.common.cli",
				"com.holdmyspot.cat.common.runner.SessionRequestCli"));
		requireThat(access.claudeAgentLaunchers(), "claudeAgentLaunchers").contains("codex-artifact").
			contains("claude-runner").contains("request-session");
	}

	/**
	 * Verifies that the Codex plugin's {@code bin/} holds {@code claude-artifact}, which finds the Claude artifact, and
	 * {@code codex-runner}, which the Claude plugin's skill runs.
	 */
	@Test
	public void codexPluginHoldsRunnerLaunchers()
	{
		// Requirement: R-I9
		DistributionMainAccess access = DistributionMainSecrets.getDistributionMainAccess();
		requireThat(access.codexLaunchers(), "codexLaunchers").
			contains(new JlinkImageBuilder.Launcher("claude-artifact", CODEX_MODULE,
				"com.holdmyspot.cat.codex.runner.ClaudeArtifact")).
			contains(new JlinkImageBuilder.Launcher("codex-runner", CODEX_MODULE,
				"com.holdmyspot.cat.codex.runner.CodexRunnerCli")).
			contains(new JlinkImageBuilder.Launcher("request-session", "com.holdmyspot.cat.common.cli",
				"com.holdmyspot.cat.common.runner.SessionRequestCli"));
		requireThat(access.codexAgentLaunchers(), "codexAgentLaunchers").contains("claude-artifact").
			contains("codex-runner").contains("request-session");
	}

	/**
	 * Verifies that each plugin's {@code bin/} holds both runner launchers, so that a session of either harness, and a
	 * session that it requests, can run either harness through the same command.
	 */
	@Test
	public void bothPluginsHoldBothRunners()
	{
		// Requirement: R-I9
		DistributionMainAccess access = DistributionMainSecrets.getDistributionMainAccess();
		requireThat(access.claudeLaunchers(), "claudeLaunchers").
			contains(new JlinkImageBuilder.Launcher("codex-runner", CLAUDE_MODULE,
				"com.holdmyspot.cat.claude.runner.CodexRunnerRelay"));
		requireThat(access.codexLaunchers(), "codexLaunchers").
			contains(new JlinkImageBuilder.Launcher("claude-runner", CODEX_MODULE,
				"com.holdmyspot.cat.codex.runner.ClaudeRunnerRelay"));
		requireThat(access.claudeAgentLaunchers(), "claudeAgentLaunchers").contains("claude-runner").
			contains("codex-runner");
		requireThat(access.codexAgentLaunchers(), "codexAgentLaunchers").contains("claude-runner").
			contains("codex-runner");
	}

	/**
	 * Verifies that each image launches only its own harness's module or the common module.
	 */
	@Test
	public void imagesLaunchOnlyTheirOwnHarness()
	{
		DistributionMainAccess access = DistributionMainSecrets.getDistributionMainAccess();
		for (JlinkImageBuilder.Launcher launcher : access.claudeLaunchers())
			requireThat(List.of(CLAUDE_MODULE, COMMON_MODULE), "modules").contains(launcher.module(), launcher.name());
		for (JlinkImageBuilder.Launcher launcher : access.codexLaunchers())
			requireThat(List.of(CODEX_MODULE, COMMON_MODULE), "modules").contains(launcher.module(), launcher.name());
	}

	/**
	 * Verifies that only the Claude skills hold the {@code codex-runner} skill and that it runs the plugin's
	 * {@code codex-artifact} and the artifact's {@code codex-runner}.
	 *
	 * @throws IOException if a skill cannot be read
	 */
	@Test
	public void claudeSkillsHoldCodexRunner() throws IOException
	{
		List<URL> skills = resources("skills/codex-runner/first-use.md");
		requireThat(skills, "skills").size().isEqualTo(1);
		requireThat(skills.getFirst().toString(), "skill").matches(".*claude[-/]skills.*");
		requireThat(resources("skills/codex-runner/SKILL.md"), "manifests").size().isEqualTo(1);
		requireThat(read(skills.getFirst()), "first-use.md").contains("bin/codex-artifact").
			contains("bin/codex-runner");
	}

	/**
	 * Verifies that only the Codex skills hold the {@code claude-runner} skill and that it runs the plugin's
	 * {@code claude-artifact} and the artifact's {@code claude-runner}.
	 *
	 * @throws IOException if a skill cannot be read
	 */
	@Test
	public void codexSkillsHoldClaudeRunner() throws IOException
	{
		List<URL> skills = resources("skills/claude-runner/first-use.md");
		requireThat(skills, "skills").size().isEqualTo(1);
		requireThat(skills.getFirst().toString(), "skill").matches(".*codex[-/]skills.*");
		requireThat(resources("skills/claude-runner/SKILL.md"), "manifests").size().isEqualTo(1);
		requireThat(read(skills.getFirst()), "first-use.md").contains("bin/claude-artifact").
			contains("bin/claude-runner");
	}

	/**
	 * Returns every class path location of a resource.
	 *
	 * @param name the resource's name
	 * @return the locations
	 * @throws IOException if the class path cannot be read
	 */
	private static List<URL> resources(String name) throws IOException
	{
		return new ArrayList<>(Collections.list(RunnerSkillLaunchersTest.class.getClassLoader().getResources(name)));
	}

	/**
	 * Reads a resource.
	 *
	 * @param resource the resource's location
	 * @return its content
	 * @throws IOException if it cannot be read
	 */
	private static String read(URL resource) throws IOException
	{
		try (InputStream in = resource.openStream())
		{
			return new String(in.readAllBytes(), UTF_8);
		}
	}
}
