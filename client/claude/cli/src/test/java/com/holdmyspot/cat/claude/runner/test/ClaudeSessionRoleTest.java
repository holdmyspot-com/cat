package com.holdmyspot.cat.claude.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.runner.ClaudeConnectionSettings;
import com.holdmyspot.cat.claude.runner.ClaudeRunner;
import com.holdmyspot.cat.claude.runner.ClaudeSessionRole;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.HarnessRunnerScope;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
	 * Verifies that a judge's settings let its commands write none of CAT's write roots, that a trial's settings are
	 * what they are for a session with no role, and that the judge keeps every read and write denial that a session
	 * has, so that the section that Claude Code renders for a judge holds no protected-path entry for those roots.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 */
	@Test
	public void judgeSandboxListsNoWriteRoot() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-judge-sandbox-")).toRealPath();
			Path hostProject = Files.createDirectories(root.resolve("project"));
			HarnessRunManager manager = new HarnessRunManager(scope.fileLocks(), hostProject.resolve("runs"),
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("claude");
			manager.snapshotProject(run);
			Files.createDirectories(run.directory().resolve("home/.claude"));
			Path deniedFile = Files.writeString(root.resolve("campaign.json"), "{}");
			Path deniedDirectory = Files.createDirectory(root.resolve("trials"));
			Path evidence = Files.createDirectory(root.resolve("evidence"));
			SessionRequestAccess requestAccess = SessionRequestAccess.allowing(run, root.resolve("artifact"), 0,
				Platform.LINUX_X64, SessionRequestAccess.Shell.POSIX).orElseThrow();
			List<Path> deniedReads = ClaudeRunner.sessionDeniedReads(manager, run, List.of(evidence),
				List.of(deniedFile, deniedDirectory, hostProject), Map.of(), requestAccess);
			List<Path> dependencyCaches = List.of(root.resolve("host-home/.npm"), root.resolve("host-home/.m2"));
			Path project = run.directory().resolve("project");
			Path home = run.directory().resolve("home");
			Path claudeTemporaryDirectory = run.directory().resolve("claude");

			Path previous = root.resolve("isolated/previous.json");
			ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), previous,
				claudeTemporaryDirectory, home, dependencyCaches, run.directory(), deniedReads, "", requestAccess,
				project, run.directory(), null, ExtraPermissions.NONE, "api.anthropic.com",
				ClaudeSessionRole.JUDGE.promptCacheTtl());
			for (ClaudeSessionRole role : ClaudeSessionRole.values())
			{
				Path destination = root.resolve("isolated/" + role.name() + ".json");
				ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), destination,
					claudeTemporaryDirectory, home, dependencyCaches, run.directory(), deniedReads, "", requestAccess,
					project, run.directory(), null, ExtraPermissions.NONE, "api.anthropic.com", role.promptCacheTtl(),
					role.writes());
			}
			JsonNode before = scope.json().readTree(Files.readString(previous));
			JsonNode trial = scope.json().readTree(Files.readString(root.resolve("isolated/TRIAL.json")));
			JsonNode judge = scope.json().readTree(Files.readString(root.resolve("isolated/JUDGE.json")));

			// The fixture has the roots that CAT adds to a session's sandbox, and a trial lists them
			List<String> roots = new ArrayList<>();
			for (JsonNode entry : before.path("sandbox").path("filesystem").path("allowWrite"))
				roots.add(entry.asString());
			requireThat(roots, "roots").contains(home.toString()).contains(dependencyCaches.get(0).toString()).
				contains(dependencyCaches.get(1).toString()).contains(requestAccess.requests().toString()).
				contains(project.resolve(".git/worktrees").toString());
			requireThat(trial, "trial").isEqualTo(before);

			JsonNode judgeFilesystem = judge.path("sandbox").path("filesystem");
			requireThat(judgeFilesystem.path("allowWrite").size(), "judgeAllowWrite").isEqualTo(0);
			JsonNode previousFilesystem = before.path("sandbox").path("filesystem");
			requireThat(previousFilesystem.path("denyRead").size(), "denyReadCount").isGreaterThan(0);
			for (String name : List.of("denyRead", "denyWrite", "allowRead"))
				requireThat(judgeFilesystem.path(name), name).isEqualTo(previousFilesystem.path(name));
			requireThat(judge.path("sandbox").path("network"), "network").isEqualTo(before.path("sandbox").path("network"));
			requireThat(judge.path("permissions"), "permissions").isEqualTo(before.path("permissions"));
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
