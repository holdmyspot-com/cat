package com.holdmyspot.cat.claude.runner.test;

import com.holdmyspot.cat.claude.policy.ClaudeCommandSession;
import com.holdmyspot.cat.claude.runner.ClaudeServedSession;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.ShellQuoting;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.runner.RequestServer;
import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests how {@link ClaudeServedSession} identifies the Claude Code session that runs the request server, and the
 * reason that the server gives when a Claude Code session runs it inside the session's sandbox, through
 * {@link ClaudeServedSession#sandboxed(Path, Map, Path, String, List, JsonMapper)}. The transcripts' records follow
 * those of Claude Code 2.1.
 */
public final class ClaudeServedSessionTest
{
	/**
	 * The session's identifier.
	 */
	private static final String SESSION_ID = "86af8fc6-4067-4c6e-9f71-53e1bf4164ed";
	/**
	 * The identifier of the session's Claude Code process.
	 */
	private static final long PROCESS_ID = 4242;
	/**
	 * When the session's Claude Code process started, as the operating system reports it.
	 */
	private static final String PROCESS_START = "1731067200";
	/**
	 * The start of every reason.
	 */
	private static final String START = "CAT cannot start the request server: this Claude Code session runs it " +
		"inside its sandbox";
	/**
	 * Parses the settings and transcripts.
	 */
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public ClaudeServedSessionTest()
	{
	}

	/**
	 * Skips the tests on native Windows, where Claude Code has no sandbox, so that no request server runs inside one.
	 */
	@BeforeClass
	public void requireSandboxPlatform()
	{
		if (Platform.current().isWindows())
			throw new SkipException("Claude Code has no sandbox on native Windows, so no request server runs inside one");
	}

	/**
	 * Verifies that the server identifies the session that runs it by the session's transcript, and ends with the
	 * session's Claude Code process, which it identifies by the process's identifier and start.
	 *
	 * @throws IOException   if the fixture cannot be written
	 * @throws PolicyRefusal if the session cannot be identified
	 */
	@Test
	public void identifiesSessionAndHarnessProcess() throws IOException, PolicyRefusal
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.transcript(fixture.command(fixture.server.toString()));

			RequestServer.Session session = ClaudeServedSession.sessions(fixture.environment(), fixture.home, "user",
				HarnessProcesses.system(), Fixture.ancestors(), JSON).find();

			requireThat(session.transcript(), "transcript").isEqualTo(fixture.configuration.
				resolve("projects/-project").resolve(SESSION_ID + ".jsonl"));
			requireThat(session.project(), "project").isEqualTo(fixture.project);
			requireThat(session.harness(), "harness").isEqualTo(new ProcessIdentity(PROCESS_ID, PROCESS_START));
		}
	}

	/**
	 * Verifies that when the user settings name no sandbox exception for the server, the reason says so, names the
	 * settings file and the launcher, and names no other cause.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void namesMissingException() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.settings("{\"sandbox\": {\"enabled\": true, \"excludedCommands\": [\"git *\"]}}");
			fixture.transcript(fixture.command(fixture.server.toString()));

			String reason = fixture.reason();

			requireThat(reason, "reason").startsWith(START).
				contains("The user settings " + fixture.configuration.resolve("settings.json") + " name no sandbox " +
					"exception for " + fixture.server).
				doesNotContain("ran the request server as");
		}
	}

	/**
	 * Verifies that when the latest Bash command that names the launcher's file name, in the session's transcript or
	 * a subagent's, names the launcher by another path, the reason names that path and the launcher's absolute path.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void namesOtherSpelling() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.installed();
			fixture.transcript(fixture.command(ShellQuoting.posix(fixture.server.toString())));
			fixture.subagentTranscript(fixture.command("'~/plugin/bin/request-server'"));

			String reason = fixture.reason();

			requireThat(reason, "reason").startsWith(START).
				contains("The command ran the request server as ~/plugin/bin/request-server, but the sandbox " +
					"exception that CAT's install adds matches only the command " + fixture.server).
				doesNotContain("name no sandbox exception");
		}
	}

	/**
	 * Verifies that when no cause can be found, the reason lists the causes that keep the launcher sandboxed and
	 * names the launcher's path.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void listsCausesWhenNoneIsFound() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.installed();
			fixture.transcript(fixture.command(ShellQuoting.posix(fixture.server.toString())));

			String reason = fixture.reason();

			requireThat(reason, "reason").startsWith(START).
				contains("only through the sandbox exception that CAT's install adds for " + fixture.server).
				contains("A session that started before that install has no such exception");
		}
	}

	/**
	 * A user's home folder, Claude Code's configuration folder, a project, a launcher's path and the session's
	 * Claude Code process.
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
		 * Claude Code's configuration folder.
		 */
		final Path configuration;
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
				root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "claude-direct-")).
					toRealPath();
			}
			home = Files.createDirectory(root.resolve("home"));
			configuration = Files.createDirectory(home.resolve(".claude"));
			project = Files.createDirectory(root.resolve("project"));
			server = root.resolve("plugin/bin/request-server");
		}

		/**
		 * Writes the user settings.
		 *
		 * @param json the settings
		 * @throws IOException if the file cannot be written
		 */
		void settings(String json) throws IOException
		{
			Files.writeString(configuration.resolve("settings.json"), json);
		}

		/**
		 * Writes user settings that hold the sandbox exception and allow rule that install adds for the server.
		 *
		 * @throws IOException if the file cannot be written
		 */
		void installed() throws IOException
		{
			String entry = JSON.writeValueAsString(server.toString());
			settings("{\"sandbox\": {\"enabled\": true, \"excludedCommands\": [" + entry + "]}, " +
				"\"permissions\": {\"allow\": [" + JSON.writeValueAsString("Bash(" + server + ")") + "]}}");
		}

		/**
		 * Returns a transcript record of an assistant message that runs a Bash command.
		 *
		 * @param word the command's first word
		 * @return the record, without its line feed
		 */
		String command(String word)
		{
			String command = word;
			return "{\"type\":\"assistant\",\"timestamp\":\"%s\",\"sessionId\":\"%s\",\"message\":{\"role\":" +
				"\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_01\",\"name\":\"Bash\",\"input\":" +
				"{\"command\":" + JSON.writeValueAsString(command) + "}}]}}";
		}

		/**
		 * Writes the session's transcript, whose first record names the project and whose next record a command at
		 * 12:00.
		 *
		 * @param command the command's record, as {@link #command(String)} returns it
		 * @throws IOException if the transcript cannot be written
		 */
		void transcript(String command) throws IOException
		{
			Path folder = Files.createDirectories(configuration.resolve("projects/-project"));
			Files.writeString(folder.resolve(SESSION_ID + ".jsonl"), "{\"type\":\"user\",\"cwd\":" +
				JSON.writeValueAsString(project.toString()) + ",\"sessionId\":\"" + SESSION_ID + "\"}\n" +
				command.formatted("2026-11-08T12:00:00.000Z", SESSION_ID) + "\n");
		}

		/**
		 * Writes a transcript of one of the session's subagents with a command at 12:05.
		 *
		 * @param command the command's record, as {@link #command(String)} returns it
		 * @throws IOException if the transcript cannot be written
		 */
		void subagentTranscript(String command) throws IOException
		{
			Path folder = Files.createDirectories(configuration.resolve("projects/-project").resolve(SESSION_ID).
				resolve("subagents"));
			Files.writeString(folder.resolve("agent-a1.jsonl"),
				command.formatted("2026-11-08T12:05:00.000Z", SESSION_ID) + "\n");
		}

		/**
		 * Returns the reason that the server gives when the session runs it inside its sandbox.
		 *
		 * @return the reason
		 */
		String reason()
		{
			return ClaudeServedSession.sandboxed(server, environment(), home, "user", ancestors(), JSON);
		}

		/**
		 * Returns the environment of the session's command.
		 *
		 * @return the environment
		 */
		Map<String, String> environment()
		{
			return Map.of("CLAUDE_PID", String.valueOf(PROCESS_ID), ClaudeCommandSession.SESSION_VARIABLE, SESSION_ID,
				"HOME", home.toString());
		}

		/**
		 * Returns the processes that run the session's command, from its parent up.
		 *
		 * @return the processes
		 */
		static List<ProcessDescription> ancestors()
		{
			return List.of(
				new ProcessDescription(4300, Optional.empty(), Optional.of("/bin/bash"), Optional.of(List.of("-c",
					"request-server"))),
				new ProcessDescription(PROCESS_ID, Optional.of(PROCESS_START), Optional.of("/opt/claude/claude"),
					Optional.of(List.of())));
		}

		@Override
		public void close() throws IOException
		{
			cleanup.close();
		}
	}
}
