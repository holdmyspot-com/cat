package com.holdmyspot.cat.codex.runner.test;

import com.holdmyspot.cat.codex.runner.CodexDirectLaunch;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests the reason that a runner launcher gives when a Codex session runs it inside the session's sandbox, through
 * {@link CodexDirectLaunch#sandboxed(Path, Map, Path, Path)}. The rollouts' records follow those of Codex 0.160.0.
 */
public final class CodexDirectLaunchTest
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
	 * Creates the test.
	 */
	public CodexDirectLaunchTest()
	{
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

			String reason = CodexDirectLaunch.sandboxed(fixture.launcher, fixture.environment(), fixture.home, null);

			requireThat(reason, "reason").contains("openai/codex#38318").
				contains("the permissions profile private-reads").contains("\"" + secret + "\"").
				contains(fixture.launcher.toString());
		}
	}

	/**
	 * Verifies that when the thread's latest turn denies no read, the reason names the rule that install adds and the
	 * launcher's path, and does not blame denied reads.
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

			String reason = CodexDirectLaunch.sandboxed(fixture.launcher, fixture.environment(), fixture.home, null);

			requireThat(reason, "reason").contains("the rule that CAT's install adds for " + fixture.launcher).
				doesNotContain("openai/codex#38318").doesNotContain("No rule in").doesNotContain("ran the launcher as");
		}
	}

	/**
	 * Verifies that a launcher whose thread cannot be identified still gets the reason that names the rule and the
	 * launcher's path.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void namesRuleWhenThreadIsUnknown() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.installed();
			String reason = CodexDirectLaunch.sandboxed(fixture.launcher, fixture.environment(), fixture.home, null);

			requireThat(reason, "reason").contains("the rule that CAT's install adds for " + fixture.launcher);
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

			String reason = CodexDirectLaunch.sandboxed(fixture.launcher, fixture.environment(), fixture.home, null);

			requireThat(reason, "reason").contains("openai/codex#38318").contains("\"" + pattern + "\"");
		}
	}

	/**
	 * Verifies that when no rule in Codex's rules folder names the launcher, the reason says so and names the folder
	 * and the launcher, and does not list the other causes.
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

			String reason = CodexDirectLaunch.sandboxed(fixture.launcher, fixture.environment(), fixture.home, null);

			requireThat(reason, "reason").contains("No rule in " + fixture.codexHome.resolve("rules") + " names " +
				fixture.launcher).doesNotContain("A session that started before that install");
		}
	}

	/**
	 * Verifies that when the thread's latest command that names the launcher's file name names the launcher by
	 * another path, the reason names that path and the launcher's absolute path. The latest command is in a script
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
			String exact = JSON.writeValueAsString(JSON.writeValueAsString(Map.of("cmd", fixture.launcher +
				" --prompt-file task.md")));
			String script = JSON.writeValueAsString("const r = await tools.exec_command({cmd:" +
				JSON.writeValueAsString("cd " + fixture.project + " && ./plugin/bin/codex-runner --prompt-file task.md") +
				"}); text(r.output);\n");
			Files.writeString(fixture.rolloutFile(), """
				{"timestamp":"2026-11-05T12:00:02.000Z","type":"response_item","payload":{"type":"function_call",\
				"name":"exec_command","arguments":%s,"call_id":"call_1"}}
				{"timestamp":"2026-11-05T12:00:03.000Z","type":"response_item","payload":{"type":"custom_tool_call",\
				"status":"completed","call_id":"call_2","name":"exec","input":%s}}
				""".formatted(exact, script), StandardOpenOption.APPEND);

			String reason = CodexDirectLaunch.sandboxed(fixture.launcher, fixture.environment(), fixture.home, null);

			requireThat(reason, "reason").contains("The command ran the launcher as ./plugin/bin/codex-runner, but " +
				"the rule that CAT's install adds matches only a command whose first word is " + fixture.launcher).
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
	 * A user's home folder, Codex's home, a project and a launcher's path.
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
		 * The launcher's absolute path.
		 */
		final Path launcher;

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
				root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "codex-direct-")).
					toRealPath();
			}
			home = Files.createDirectory(root.resolve("home"));
			codexHome = Files.createDirectory(home.resolve(".codex"));
			project = Files.createDirectory(root.resolve("project"));
			launcher = root.resolve("plugin/bin/codex-runner");
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
		 * Writes the rules file that install writes, with the rule that runs the launcher outside the sandbox.
		 *
		 * @throws IOException if the file cannot be written
		 */
		void installed() throws IOException
		{
			Files.createDirectories(codexHome.resolve("rules"));
			Files.writeString(codexHome.resolve("rules/cat-installer.rules"), "prefix_rule(pattern=[" +
				JSON.writeValueAsString(launcher.toString()) + "], decision=\"allow\")\n");
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
