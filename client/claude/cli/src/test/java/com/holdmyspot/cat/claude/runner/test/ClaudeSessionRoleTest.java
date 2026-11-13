package com.holdmyspot.cat.claude.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.runner.ClaudeConnectionSettings;
import com.holdmyspot.cat.claude.runner.ClaudeRunner;
import com.holdmyspot.cat.claude.runner.ClaudeSessionRole;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessRunnerScope;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

/**
 * Verifies what a session's role changes in how Claude Code is launched: the tools its model is offered and the
 * lifetime of its prompt cache.
 */
public final class ClaudeSessionRoleTest
{
	/**
	 * Creates the test.
	 */
	public ClaudeSessionRoleTest()
	{
	}

	/**
	 * Verifies that a judge's model is offered only the tools that read and search files, through {@code --tools},
	 * which removes the other tools' definitions from its requests, and that a trial and a session with no role keep
	 * every tool.
	 */
	@Test
	public void judgeIsOfferedOnlyReadTools()
	{
		List<String> judge = launch(ClaudeSessionRole.JUDGE.tools());
		int option = judge.indexOf("--tools");

		requireThat(option, "option").isGreaterThanOrEqualTo(0);
		requireThat(judge.get(option + 1), "tools").isEqualTo("Read,Grep,Glob");
		requireThat(judge, "judge").doesNotContain("--allowedTools");
		requireThat(launch(ClaudeSessionRole.TRIAL.tools()), "trial").doesNotContain("--tools");
		requireThat(launch(List.of()), "noRole").doesNotContain("--tools");
	}

	/**
	 * Verifies that the settings of a judge keep the sandbox section and carry the five-minute prompt cache, as the
	 * settings of a trial do, without an environment variable for it, and that the settings of a session with no role
	 * keep the default lifetime.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 */
	@Test
	public void rolesSetFiveMinuteCache() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-session-role-"));
			for (ClaudeSessionRole role : ClaudeSessionRole.values())
			{
				JsonNode settings = write(scope, root.resolve(role.name()), role.promptCacheTtl());
				requireThat(settings.path("promptCacheTtl").asString(), role.name()).isEqualTo("5m");
				requireThat(settings.path("sandbox").path("enabled").asBoolean(false), role.name() + "Sandbox").isTrue();
				for (JsonNode variable : settings.path("env"))
					requireThat(variable.asString(), "variable").doesNotContain("5m");
			}
			JsonNode author = write(scope, root.resolve("author"), "");
			requireThat(author.has("promptCacheTtl"), "authorHasTtl").isFalse();
			requireThat(author.path("sandbox").path("enabled").asBoolean(false), "authorSandbox").isTrue();
		}
	}

	/**
	 * Verifies that {@code --session-role} names exactly the roles that CAT launches with settings of their own.
	 */
	@Test
	public void roleOptionValuesRoundTrip()
	{
		for (ClaudeSessionRole role : ClaudeSessionRole.values())
			requireThat(ClaudeSessionRole.parse(role.optionValue()), role.name()).isEqualTo(role);
	}

	/**
	 * Builds the command that starts a session whose model is offered {@code tools}.
	 *
	 * @param tools the tools, or none for every tool
	 * @return the command
	 */
	private static List<String> launch(List<String> tools)
	{
		return ClaudeRunner.command("claude", "sonnet", "low", "", List.of(), Path.of("hook-results"), List.of(), false, 0,
			tools);
	}

	/**
	 * Writes the isolated settings of a session and reads them back.
	 *
	 * @param scope          the scope
	 * @param root           the directory of the fixture
	 * @param promptCacheTtl the prompt cache lifetime, or an empty string for the default
	 * @return the settings
	 * @throws IOException if settings I/O fails
	 */
	private static JsonNode write(HarnessRunnerScope scope, Path root, String promptCacheTtl) throws IOException
	{
		Path destination = root.resolve("isolated/settings.json");
		ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), destination,
			root.resolve("tmp"), root.resolve("home"), List.of(), root, List.of(), "", null, null, root, null,
			ExtraPermissions.NONE, "api.anthropic.com", promptCacheTtl);
		return scope.json().readTree(Files.readString(destination));
	}
}
