package com.holdmyspot.cat.claude.policy.test;

import com.holdmyspot.cat.claude.policy.ClaudeCommandSession;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests that a runner launcher identifies the Claude Code session that runs it from the Claude Code process among its
 * ancestors and the session's transcript, and takes the session's project folder from the transcript, through
 * {@link ClaudeCommandSession}. The transcripts' records follow those of Claude Code 2.1, whose first records name no
 * working directory.
 */
public final class ClaudeCommandSessionTest
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
	 * Parses the transcripts.
	 */
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public ClaudeCommandSessionTest()
	{
	}

	/**
	 * Verifies that the session is the one whose transcript has the session's identifier, and that its project folder
	 * is the working directory of the transcript's first record that names one, not the launcher's working directory
	 * or a later record's.
	 *
	 * @throws IOException   if the fixture cannot be written
	 * @throws PolicyRefusal if the session is refused
	 */
	@Test
	public void findsSessionFromTranscript() throws IOException, PolicyRefusal
	{
		try (Fixture fixture = new Fixture())
		{
			Path transcript = fixture.transcript("-project", fixture.project, fixture.root.resolve("later"));

			ClaudeCommandSession found = fixture.find(fixture.environment());

			requireThat(found.transcript(), "transcript").isEqualTo(transcript);
			requireThat(found.session().projectDirectory(), "project").isEqualTo(fixture.project);
			requireThat(found.session().processId(), "processId").isEqualTo(PROCESS_ID);
			requireThat(found.session().configurationDirectory(), "configuration").isEqualTo(fixture.configuration);
		}
	}

	/**
	 * Verifies that the session's transcripts are looked for in the configuration folder that
	 * {@code CLAUDE_CONFIG_DIR} names.
	 *
	 * @throws IOException   if the fixture cannot be written
	 * @throws PolicyRefusal if the session is refused
	 */
	@Test
	public void findsTranscriptInConfiguredFolder() throws IOException, PolicyRefusal
	{
		try (Fixture fixture = new Fixture())
		{
			Path configured = Files.createDirectory(fixture.root.resolve("configured"));
			Path transcript = fixture.transcript(configured, "-project", fixture.project, fixture.project);
			Map<String, String> environment = fixture.environment();
			environment.put("CLAUDE_CONFIG_DIR", configured.toString());

			ClaudeCommandSession found = fixture.find(environment);

			requireThat(found.transcript(), "transcript").isEqualTo(transcript);
		}
	}

	/**
	 * Verifies that a launch whose environment names no Claude Code process, or one that does not run the launcher,
	 * is refused.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusesProcessThatDoesNotRunLauncher() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.transcript("-project", fixture.project, fixture.project);
			Map<String, String> missing = fixture.environment();
			missing.remove("CLAUDE_PID");
			Map<String, String> other = fixture.environment();
			other.put("CLAUDE_PID", "999");

			requireThat(fixture.refusal(missing).reason(), "missing").contains("CLAUDE_PID");
			requireThat(fixture.refusal(other).reason(), "other").contains("\"999\"").
				contains("does not run the launcher");
		}
	}

	/**
	 * Verifies that a launch whose environment names no session, or names one by text that is not a session
	 * identifier, such as a path, is refused.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusesMissingOrInvalidSessionIdentifier() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Map<String, String> missing = fixture.environment();
			missing.remove(ClaudeCommandSession.SESSION_VARIABLE);
			Map<String, String> invalid = fixture.environment();
			invalid.put(ClaudeCommandSession.SESSION_VARIABLE, "../other/" + SESSION_ID);

			requireThat(fixture.refusal(missing).reason(), "missing").contains(ClaudeCommandSession.SESSION_VARIABLE);
			requireThat(fixture.refusal(invalid).reason(), "invalid").contains("is not a session identifier");
		}
	}

	/**
	 * Verifies that a session without a transcript, or with transcripts in more than one project folder, is refused.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusesNoneOrSeveralTranscripts() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			requireThat(fixture.refusal(fixture.environment()).reason(), "none").contains("no transcript");

			fixture.transcript("-project", fixture.project, fixture.project);
			fixture.transcript("-other", fixture.root, fixture.root);

			requireThat(fixture.refusal(fixture.environment()).reason(), "several").
				contains("more than one transcript");
		}
	}

	/**
	 * Verifies that a transcript whose first records name no absolute working directory is refused.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusesTranscriptWithoutProject() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path folder = Files.createDirectories(fixture.configuration.resolve("projects/-project"));
			Files.writeString(folder.resolve(SESSION_ID + ".jsonl"), """
				{"type":"last-prompt","sessionId":"%s"}
				{"type":"user","cwd":"relative/project","sessionId":"%s"}
				""".formatted(SESSION_ID, SESSION_ID));

			requireThat(fixture.refusal(fixture.environment()).reason(), "reason").
				contains("does not name the session's project folder");
		}
	}

	/**
	 * A user's home folder, Claude Code configuration folder and project, and the processes that run a launcher.
	 */
	private static final class Fixture implements AutoCloseable
	{
		/**
		 * Removes the fixture's folders.
		 */
		private final TemporaryDirectories cleanup = new TemporaryDirectories();
		/**
		 * The fixture's real root folder.
		 */
		final Path root;
		/**
		 * The user's home folder.
		 */
		final Path home;
		/**
		 * Claude Code's default configuration folder.
		 */
		final Path configuration;
		/**
		 * The session's project folder.
		 */
		final Path project;

		/**
		 * Creates the fixture.
		 *
		 * @throws IOException if the folders cannot be created
		 */
		Fixture() throws IOException
		{
			try (TestJvmScope jvm = new TestJvmScope())
			{
				root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "claude-command-")).
					toRealPath();
			}
			home = Files.createDirectory(root.resolve("home"));
			configuration = Files.createDirectory(home.resolve(".claude"));
			project = Files.createDirectory(root.resolve("project"));
		}

		/**
		 * Returns the environment of a command that the session runs.
		 *
		 * @return a modifiable environment
		 */
		Map<String, String> environment()
		{
			Map<String, String> environment = new HashMap<>();
			environment.put("CLAUDE_PID", String.valueOf(PROCESS_ID));
			environment.put(ClaudeCommandSession.SESSION_VARIABLE, SESSION_ID);
			environment.put("CLAUDECODE", "1");
			return environment;
		}

		/**
		 * Writes a transcript of the session in the default configuration folder.
		 *
		 * @param folder the name of the transcript's project folder in {@code projects}
		 * @param first  the working directory of the first record that names one
		 * @param later  the working directory of a later record
		 * @return the transcript
		 * @throws IOException if the transcript cannot be written
		 */
		Path transcript(String folder, Path first, Path later) throws IOException
		{
			return transcript(configuration, folder, first, later);
		}

		/**
		 * Writes a transcript of the session.
		 *
		 * @param configurationFolder the configuration folder
		 * @param folder              the name of the transcript's project folder in {@code projects}
		 * @param first               the working directory of the first record that names one
		 * @param later               the working directory of a later record
		 * @return the transcript
		 * @throws IOException if the transcript cannot be written
		 */
		Path transcript(Path configurationFolder, String folder, Path first, Path later) throws IOException
		{
			Path directory = Files.createDirectories(configurationFolder.resolve("projects").resolve(folder));
			return Files.writeString(directory.resolve(SESSION_ID + ".jsonl"), """
				{"type":"last-prompt","lastPrompt":"task","sessionId":"%s"}
				{"type":"mode","mode":"default","sessionId":"%s"}
				{"type":"user","cwd":%s,"sessionId":"%s"}
				{"type":"assistant","cwd":%s,"sessionId":"%s"}
				""".formatted(SESSION_ID, SESSION_ID, json(first), SESSION_ID, json(later), SESSION_ID));
		}

		/**
		 * Finds the session that runs a command whose ancestors are a shell and the session's Claude Code process.
		 *
		 * @param environment the command's environment
		 * @return the session
		 * @throws IOException   if a transcript cannot be read
		 * @throws PolicyRefusal if the session is refused
		 */
		ClaudeCommandSession find(Map<String, String> environment) throws IOException, PolicyRefusal
		{
			List<ProcessDescription> ancestors = List.of(
				new ProcessDescription(4300, Optional.empty(), Optional.of("/bin/bash"),
					Optional.of(List.of("-c", "launcher"))),
				new ProcessDescription(PROCESS_ID, Optional.empty(), Optional.of("/opt/claude/claude"),
					Optional.of(List.of())));
			return ClaudeCommandSession.find(environment, home, "user", ancestors, JSON);
		}

		/**
		 * Returns the refusal of the session that runs a command.
		 *
		 * @param environment the command's environment
		 * @return the refusal
		 * @throws IOException if a transcript cannot be read
		 */
		PolicyRefusal refusal(Map<String, String> environment) throws IOException
		{
			try
			{
				find(environment);
			}
			catch (PolicyRefusal e)
			{
				return e;
			}
			throw new AssertionError("The session was not refused");
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

		@Override
		public void close() throws IOException
		{
			cleanup.close();
		}
	}
}
