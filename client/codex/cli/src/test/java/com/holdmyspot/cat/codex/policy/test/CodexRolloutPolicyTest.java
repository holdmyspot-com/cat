package com.holdmyspot.cat.codex.policy.test;

import static com.holdmyspot.cat.codex.policy.test.CodexParentSession.json;
import static com.holdmyspot.cat.codex.policy.test.CodexParentSession.toml;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.policy.CodexPolicyReader;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Verifies that {@link CodexPolicyReader} narrows a Codex session's policy by the resolved policy that Codex records
 * in the rollout of the thread that runs the command, which the command's {@code CODEX_THREAD_ID} names.
 * <p>
 * The rollout records follow those of Codex 0.160.0 ({@code codex exec}, Linux), with the instructions left out.
 */
public final class CodexRolloutPolicyTest
{
	private static final String SUBAGENT_THREAD = "01a1a8f0-1111-7222-8333-444455556666";

	/**
	 * Creates a test instance.
	 */
	public CodexRolloutPolicyTest()
	{
	}

	/**
	 * Verifies that a rollout {@code turn_context} that grants less than the configuration narrows the policy.
	 */
	@Test
	public void rolloutNarrowerWins() throws IOException, PolicyRefusal
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CodexParentSession session = new CodexParentSession(cleanup.add(Files.createTempDirectory("codex-policy")));
			Path extra = configureExtraRoot(session);
			session.rollout(session.writeRollout(CodexParentSession.SESSION_ID, turnContext(session,
				projectOnlyEntries(session))));

			ParentPolicy policy = session.read();

			requireThat(policy.writeRoots(), "writeRoots").contains(session.project()).doesNotContain(extra);
		}
	}

	/**
	 * Verifies that a rollout entry that Codex records as {@code "access":"deny"}, for a profile path set to
	 * {@code none}, keeps the commands from reading and writing that path.
	 */
	@Test
	public void rolloutDenialApplies() throws IOException, PolicyRefusal
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CodexParentSession session = new CodexParentSession(cleanup.add(Files.createTempDirectory("codex-policy")));
			configureExtraRoot(session);
			Path secret = session.folder("project/secret");
			session.rollout(session.writeRollout(CodexParentSession.SESSION_ID, turnContext(session,
				projectOnlyEntries(session) + """
					,
					{"path":{"type":"path","path":%s},"access":"deny"}""".formatted(json(secret)))));

			ParentPolicy policy = session.read();

			requireThat(policy.readDenials(), "readDenials").contains(secret);
			requireThat(policy.writeDenials(), "writeDenials").contains(secret);
		}
	}

	/**
	 * Verifies that a rollout entry of a kind that the reader does not know is refused.
	 */
	@Test(expectedExceptions = PolicyRefusal.class, expectedExceptionsMessageRegExp = ".*home_directory.*")
	public void rolloutUnknownKindRefused() throws IOException, PolicyRefusal
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CodexParentSession session = new CodexParentSession(cleanup.add(Files.createTempDirectory("codex-policy")));
			configureExtraRoot(session);
			String entries = """
				{"path":{"type":"special","value":{"kind":"root"}},"access":"read"},
				{"path":{"type":"special","value":{"kind":"home_directory"}},"access":"write"}""";
			session.rollout(session.writeRollout(CodexParentSession.SESSION_ID, turnContext(session,
				entries)));

			session.read();
		}
	}

	/**
	 * Verifies that the narrower policy of the subagent thread that asks applies.
	 */
	@Test
	public void subagentNarrowerApplies() throws IOException, PolicyRefusal
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CodexParentSession session = new CodexParentSession(cleanup.add(Files.createTempDirectory("codex-policy")));
			Path extra = configureExtraRoot(session);
			session.writeRollout(SUBAGENT_THREAD, turnContext(session, projectOnlyEntries(session)));

			ParentPolicy policy = session.readThread(SUBAGENT_THREAD);

			requireThat(policy.writeRoots(), "writeRoots").contains(session.project()).doesNotContain(extra);
		}
	}

	/**
	 * Verifies that a command whose {@code CODEX_THREAD_ID} is not a thread identifier, or names a thread whose rollout
	 * Codex does not keep, is refused rather than given the policy of another thread.
	 */
	@Test
	public void unknownThreadRefused() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CodexParentSession session = new CodexParentSession(cleanup.add(Files.createTempDirectory("codex-policy")));
			configureExtraRoot(session);
			session.writeRollout(CodexParentSession.SESSION_ID, turnContext(session, projectOnlyEntries(session)));

			for (String threadId : new String[]{"../../config", "01a1a8f0-0000-7000-8000-000000000000"})
			{
				try
				{
					ParentPolicy policy = session.readThread(threadId);
					throw new AssertionError("The thread " + threadId + " was not refused: " + policy);
				}
				catch (PolicyRefusal e)
				{
					requireThat(e.reason(), "reason").withContext(threadId, "threadId").contains(threadId);
				}
			}
		}
	}

	/**
	 * Configures the session to write the project and another folder, with network access.
	 *
	 * @param session the session
	 * @return the other folder
	 * @throws IOException if a file cannot be written
	 */
	private static Path configureExtraRoot(CodexParentSession session) throws IOException
	{
		Path extra = session.folder("other/extra");
		session.write("home/.codex/config.toml", """
			sandbox_mode = "workspace-write"
			[sandbox_workspace_write]
			network_access = true
			writable_roots = [%s]
			""".formatted(toml(extra)));
		return extra;
	}

	/**
	 * Returns the {@code permission_profile} entries of a turn that may read everything and write the project.
	 *
	 * @param session the session
	 * @return the entries, as JSON array elements
	 */
	private static String projectOnlyEntries(CodexParentSession session)
	{
		return """
			{"path":{"type":"special","value":{"kind":"root"}},"access":"read"},
			{"path":{"type":"path","path":%s},"access":"write"}""".formatted(json(session.project()));
	}

	/**
	 * Returns a rollout's {@code turn_context} record.
	 *
	 * @param session the session
	 * @param entries the {@code permission_profile} entries, as JSON array elements
	 * @return the record and its line terminator
	 */
	private static String turnContext(CodexParentSession session, String entries)
	{
		return """
			{"timestamp":"2026-11-05T12:00:01.000Z","type":"turn_context","payload":{"turn_id":"t1","cwd":%s,\
			"workspace_roots":[%s],"approval_policy":"never","sandbox_policy":{"type":"workspace-write",\
			"network_access":true,"exclude_tmpdir_env_var":false,"exclude_slash_tmp":false},\
			"permission_profile":{"type":"managed","file_system":{"type":"restricted","entries":[%s]},\
			"network":"enabled"},"model":"gpt-6.1-sol"}}
			""".formatted(json(session.project()), json(session.project()), entries.replace("\n", ""));
	}
}
