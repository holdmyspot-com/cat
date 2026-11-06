package com.holdmyspot.cat.codex.policy;

import java.nio.file.Path;
import java.util.Map;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * What a command that Codex runs learns about the Codex session that runs it.
 *
 * @param environment the command's environment variables, which it inherits from Codex
 * @param accountHome the user's home directory in the account database, which Codex reads when the environment names
 *                    none
 * @param cwd         the session's working directory, from the thread's rollout
 * @param rollout     the rollout file of the thread that runs the command
 */
public record CodexSession(Map<String, String> environment, Path accountHome, Path cwd, Path rollout)
{
	/**
	 * Creates a session.
	 *
	 * @param environment the command's environment variables, which it inherits from Codex
	 * @param accountHome the user's home directory in the account database, which Codex reads when the environment
	 *                    names none
	 * @param cwd         the session's working directory, from the thread's rollout
	 * @param rollout     the rollout file of the thread that runs the command
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if a path is relative
	 */
	public CodexSession
	{
		environment = Map.copyOf(environment);
		requireThat(accountHome, "accountHome").isAbsolute();
		requireThat(cwd, "cwd").isAbsolute();
		requireThat(rollout, "rollout").isAbsolute();
	}
}
