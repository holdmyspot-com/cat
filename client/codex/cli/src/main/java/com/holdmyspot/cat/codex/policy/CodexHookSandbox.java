package com.holdmyspot.cat.codex.policy;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import java.nio.file.Path;
import java.util.List;

/**
 * What a Codex hook needs to read the sandbox of the session that runs it.
 *
 * @param ancestors   the processes that started the hook, the nearest first
 * @param reader      reads the session's sandbox policy
 * @param accountHome the user's home directory in the account database, which Codex reads when the environment names
 *                    none
 * @param platform    the platform that the session runs on
 */
public record CodexHookSandbox(List<ProcessDescription> ancestors, CodexPolicyReader reader, Path accountHome,
	Platform platform)
{
	/**
	 * Creates the sandbox source.
	 *
	 * @param ancestors   the processes that started the hook, the nearest first
	 * @param reader      reads the session's sandbox policy
	 * @param accountHome the user's home directory in the account database
	 * @param platform    the platform that the session runs on
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code accountHome} is relative
	 */
	public CodexHookSandbox
	{
		ancestors = List.copyOf(ancestors);
		requireThat(reader, "reader").isNotNull();
		requireThat(accountHome, "accountHome").isAbsolute();
		requireThat(platform, "platform").isNotNull();
	}

	/**
	 * Returns the source for the hook that this process runs, on this machine.
	 *
	 * @return the source
	 */
	public static CodexHookSandbox ofThisProcess()
	{
		return new CodexHookSandbox(HarnessProcesses.ancestorsOf(ProcessHandle.current()),
			new CodexPolicyReader(CodexSystemFiles.ofThisMachine(System.getProperty("user.name", ""))),
			Path.of(System.getProperty("user.home")).toAbsolutePath(), Platform.current());
	}
}
