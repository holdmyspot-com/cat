package com.holdmyspot.cat.codex.policy;

import com.holdmyspot.cat.codex.layout.CodexInstallation;
import com.holdmyspot.cat.codex.optimizer.RolloutInterruptSignal;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.ParentPolicySource;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Reads the sandbox policy of the Codex thread whose command runs a runner of CAT's Codex plugin.
 * <p>
 * Codex sets {@value #THREAD_VARIABLE} in every command that it runs. The command's thread has the policy that
 * {@link CodexPolicyReader} reads from Codex's configuration and the rollout of that thread, which Codex keeps under
 * its home's {@code sessions} folder; the rollout also names the session's working directory. A command that Codex
 * runs with its sandbox off has the unsandboxed policy that the configuration or the rollout records. A command that
 * Claude Code runs is refused, since only CAT's Claude Code plugin reads a Claude Code session's policy. A command that
 * no harness runs is unsandboxed.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class CodexParentPolicy implements ParentPolicySource
{
	/**
	 * The environment variable that names the thread that runs a command that Codex runs.
	 */
	public static final String THREAD_VARIABLE = "CODEX_THREAD_ID";
	/**
	 * The environment variable that Claude Code sets to {@code 1} in every command that it runs.
	 */
	private static final String CLAUDE_CODE_VARIABLE = "CLAUDECODE";
	/**
	 * The form of a Codex thread identifier.
	 */
	private static final Pattern THREAD_ID = Pattern.compile(
		"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
	/**
	 * The fix of a refusal that the thread's rollout causes.
	 */
	private static final String ROLLOUT_FIX = "Start a new Codex session";
	private final CodexPolicyReader reader;
	private final Map<String, String> environment;
	private final Path accountHome;
	private final Path workingDirectory;

	/**
	 * Creates a source.
	 *
	 * @param reader           reads a thread's policy
	 * @param environment      the environment of the command that runs the runner
	 * @param accountHome      the user's home directory in the account database, which Codex reads when the
	 *                         environment names none
	 * @param workingDirectory the command's absolute working directory, which a relative {@code CODEX_HOME} is
	 *                         relative to
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if a path is not absolute
	 */
	public CodexParentPolicy(CodexPolicyReader reader, Map<String, String> environment, Path accountHome,
		Path workingDirectory)
	{
		requireThat(reader, "reader").isNotNull();
		requireThat(accountHome, "accountHome").isAbsolute();
		requireThat(workingDirectory, "workingDirectory").isAbsolute();
		this.reader = reader;
		this.environment = Map.copyOf(environment);
		this.accountHome = accountHome;
		this.workingDirectory = workingDirectory;
	}

	@Override
	public String harnessName()
	{
		return "Codex";
	}

	@Override
	public String permissionEntries(List<Path> writes, Set<String> hosts)
	{
		requireThat(writes, "writes").isNotNull();
		requireThat(hosts, "hosts").isNotNull();
		if (writes.isEmpty() && hosts.isEmpty())
			throw new IllegalArgumentException("writes and hosts may not both be empty");
		List<String> additions = new ArrayList<>();
		if (!writes.isEmpty())
		{
			List<String> entries = new ArrayList<>();
			for (Path write : writes)
				entries.add("\"" + write + "\" = \"write\"");
			additions.add(String.join(" and ", entries) + " under [permissions.<profile>.filesystem]");
		}
		if (!hosts.isEmpty())
		{
			List<String> entries = new ArrayList<>();
			for (String host : new TreeSet<>(hosts))
				entries.add("\"" + host + "\" = \"allow\"");
			additions.add(String.join(" and ", entries) + " under [permissions.<profile>.network.domains]");
		}
		String instruction = "add " + String.join(" and ", additions) + " in Codex's config.toml, where " +
			"<profile> is the permissions profile that default_permissions names";
		if (!writes.isEmpty())
		{
			instruction += "; a session that sets sandbox_mode instead lists the paths in " +
				"sandbox_workspace_write.writable_roots";
		}
		return instruction;
	}

	/**
	 * {@inheritDoc}
	 *
	 * @throws SessionRefusedException if Claude Code runs the command
	 */
	@Override
	public ParentPolicy read() throws IOException, PolicyRefusal
	{
		String thread = environment.get(THREAD_VARIABLE);
		if (thread == null || thread.isBlank())
		{
			if ("1".equals(environment.get(CLAUDE_CODE_VARIABLE)))
			{
				throw new SessionRefusedException("CAT cannot start the session: Claude Code runs this command, and " +
					"CAT's Codex plugin cannot read a Claude Code session's sandbox policy. Run the runner of CAT's " +
					"Claude Code plugin instead.");
			}
			return ParentPolicy.UNSANDBOXED;
		}
		String threadId = thread.strip();
		if (!THREAD_ID.matcher(threadId).matches())
		{
			throw new PolicyRefusal("the command environment's " + THREAD_VARIABLE + " \"" + thread + "\" is not a " +
				"Codex thread identifier", ROLLOUT_FIX);
		}
		Path codexHome = CodexInstallation.resolveHome(environment, accountHome, workingDirectory);
		Optional<Path> rollout = RolloutInterruptSignal.locate(codexHome, threadId);
		if (rollout.isEmpty())
		{
			throw new PolicyRefusal("Codex keeps no rollout of the thread " + threadId + " under " +
				codexHome.resolve("sessions"), ROLLOUT_FIX);
		}
		Path file = rollout.get().toAbsolutePath();
		return reader.read(new CodexSession(environment, accountHome, CodexRollout.cwd(file), file));
	}
}
