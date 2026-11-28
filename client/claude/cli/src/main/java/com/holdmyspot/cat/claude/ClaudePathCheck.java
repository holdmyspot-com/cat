package com.holdmyspot.cat.claude;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.installer.ClaudeWriteGrants;
import com.holdmyspot.cat.claude.policy.ClaudePolicyReader;
import com.holdmyspot.cat.claude.policy.ClaudeSession;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.SandboxWrites;
import com.holdmyspot.cat.common.WritablePathEntries;
import com.holdmyspot.cat.common.WritablePathEntries.Writable;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Checks the folders on {@code PATH} against the sandbox of the Claude Code session that runs a hook.
 * <p>
 * The check overestimates: it reads every setting that may apply to the session, reports a grant that it cannot
 * interpret for certain as possibly writable, and tells the user what it could not read.
 */
final class ClaudePathCheck
{
	/**
	 * The start of every line that tells the user what the check could not read.
	 */
	private static final String UNREAD = "CAT could not read the sandbox of this session exactly, so it checked every " +
		"setting that may apply: ";

	/**
	 * Prevents instantiation.
	 */
	private ClaudePathCheck()
	{
	}

	/**
	 * Returns the notice that names the folders on {@code PATH} that the session's sandbox lets, or may let, its
	 * commands write, and the sandbox settings that the check could not read. Each such failure also goes to
	 * {@code error}.
	 *
	 * @param environment the hook's environment variables
	 * @param projectDir  the session's project folder
	 * @param processes   describes the session's Claude Code process
	 * @param error       the hook's diagnostic output
	 * @return the notice, or empty if no folder on {@code PATH} is writable or possibly writable, nothing could not be
	 *   read, and the session has no sandbox
	 * @throws NullPointerException if any argument is null
	 */
	static Optional<String> notice(Map<String, String> environment, Path projectDir, HarnessProcesses processes,
		PrintStream error)
	{
		requireThat(environment, "environment").isNotNull();
		requireThat(projectDir, "projectDir").isNotNull();
		requireThat(processes, "processes").isNotNull();
		requireThat(error, "error").isNotNull();
		List<String> problems = new ArrayList<>();
		List<Writable> writable = List.of();
		try
		{
			Platform platform = Platform.current();
			Path userHome = platform.homeDirectory(environment);
			Path configuration = userHome.resolve(".claude");
			try
			{
				configuration = ClaudeSession.configurationDirectory(environment, userHome);
			}
			catch (PolicyRefusal e)
			{
				problems.add(UNREAD + e.messageFor(ClaudePolicyReader.HARNESS_NAME));
			}
			Optional<ClaudeWriteGrants.Launch> launch = Optional.empty();
			try
			{
				ClaudeSession session = ClaudeSession.fromHookEnvironment(environment, userHome,
					System.getProperty("user.name", ""));
				launch = Optional.of(new ClaudePolicyReader(processes, Platform.current()).readLaunch(session));
			}
			catch (PolicyRefusal e)
			{
				problems.add(UNREAD + e.messageFor(ClaudePolicyReader.HARNESS_NAME));
			}
			Optional<Path> temporary = Optional.empty();
			String named = environment.get(ClaudeWriteGrants.TEMPORARY_DIRECTORY_VARIABLE);
			if (named != null && !named.isBlank())
			{
				try
				{
					Path path = Path.of(named);
					if (path.isAbsolute())
						temporary = Optional.of(path);
				}
				catch (InvalidPathException _)
				{
					// The release does not use a value that is not a path
				}
			}
			SandboxWrites writes = ClaudeWriteGrants.read(new ClaudeWriteGrants.Session(userHome, configuration,
				List.of(projectDir), true, temporary, ClaudeWriteGrants.managedDirectories(platform),
				ClaudeWriteGrants.unreadableManagedSources(platform, System.getProperty("user.name", "")), launch));
			problems.addAll(writes.problems());
			writable = WritablePathEntries.find(WritablePathEntries.searchPath(environment), projectDir, writes);
		}
		catch (IOException | IllegalArgumentException e)
		{
			problems.add(UNREAD + e.getMessage());
		}
		for (String problem : problems)
			error.println("CAT SessionStart: could not check the folders on PATH against the sandbox: " + problem);
		if (writable.isEmpty() && problems.isEmpty())
			return Optional.empty();
		return Optional.of(WritablePathEntries.notice(ClaudePolicyReader.HARNESS_NAME, writable, problems));
	}
}
