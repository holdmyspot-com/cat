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
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests the launchers and skills through which each plugin runs either harness: the {@code claude-runner} and
 * {@code codex-runner} skills and launchers. Each plugin finds the other harness's artifact with a launcher of its own
 * harness and runs the runner launcher in that artifact's {@code bin/}, so neither plugin holds the other harness's
 * classes.
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
	 * Supplies each runner skill with the launcher that selects its artifact.
	 *
	 * @return the skill's name and its artifact selector
	 */
	@DataProvider
	public Object[][] runnerSkills()
	{
		return new Object[][]{{"claude-runner", "claude-artifact"}, {"codex-runner", "codex-artifact"}};
	}

	/**
	 * Verifies that both harnesses hold both runner skills over one shared body, which names the plugin's runner
	 * launcher, the launcher that selects the artifact, and the use of the launcher inside a run, and holds no shell
	 * syntax, since Codex runs commands without a shell.
	 *
	 * @param skill    the skill's name
	 * @param selector the launcher that selects the skill's artifact
	 * @throws IOException if a skill cannot be read
	 */
	@Test(dataProvider = "runnerSkills")
	public void bothHarnessesHoldRunnerSkill(String skill, String selector) throws IOException
	{
		// Requirement: R-I9
		List<URL> bodies = resources("skills/" + skill + "/first-use.md");
		requireThat(bodies, "bodies").size().isEqualTo(1);
		requireThat(bodies.getFirst().toString(), "body").matches(".*common[-/]skills.*");
		List<String> manifests = new ArrayList<>();
		for (URL manifest : resources("skills/" + skill + "/SKILL.md"))
			manifests.add(manifest.toString().replaceAll(".*(claude|codex)[-/]skills.*", "$1"));
		requireThat(manifests, "manifests").containsExactly(List.of("claude", "codex"));
		String body = read(bodies.getFirst());
		requireThat(body, "first-use.md").contains("bin/" + skill).contains("bin/" + selector).
			contains("CAT_SESSION_REQUESTS").contains("--input").contains("--request-depth").
			doesNotContain("printf").doesNotContain(" | ").doesNotContain("$(").doesNotContain("```bash").
			doesNotContain("cannot start a harness");
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
