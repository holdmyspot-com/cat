package io.github.cowwoc.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Prepares the environment inherited by a sandboxed Claude process.
 */
public final class ClaudeProcessEnvironment
{
	/**
	 * Removes unavailable and duplicate path directories before Claude constructs its sandbox.
	 *
	 * <p>
	 * Claude's Bubblewrap setup masks unavailable path entries. Omitting them here preserves ordinary shell lookup
	 * behavior without requiring Bubblewrap to create a mount target outside the caller's writable directories.
	 *
	 * @param environment the mutable nested-process environment
	 */
	public static void removeUnavailablePathEntries(Map<String, String> environment)
	{
		requireThat(environment, "environment").isNotNull();
		String path = environment.get("PATH");
		if (path == null || path.isBlank())
			return;
		Set<String> availableDirectories = new LinkedHashSet<>();
		for (String entry : path.split(Pattern.quote(File.pathSeparator), -1))
		{
			if (!entry.isBlank() && Files.isDirectory(Path.of(entry)))
				availableDirectories.add(entry);
		}
		if (availableDirectories.isEmpty())
			environment.remove("PATH");
			else
				environment.put("PATH", String.join(File.pathSeparator, availableDirectories));
	}

	/**
	 * Asks Claude to print a {@code session_state_changed} line whenever its session starts or stops working.
	 * {@link ClaudeProcess} ends a turn at the {@code idle} state and fails a turn of a Claude that prints no
	 * state, so the setting overrides any value inherited from the caller.
	 *
	 * @param environment the mutable nested-process environment
	 */
	public static void requestSessionStateEvents(Map<String, String> environment)
	{
		requireThat(environment, "environment").isNotNull();
		environment.put("CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS", "1");
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private ClaudeProcessEnvironment()
	{
	}
}
