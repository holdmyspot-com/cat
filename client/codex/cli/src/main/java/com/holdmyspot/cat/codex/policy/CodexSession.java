package com.holdmyspot.cat.codex.policy;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a Codex hook learns about the Codex session that runs it.
 *
 * @param ancestors   the processes that started the hook, the nearest first
 * @param environment the hook's environment variables, which it inherits from Codex
 * @param accountHome the user's home directory in the account database, which Codex reads when the environment names
 *                    none
 * @param cwd         the session's working directory, from the hook's input
 * @param transcript  the session's rollout file, from the hook's input, or empty if the hook's input names none
 * @param sessionId   the session's identifier, from the hook's input
 */
public record CodexSession(List<CodexProcess> ancestors, Map<String, String> environment, Path accountHome, Path cwd,
	Optional<Path> transcript, String sessionId)
{
	/**
	 * Creates a session.
	 *
	 * @param ancestors   the processes that started the hook, the nearest first
	 * @param environment the hook's environment variables, which it inherits from Codex
	 * @param accountHome the user's home directory in the account database, which Codex reads when the environment
	 *                    names none
	 * @param cwd         the session's working directory, from the hook's input
	 * @param transcript  the session's rollout file, from the hook's input, or empty if the hook's input names none
	 * @param sessionId   the session's identifier, from the hook's input
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if a path is relative, or {@code sessionId} is blank
	 */
	public CodexSession
	{
		ancestors = List.copyOf(ancestors);
		environment = Map.copyOf(environment);
		requireThat(accountHome, "accountHome").isAbsolute();
		requireThat(cwd, "cwd").isAbsolute();
		requireThat(transcript, "transcript").isNotNull();
		transcript.ifPresent(file -> requireThat(file, "transcript").isAbsolute());
		requireThat(sessionId, "sessionId").isNotBlank();
	}
}
