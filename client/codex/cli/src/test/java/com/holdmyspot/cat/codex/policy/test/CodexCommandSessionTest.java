package com.holdmyspot.cat.codex.policy.test;

import com.holdmyspot.cat.codex.policy.CodexCommandSession;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests that a runner launcher identifies the Codex session and thread that run it from the thread's rollout, which
 * {@code CODEX_THREAD_ID} names, and takes the project folder from the rollout, through {@link CodexCommandSession}.
 * The rollouts' first records follow those of Codex 0.160.0.
 */
public final class CodexCommandSessionTest
{
	/**
	 * The session's identifier, which is its main thread's.
	 */
	private static final String SESSION_ID = "01a1a8eb-3d83-7d73-b243-c636c2041ee1";
	/**
	 * The identifier of a subagent thread of the session.
	 */
	private static final String SUBAGENT_THREAD = "01a1a8f0-1111-7222-8333-444455556666";

	/**
	 * Creates the test.
	 */
	public CodexCommandSessionTest()
	{
	}

	/**
	 * Verifies that a command of the session's main thread finds the session's rollout, and that the project folder
	 * is the working directory that the rollout's first record names, not the launcher's working directory.
	 *
	 * @throws IOException   if the fixture cannot be written
	 * @throws PolicyRefusal if the session is refused
	 */
	@Test
	public void findsMainThreadFromRollout() throws IOException, PolicyRefusal
	{
		try (Fixture fixture = new Fixture())
		{
			Path rollout = fixture.rollout("2026/11/05", SESSION_ID, SESSION_ID, fixture.project);

			CodexCommandSession found = CodexCommandSession.find(fixture.environment(SESSION_ID), fixture.home,
				List.of());

			requireThat(found.rollout(), "rollout").isEqualTo(rollout);
			requireThat(found.project(), "project").isEqualTo(fixture.project);
			requireThat(found.askingThread(), "askingThread").isEmpty();
			requireThat(found.session().sessionId(), "sessionId").isEqualTo(SESSION_ID);
			requireThat(found.session().transcript(), "transcript").isEqualTo(Optional.of(rollout));
		}
	}

	/**
	 * Verifies that a command of a subagent thread finds the thread's rollout and the session's main rollout, and
	 * that the project folder is the thread's working directory.
	 *
	 * @throws IOException   if the fixture cannot be written
	 * @throws PolicyRefusal if the session is refused
	 */
	@Test
	public void findsSubagentThreadAndItsSession() throws IOException, PolicyRefusal
	{
		try (Fixture fixture = new Fixture())
		{
			Path main = fixture.rollout("2026/11/05", SESSION_ID, SESSION_ID, fixture.project);
			Path worktree = Files.createDirectory(fixture.root.resolve("worktree"));
			Path thread = fixture.rollout("2026/11/06", SUBAGENT_THREAD, SESSION_ID, worktree);

			CodexCommandSession found = CodexCommandSession.find(fixture.environment(SUBAGENT_THREAD), fixture.home,
				List.of());

			requireThat(found.rollout(), "rollout").isEqualTo(thread);
			requireThat(found.project(), "project").isEqualTo(worktree);
			requireThat(found.askingThread(), "askingThread").isEqualTo(Optional.of(SUBAGENT_THREAD));
			requireThat(found.session().sessionId(), "sessionId").isEqualTo(SESSION_ID);
			requireThat(found.session().transcript(), "transcript").isEqualTo(Optional.of(main));
			requireThat(found.session().cwd(), "cwd").isEqualTo(fixture.project);
		}
	}

	/**
	 * Verifies that a command whose environment names no thread, or a thread by text that is not a thread
	 * identifier, is refused.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusesMissingOrInvalidThread() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Map<String, String> missing = fixture.environment(SESSION_ID);
			missing.remove(CodexCommandSession.THREAD_VARIABLE);

			requireThat(fixture.refusal(missing).reason(), "missing").contains(CodexCommandSession.THREAD_VARIABLE);
			requireThat(fixture.refusal(fixture.environment("../" + SESSION_ID)).reason(), "invalid").
				contains(CodexCommandSession.THREAD_VARIABLE);
		}
	}

	/**
	 * Verifies that a thread without a rollout, or with more than one, is refused.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusesNoneOrSeveralRollouts() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			requireThat(fixture.refusal(fixture.environment(SESSION_ID)).reason(), "none").contains("no rollout");

			fixture.rollout("2026/11/05", SESSION_ID, SESSION_ID, fixture.project);
			fixture.rollout("2026/11/06", SESSION_ID, SESSION_ID, fixture.project);

			requireThat(fixture.refusal(fixture.environment(SESSION_ID)).reason(), "several").
				contains("more than one rollout");
		}
	}

	/**
	 * Verifies that a subagent thread whose session has no rollout is refused.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusesSubagentWithoutSessionRollout() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.rollout("2026/11/06", SUBAGENT_THREAD, SESSION_ID, fixture.project);

			requireThat(fixture.refusal(fixture.environment(SUBAGENT_THREAD)).reason(), "reason").
				contains("no rollout of the Codex thread " + SESSION_ID);
		}
	}

	/**
	 * Verifies that a rollout whose first record names no absolute working directory is refused.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusesRolloutWithoutWorkingDirectory() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path folder = Files.createDirectories(fixture.codexHome.resolve("sessions/2026/11/05"));
			Files.writeString(folder.resolve("rollout-2026-11-05T12-00-00-" + SESSION_ID + ".jsonl"), """
				{"timestamp":"2026-11-05T12:00:00.000Z","type":"session_meta","payload":{"session_id":"%s",\
				"id":"%s","cwd":"relative/project","originator":"codex_exec","cli_version":"0.160.0"}}
				""".formatted(SESSION_ID, SESSION_ID));

			requireThat(fixture.refusal(fixture.environment(SESSION_ID)).reason(), "reason").
				contains("absolute working directory");
		}
	}

	/**
	 * Verifies that a command whose {@code CODEX_HOME} is not an absolute path is refused.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusesRelativeCodexHome() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Map<String, String> environment = fixture.environment(SESSION_ID);
			environment.put("CODEX_HOME", "relative/.codex");

			requireThat(fixture.refusal(environment).reason(), "reason").contains("CODEX_HOME").
				contains("is not an absolute path");
		}
	}

	/**
	 * A user's home folder, Codex's home and a project.
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
		 * Codex's home.
		 */
		final Path codexHome;
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
				root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "codex-command-")).
					toRealPath();
			}
			home = Files.createDirectory(root.resolve("home"));
			codexHome = Files.createDirectory(home.resolve(".codex"));
			project = Files.createDirectory(root.resolve("project"));
		}

		/**
		 * Returns the environment of a command that a thread runs.
		 *
		 * @param threadId the thread
		 * @return a modifiable environment
		 */
		Map<String, String> environment(String threadId)
		{
			Map<String, String> environment = new HashMap<>();
			environment.put(CodexCommandSession.THREAD_VARIABLE, threadId);
			environment.put("CODEX_HOME", codexHome.toString());
			return environment;
		}

		/**
		 * Writes a thread's rollout.
		 *
		 * @param day       the rollout's day folder below {@code sessions}, such as {@code 2026/11/05}
		 * @param threadId  the thread
		 * @param sessionId the session that the rollout's first record names
		 * @param cwd       the working directory that the rollout's first record names
		 * @return the rollout
		 * @throws IOException if the rollout cannot be written
		 */
		Path rollout(String day, String threadId, String sessionId, Path cwd) throws IOException
		{
			Path folder = Files.createDirectories(codexHome.resolve("sessions").resolve(day));
			return Files.writeString(folder.resolve("rollout-" + day.replace('/', '-') + "T12-00-00-" + threadId +
				".jsonl"), """
				{"timestamp":"2026-11-05T12:00:00.000Z","type":"session_meta","payload":{"session_id":"%s",\
				"id":"%s","cwd":"%s","originator":"codex_exec","cli_version":"0.160.0","source":"exec"}}
				""".formatted(sessionId, threadId, cwd.toString().replace("\\", "\\\\")));
		}

		/**
		 * Returns the refusal of the session that runs a command.
		 *
		 * @param environment the command's environment
		 * @return the refusal
		 * @throws IOException if a rollout cannot be read
		 */
		PolicyRefusal refusal(Map<String, String> environment) throws IOException
		{
			try
			{
				CodexCommandSession.find(environment, home, List.of());
			}
			catch (PolicyRefusal e)
			{
				return e;
			}
			throw new AssertionError("The session was not refused");
		}

		@Override
		public void close() throws IOException
		{
			cleanup.close();
		}
	}
}
