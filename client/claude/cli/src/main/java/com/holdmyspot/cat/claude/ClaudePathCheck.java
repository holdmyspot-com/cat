package com.holdmyspot.cat.claude;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.policy.ClaudePolicyReader;
import com.holdmyspot.cat.claude.policy.ClaudePolicyReading;
import com.holdmyspot.cat.claude.policy.ClaudeSession;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.WritablePathEntries;
import com.holdmyspot.cat.common.WritablePathEntries.Writable;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Checks the folders on {@code PATH} against the sandbox of the Claude Code session that runs a hook.
 */
final class ClaudePathCheck
{
	/**
	 * Prevents instantiation.
	 */
	private ClaudePathCheck()
	{
	}

	/**
	 * Returns the notice that names the folders on {@code PATH} that the session's sandbox lets its commands write.
	 * A policy that CAT cannot read safely gives no notice for the user: the reason goes to {@code error}, because the
	 * session's rules do not depend on it.
	 *
	 * @param environment the hook's environment variables
	 * @param projectDir  the session's project folder
	 * @param processes   describes the session's Claude Code process
	 * @param error       the hook's diagnostic output
	 * @return the notice, or empty if no folder on {@code PATH} is writable, the session has no sandbox, or the policy
	 *   cannot be read
	 * @throws NullPointerException if any argument is null
	 */
	static Optional<String> notice(Map<String, String> environment, Path projectDir, HarnessProcesses processes,
		PrintStream error)
	{
		requireThat(environment, "environment").isNotNull();
		requireThat(projectDir, "projectDir").isNotNull();
		requireThat(processes, "processes").isNotNull();
		requireThat(error, "error").isNotNull();
		try
		{
			Path userHome = Platform.current().homeDirectory(environment);
			ClaudeSession session = ClaudeSession.fromHookEnvironment(environment, userHome,
				System.getProperty("user.name", ""));
			ClaudePolicyReading reading = new ClaudePolicyReader(processes).read(session);
			List<Writable> writable = WritablePathEntries.find(WritablePathEntries.searchPath(environment),
				projectDir, reading.policy().writes(reading.writeSettings()));
			if (writable.isEmpty())
				return Optional.empty();
			return Optional.of(WritablePathEntries.notice(ClaudePolicyReader.HARNESS_NAME, writable));
		}
		catch (PolicyRefusal e)
		{
			error.println("CAT SessionStart: could not check the folders on PATH against the sandbox: " +
				e.messageFor(ClaudePolicyReader.HARNESS_NAME));
			return Optional.empty();
		}
		catch (IOException e)
		{
			error.println("CAT SessionStart: could not check the folders on PATH against the sandbox: " +
				e.getMessage());
			return Optional.empty();
		}
	}
}
