package com.holdmyspot.cat.claude.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.runner.ClaudeConnectionSettings;
import com.holdmyspot.cat.claude.runner.ClaudeRunner;
import com.holdmyspot.cat.claude.runner.ClaudeSessionRole;
import com.holdmyspot.cat.claude.runner.ClaudeSessionSandbox;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.HarnessRunnerScope;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;

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
 * Verifies what a session's role changes in how Claude Code is launched: whether it runs inside a sandbox, the tools
 * its model is offered and the lifetime of its prompt cache.
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
	 * Verifies that the settings of a trial and of a judge carry the five-minute prompt cache without an environment
	 * variable for it, and that the settings of a session with no role keep the default lifetime and the sandbox.
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
				JsonNode settings = write(scope, root.resolve(role.name()), role);
				requireThat(settings.path("promptCacheTtl").asString(), role.name()).isEqualTo("5m");
				for (JsonNode variable : settings.path("env"))
					requireThat(variable.asString(), "variable").doesNotContain("5m");
			}
			JsonNode author = write(scope, root.resolve("author"), "");
			requireThat(author.has("promptCacheTtl"), "authorHasTtl").isFalse();
			requireThat(author.path("sandbox").path("enabled").asBoolean(false), "authorSandbox").isTrue();
		}
	}

	/**
	 * Verifies that a judge's settings have no sandbox block and none of the setup that only a sandbox needs, that they
	 * deny the judge's file tools every path that a session's settings deny, with the same rules as a trial's, and deny
	 * every MCP tool, and that a trial's settings are what they are for a session with no role.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 */
	@Test
	public void judgeRunsWithoutSandbox() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(scope, cleanup);
			Path noRole = fixture.writeWithoutRole(scope, "no-role");
			Path trialFile = fixture.write(scope, "trial", ClaudeSessionRole.TRIAL);
			Path judgeFile = fixture.write(scope, "judge", ClaudeSessionRole.JUDGE);
			JsonNode before = scope.json().readTree(Files.readString(noRole));
			JsonNode trial = scope.json().readTree(Files.readString(trialFile));
			JsonNode judge = scope.json().readTree(Files.readString(judgeFile));

			requireThat(Files.readString(trialFile), "trial").isEqualTo(Files.readString(noRole));
			requireThat(trial.path("sandbox").path("enabled").asBoolean(false), "trialSandbox").isTrue();
			List<String> previousDenials = strings(before.path("permissions").path("deny"));
			requireThat(previousDenials, "previousDenials").contains(fixture.rule(fixture.deniedFile, false)).
				contains(fixture.rule(fixture.deniedDirectory, true));

			requireThat(judge.has("sandbox"), "judgeHasSandbox").isFalse();
			List<String> expectedDenials = new ArrayList<>(previousDenials);
			expectedDenials.add("mcp__*");
			requireThat(strings(judge.path("permissions").path("deny")), "judgeDenials").isEqualTo(expectedDenials);
			// The file tools follow the project's links to targets outside it into the run's link-targets directory; the
			// judge requests no session, so nothing else is allowed
			requireThat(strings(judge.path("permissions").path("allow")), "judgeAllows").
				isEqualTo(List.of(fixture.linkTargetsRule()));
			for (String rule : strings(judge.path("permissions").path("deny")))
				requireThat(rule.startsWith("Read(") || rule.equals("mcp__*"), "denyRule").isTrue();
			requireThat(strings(trial.path("permissions").path("allow")), "trialAllows").contains("Bash").
				contains("Skill").contains("Edit(./**)");
			requireThat(strings(trial.path("permissions").path("deny")), "trialDenials").
				isEqualTo(previousDenials);
		}
	}

	/**
	 * Verifies that settings for a session that runs without a sandbox are refused, and none is written, when its model
	 * is offered every tool or any tool beyond Read, Grep and Glob, naming each such tool, and that every role that
	 * runs without a sandbox is offered only Read, Grep and Glob.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 */
	@Test
	public void unsandboxedSessionIsOfferedReadTools() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(scope, cleanup);
			for (ClaudeSessionRole role : ClaudeSessionRole.values())
			{
				if (!role.sandboxed())
					requireThat(role.tools(), role.name()).isEqualTo(List.of("Read", "Grep", "Glob"));
			}
			for (List<String> tools : List.of(List.<String>of(), List.of("Read", "Bash"), List.of("Edit", "Grep", "Write"),
				List.of("Read", "Skill", "WebFetch")))
			{
				String name = "refused-" + String.join("-", tools);
				SessionRefusedException refusal = expectRefusal(() -> fixture.write(scope, name, "5m", false, tools));
				if (tools.isEmpty())
					requireThat(refusal.getMessage(), "message").contains("offered every tool");
				for (String tool : tools)
				{
					String message = refusal.getMessage();
					String offered = message.substring(message.indexOf("this session's model is offered"));
					requireThat(offered.contains(tool), "names" + tool).isEqualTo(!ClaudeSessionSandbox.READ_ONLY_TOOLS.
						contains(tool));
				}
				requireThat(Files.exists(fixture.root.resolve(name)), "settingsWritten").isFalse();
			}
			requireThat(Files.exists(fixture.write(scope, "allowed", "5m", false, List.of("Read", "Glob"))), "allowed").
				isTrue();
		}
	}

	/**
	 * Verifies that a denied path whose name holds the characters that a gitignore pattern treats as syntax becomes a
	 * rule that names exactly that path, for a file and for a directory, in the settings of a trial and of a judge, so
	 * that no denial silently misses its path.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 */
	@Test
	public void deniedPathWithPatternSyntaxIsEscaped() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			// Windows file names cannot hold * or ?, and POSIX file names can hold a backslash
			String syntax = "[x]!";
			String escapedSyntax = "\\[x\\]\\!";
			if (!Platform.current().isWindows())
			{
				syntax += "*?\\";
				escapedSyntax += "\\*\\?\\\\";
			}
			Fixture fixture = new Fixture(scope, cleanup);
			Path file = Files.writeString(fixture.root.resolve("held-" + syntax + ".json"), "{}");
			Path directory = Files.createDirectory(fixture.root.resolve("evidence-" + syntax));
			fixture.deniedReads.add(file);
			fixture.deniedReads.add(directory);
			String ruleBase = ClaudeConnectionSettings.rulePath(fixture.root);

			for (ClaudeSessionRole role : ClaudeSessionRole.values())
			{
				JsonNode settings = scope.json().readTree(Files.readString(fixture.write(scope, role.name(), role)));
				List<String> denials = strings(settings.path("permissions").path("deny"));
				requireThat(denials, role.name()).contains("Read(/" + ruleBase + "/held-" + escapedSyntax + ".json)").
					contains("Read(/" + ruleBase + "/evidence-" + escapedSyntax + "/**)");
				for (String denial : denials)
				{
					if (denial.contains("held-") || denial.contains("evidence-"))
						requireThat(denial, "denial").doesNotContain("held-" + syntax).doesNotContain("evidence-" + syntax);
				}
			}
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
	 * Runs an operation that must be refused.
	 *
	 * @param operation the operation
	 * @return the refusal
	 * @throws IOException   if the operation fails for any other reason
	 * @throws AssertionError if the operation is not refused
	 */
	private static SessionRefusedException expectRefusal(IoOperation operation) throws IOException
	{
		try
		{
			operation.run();
		}
		catch (SessionRefusedException e)
		{
			return e;
		}
		throw new AssertionError("The operation was not refused");
	}

	/**
	 * An operation that fails with an I/O error.
	 */
	@FunctionalInterface
	private interface IoOperation
	{
		/**
		 * Runs the operation.
		 *
		 * @throws IOException if the operation fails
		 */
		void run() throws IOException;
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
	 * Writes the isolated settings of a session whose model is offered every tool, inside a sandbox, and reads them
	 * back.
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

	/**
	 * Writes the isolated settings of a session with a role and reads them back.
	 *
	 * @param scope the scope
	 * @param root  the directory of the fixture
	 * @param role  the session's role
	 * @return the settings
	 * @throws IOException if settings I/O fails
	 */
	private static JsonNode write(HarnessRunnerScope scope, Path root, ClaudeSessionRole role) throws IOException
	{
		Path destination = root.resolve("isolated/settings.json");
		ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), destination,
			root.resolve("tmp"), root.resolve("home"), List.of(), root, List.of(), "", null, null, root, null,
			ExtraPermissions.NONE, "api.anthropic.com", role.promptCacheTtl(), role.sandboxed(), role.tools());
		return scope.json().readTree(Files.readString(destination));
	}

	/**
	 * Returns the strings of a JSON array.
	 *
	 * @param array the array
	 * @return the strings, in order
	 */
	private static List<String> strings(JsonNode array)
	{
		List<String> result = new ArrayList<>();
		for (JsonNode entry : array)
			result.add(entry.asString());
		return result;
	}

	/**
	 * A run of a session, with the files and directories that it must not read, and the settings that the runner writes
	 * for it.
	 */
	private static final class Fixture
	{
		private final Path root;
		private final Path deniedFile;
		private final Path deniedDirectory;
		private final List<Path> deniedReads = new ArrayList<>();
		private final HarnessRunManager manager;
		private final HarnessRun run;
		private final SessionRequestAccess requestAccess;
		private final List<Path> dependencyCaches;
		private final List<Path> evidence;

		/**
		 * Creates the run.
		 *
		 * @param scope   the scope
		 * @param cleanup removes the fixture's directories
		 * @throws IOException if a fixture cannot be created
		 */
		Fixture(HarnessRunnerScope scope, TemporaryDirectories cleanup) throws IOException
		{
			root = cleanup.add(Files.createTempDirectory("cat-judge-no-sandbox-")).toRealPath();
			Path hostProject = Files.createDirectories(root.resolve("project"));
			manager = new HarnessRunManager(scope.fileLocks(), hostProject.resolve("runs"), Clock.systemUTC(),
				Yielding.fileReplacer());
			run = manager.create("claude");
			manager.snapshotProject(run);
			Files.createDirectories(run.directory().resolve("home/.claude"));
			deniedFile = Files.writeString(root.resolve("campaign.json"), "{}");
			deniedDirectory = Files.createDirectory(root.resolve("trials"));
			evidence = List.of(Files.createDirectory(root.resolve("evidence")));
			requestAccess = SessionRequestAccess.allowing(run, root.resolve("artifact"), 0, Platform.LINUX_X64,
				SessionRequestAccess.Shell.POSIX).orElseThrow();
			deniedReads.addAll(List.of(deniedFile, deniedDirectory, hostProject));
			dependencyCaches = List.of(root.resolve("host-home/.npm"), root.resolve("host-home/.m2"));
		}

		/**
		 * Returns the rule that denies the file tools a path, as the runner writes it.
		 *
		 * @param path      the path
		 * @param directory {@code true} if the rule covers the directory's descendants
		 * @return the rule
		 */
		String rule(Path path, boolean directory)
		{
			String descendants = "";
			if (directory)
				descendants = "/**";
			return "Read(/" + ClaudeConnectionSettings.rulePath(path) + descendants + ")";
		}

		/**
		 * Returns the rule that lets the file tools read the run's link-targets directory.
		 *
		 * @return the rule
		 */
		String linkTargetsRule()
		{
			return rule(HarnessRunManager.linkTargets(run.directory()), true);
		}

		/**
		 * Writes the settings of a session with a role.
		 *
		 * @param scope the scope
		 * @param name  the name of the settings file in the fixture's directory
		 * @param role  the session's role
		 * @return the settings file
		 * @throws IOException if settings I/O fails
		 */
		Path write(HarnessRunnerScope scope, String name, ClaudeSessionRole role) throws IOException
		{
			return write(scope, name, role.promptCacheTtl(), role.sandboxed(), role.tools());
		}

		/**
		 * Writes the settings of a session whose model is offered some tools.
		 *
		 * @param scope          the scope
		 * @param name           the name of the settings file in the fixture's directory
		 * @param promptCacheTtl the prompt cache lifetime
		 * @param sandboxed      {@code true} if the session runs inside a sandbox
		 * @param tools          the tools that the session's model is offered, or none for every tool
		 * @return the settings file
		 * @throws IOException if settings I/O fails
		 */
		Path write(HarnessRunnerScope scope, String name, String promptCacheTtl, boolean sandboxed, List<String> tools)
			throws IOException
		{
			Path destination = root.resolve(name);
			ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), destination,
				run.directory().resolve("claude"), run.directory().resolve("home"), dependencyCaches, run.directory(),
				sessionDeniedReads(), "", requestAccess, run.directory().resolve("project"), run.directory(), null,
				ExtraPermissions.NONE, "api.anthropic.com", promptCacheTtl, sandboxed, tools);
			return destination;
		}

		/**
		 * Writes the settings of a session that has no role, as the runner wrote them before roles had a sandbox of
		 * their own.
		 *
		 * @param scope the scope
		 * @param name  the name of the settings file in the fixture's directory
		 * @return the settings file
		 * @throws IOException if settings I/O fails
		 */
		Path writeWithoutRole(HarnessRunnerScope scope, String name) throws IOException
		{
			Path destination = root.resolve(name);
			ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), destination,
				run.directory().resolve("claude"), run.directory().resolve("home"), dependencyCaches, run.directory(),
				sessionDeniedReads(), "", requestAccess, run.directory().resolve("project"), run.directory(), null,
				ExtraPermissions.NONE, "api.anthropic.com", ClaudeSessionRole.TRIAL.promptCacheTtl());
			return destination;
		}

		/**
		 * Returns the paths that the session must not read.
		 *
		 * @return the paths
		 * @throws IOException if a denied directory cannot be listed
		 */
		private List<Path> sessionDeniedReads() throws IOException
		{
			return ClaudeRunner.sessionDeniedReads(manager, run, evidence, deniedReads, Map.of(), requestAccess);
		}
	}
}
