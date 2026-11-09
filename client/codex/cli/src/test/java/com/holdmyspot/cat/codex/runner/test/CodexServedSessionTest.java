package com.holdmyspot.cat.codex.runner.test;

import com.holdmyspot.cat.codex.policy.CodexPolicyReader;
import com.holdmyspot.cat.codex.policy.CodexSystemFiles;
import com.holdmyspot.cat.codex.runner.CodexServedSession;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.RequestServer;
import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests how the request server identifies the Codex session that runs it, and the reason that it gives when a Codex
 * session runs it inside the session's sandbox, through {@link CodexServedSession#sandboxed(Path, Map, Path, Path)}.
 * The rollouts' records follow those of Codex 0.160.0.
 */
public final class CodexServedSessionTest
{
	/**
	 * The session's identifier.
	 */
	private static final String SESSION_ID = "01a1a8eb-3d83-7d73-b243-c636c2041ee1";
	/**
	 * Writes JSON strings.
	 */
	private static final JsonMapper JSON = JsonMapper.builder().build();
	/**
	 * A process table in which no process runs, which no identification reads.
	 */
	private static final HarnessProcesses NO_PROCESSES = _ -> Optional.empty();

	/**
	 * Creates the test.
	 */
	public CodexServedSessionTest()
	{
	}

	/**
	 * Verifies that the server identifies the session by the thread's rollout, takes the project from it, and takes
	 * the harness process that it outlives no longer than from the nearest Codex process among its ancestors.
	 *
	 * @throws IOException   if the fixture cannot be written
	 * @throws PolicyRefusal if the session cannot be identified
	 */
	@Test
	public void identifiesSessionAndHarnessProcess() throws IOException, PolicyRefusal
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.rollout("", "");
			List<ProcessDescription> ancestors = List.of(
				new ProcessDescription(4300, Optional.of("1731067201"), Optional.of("/bin/bash"),
					Optional.of(List.of("-c", fixture.server.toString()))),
				new ProcessDescription(4200, Optional.of("1731067200"), Optional.of("/opt/codex/bin/codex"),
					Optional.of(List.of())));

			RequestServer.Session session = CodexServedSession.sessions(fixture.environment(), fixture.home,
				NO_PROCESSES, ancestors, fixture.reader()).find();

			requireThat(session.transcript(), "transcript").isEqualTo(fixture.rolloutFile());
			requireThat(session.project(), "project").isEqualTo(fixture.project);
			requireThat(session.askingThread(), "askingThread").isEmpty();
			requireThat(session.harness(), "harness").isEqualTo(new ProcessIdentity(4200, "1731067200"));
		}
	}

	/**
	 * Verifies that the server refuses a session whose commands no Codex process runs, because it could not tell
	 * when the session ends.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusesSessionWithoutCodexProcess() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.rollout("", "");
			List<ProcessDescription> ancestors = List.of(new ProcessDescription(4300, Optional.of("1731067201"),
				Optional.of("/bin/bash"), Optional.of(List.of("-c", fixture.server.toString()))));
			RequestServer.SessionFinder finder = CodexServedSession.sessions(fixture.environment(), fixture.home,
				NO_PROCESSES, ancestors, fixture.reader());
			try
			{
				finder.find();
				throw new AssertionError("The session was identified");
			}
			catch (PolicyRefusal e)
			{
				requireThat(e.reason(), "reason").contains("cannot find the Codex process");
			}
		}
	}

	/**
	 * Verifies that when the thread's latest turn denies reads, the reason names the Codex limitation, the profile
	 * and each denied path, because Codex then keeps every command inside its sandbox whatever rule allows it.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void namesDeniedReads() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path secret = Files.createDirectory(fixture.project.resolve("secret"));
			fixture.rollout("""
				,{"path":{"type":"path","path":%s},"access":"deny"}""".formatted(json(secret)),
				",\"active_permission_profile\":{\"id\":\"private-reads\"}");

			String reason = CodexServedSession.sandboxed(fixture.server, fixture.environment(), fixture.home, null);

			requireThat(reason, "reason").contains("openai/codex#38318").
				contains("the permissions profile private-reads").contains("\"" + secret + "\"").
				contains("Remove those entries from the profile and start a new Codex session, or start the session " +
					"from a terminal.");
		}
	}

	/**
	 * Verifies that when the thread's latest turn denies no read, the reason names the rule that install adds and the
	 * server's path, and does not blame denied reads.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void namesRuleWhenNoReadIsDenied() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.installed();
			fixture.rollout("", "");

			String reason = CodexServedSession.sandboxed(fixture.server, fixture.environment(), fixture.home, null);

			requireThat(reason, "reason").contains("the rule that CAT's install adds for " + fixture.server).
				doesNotContain("openai/codex#38318").doesNotContain("No rule in").doesNotContain("ran the request server as");
		}
	}

	/**
	 * Verifies that a request server that a session of a CAT run runs inside its sandbox is told that no request
	 * server serves a run's session, that Codex keeps a rule-allowed command sandboxed while the session denies reads,
	 * and that the session requests sessions through the launchers. A run's session keeps no rollout unless its caller
	 * keeps the session's transcripts, and its permissions are the runner's, so neither the rule that install adds nor
	 * the profile's entries are the advice.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void explainsRequestServerInRun() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.installed();
			Map<String, String> environment = new HashMap<>(fixture.environment());
			environment.put(HarnessEnvironment.RUN_VARIABLE, HarnessEnvironment.RUN_VALUE);

			String withoutRollout = CodexServedSession.sandboxed(fixture.server, environment, fixture.home, null);

			requireThat(withoutRollout, "withoutRollout").contains("a session of a CAT run").
				contains("openai/codex#38318").contains("--session-requests <count>").
				doesNotContain("the rule that CAT's install adds").doesNotContain("No rule in");

			Path secret = Files.createDirectory(fixture.project.resolve("secret"));
			fixture.rollout("""
				,{"path":{"type":"path","path":%s},"access":"deny"}""".formatted(json(secret)),
				",\"active_permission_profile\":{\"id\":\"cat-denied-reads\"}");

			String withRollout = CodexServedSession.sandboxed(fixture.server, environment, fixture.home, null);

			requireThat(withRollout, "withRollout").isEqualTo(withoutRollout);
			requireThat(withRollout, "withRollout").doesNotContain("Remove those entries");
		}
	}

	/**
	 * Verifies that a server whose thread cannot be identified still gets the reason that names the rule and the
	 * server's path.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void namesRuleWhenThreadIsUnknown() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.installed();
			String reason = CodexServedSession.sandboxed(fixture.server, fixture.environment(), fixture.home, null);

			requireThat(reason, "reason").contains("the rule that CAT's install adds for " + fixture.server);
		}
	}

	/**
	 * Verifies that the reason names a denied glob pattern of the latest turn, also when the pattern matches no
	 * existing path.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void namesDeniedGlobPattern() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.installed();
			String pattern = fixture.project + "/**/*.env";
			fixture.rollout("""
				,{"path":{"type":"glob_pattern","pattern":%s},"access":"deny"}""".formatted(JSON.writeValueAsString(pattern)),
				"");

			String reason = CodexServedSession.sandboxed(fixture.server, fixture.environment(), fixture.home, null);

			requireThat(reason, "reason").contains("openai/codex#38318").contains("\"" + pattern + "\"");
		}
	}

	/**
	 * Verifies that when no rule in Codex's rules folder names the server, the reason says so and names the folder
	 * and the server, and does not list the other causes.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void namesMissingRule() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Files.createDirectories(fixture.codexHome.resolve("rules"));
			Files.writeString(fixture.codexHome.resolve("rules/default.rules"),
				"prefix_rule(pattern=[\"git\", \"status\"], decision=\"allow\")\n");
			fixture.rollout("", "");

			String reason = CodexServedSession.sandboxed(fixture.server, fixture.environment(), fixture.home, null);

			requireThat(reason, "reason").contains("No rule in " + fixture.codexHome.resolve("rules") + " names " +
				fixture.server).doesNotContain("A session that started before that install");
		}
	}

	/**
	 * Verifies that when the thread's latest command that names the server's file name names the server by another
	 * path, the reason names that path and the server's absolute path. The latest command is in a script
	 * of the code-mode tool, after one that names the absolute path.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void namesOtherSpelling() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.installed();
			fixture.rollout("", "");
			String exact = JSON.writeValueAsString(JSON.writeValueAsString(Map.of("cmd",
				fixture.server.toString())));
			String script = JSON.writeValueAsString("const r = await tools.exec_command({cmd:" +
				JSON.writeValueAsString("cd " + fixture.project + " && ./plugin/bin/request-server") +
				"}); text(r.output);\n");
			Files.writeString(fixture.rolloutFile(), """
				{"timestamp":"2026-11-05T12:00:02.000Z","type":"response_item","payload":{"type":"function_call",\
				"name":"exec_command","arguments":%s,"call_id":"call_1"}}
				{"timestamp":"2026-11-05T12:00:03.000Z","type":"response_item","payload":{"type":"custom_tool_call",\
				"status":"completed","call_id":"call_2","name":"exec","input":%s}}
				""".formatted(exact, script), StandardOpenOption.APPEND);

			String reason = CodexServedSession.sandboxed(fixture.server, fixture.environment(), fixture.home, null);

			requireThat(reason, "reason").contains("The command ran the request server as ./plugin/bin/request-server, " +
				"but the rule that CAT's install adds matches only the command " + fixture.server + ", spelled exactly " +
				"so. Run " + fixture.server + " alone").
				doesNotContain("No rule in");
		}
	}

	/**
	 * Returns a path as a JSON string.
	 *
	 * @param path the path
	 * @return the string
	 */
	private static String json(Path path)
	{
		return "\"" + path.toString().replace("\\", "\\\\") + "\"";
	}

	/**
	 * A user's home folder, Codex's home, a project and the request server's path.
	 */
	private static final class Fixture implements AutoCloseable
	{
		/**
		 * Removes the fixture's folders.
		 */
		private final TemporaryDirectories cleanup = new TemporaryDirectories();
		/**
		 * The user's home folder.
		 */
		final Path home;
		/**
		 * Codex's home.
		 */
		final Path codexHome;
		/**
		 * The session's project folder.
		 */
		final Path project;
		/**
		 * The request server's absolute path.
		 */
		final Path server;

		/**
		 * Creates the fixture.
		 *
		 * @throws IOException if the folders cannot be created
		 */
		Fixture() throws IOException
		{
			Path root;
			try (TestJvmScope jvm = new TestJvmScope())
			{
				root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "codex-served-")).
					toRealPath();
			}
			home = Files.createDirectory(root.resolve("home"));
			codexHome = Files.createDirectory(home.resolve(".codex"));
			project = Files.createDirectory(root.resolve("project"));
			server = root.resolve("plugin/bin/request-server");
		}

		/**
		 * Returns the environment of a command that the session's main thread runs.
		 *
		 * @return the environment
		 */
		Map<String, String> environment()
		{
			return Map.of("CODEX_THREAD_ID", SESSION_ID, "CODEX_HOME", codexHome.toString());
		}

		/**
		 * Returns a reader of the session's policy, for a machine without Codex configuration.
		 *
		 * @return the reader
		 */
		CodexPolicyReader reader()
		{
			return new CodexPolicyReader(new CodexSystemFiles(home.resolve("etc"), List.of(), null));
		}

		/**
		 * Writes the rules file that install writes, with the rule that runs the server outside the sandbox.
		 *
		 * @throws IOException if the file cannot be written
		 */
		void installed() throws IOException
		{
			Files.createDirectories(codexHome.resolve("rules"));
			Files.writeString(codexHome.resolve("rules/cat-installer.rules"), "prefix_rule(pattern=[" +
				JSON.writeValueAsString(server.toString()) + "], decision=\"allow\")\n");
		}

		/**
		 * Returns the main thread's rollout file.
		 *
		 * @return the file
		 */
		Path rolloutFile()
		{
			return codexHome.resolve("sessions/2026/11/05/rollout-2026-11-05T12-00-00-" + SESSION_ID + ".jsonl");
		}

		/**
		 * Writes the main thread's rollout with one turn that may read everything and write the project.
		 *
		 * @param moreEntries further {@code permission_profile} entries, each led by a comma
		 * @param moreFields  further fields of the turn's payload, each led by a comma
		 * @throws IOException if the rollout cannot be written
		 */
		void rollout(String moreEntries, String moreFields) throws IOException
		{
			Path folder = Files.createDirectories(codexHome.resolve("sessions/2026/11/05"));
			Files.writeString(folder.resolve("rollout-2026-11-05T12-00-00-" + SESSION_ID + ".jsonl"), """
				{"timestamp":"2026-11-05T12:00:00.000Z","type":"session_meta","payload":{"session_id":"%s",\
				"id":"%s","cwd":%s,"originator":"codex_exec","cli_version":"0.160.0","source":"exec"}}
				{"timestamp":"2026-11-05T12:00:01.000Z","type":"turn_context","payload":{"turn_id":"t1",\
				"cwd":%s,"workspace_roots":[%s],"approval_policy":"never","sandbox_policy":\
				{"type":"workspace-write","network_access":true,"exclude_tmpdir_env_var":false,\
				"exclude_slash_tmp":false},"permission_profile":{"type":"managed","file_system":\
				{"type":"restricted","entries":[{"path":{"type":"special","value":{"kind":"root"}},\
				"access":"read"},{"path":{"type":"path","path":%s},"access":"write"}%s]},\
				"network":"enabled"}%s,"model":"gpt-6.1-sol"}}
				""".formatted(SESSION_ID, SESSION_ID, json(project), json(project), json(project), json(project),
				moreEntries.replace("\n", ""), moreFields));
		}

		@Override
		public void close() throws IOException
		{
			cleanup.close();
		}
	}
}
