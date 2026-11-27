package com.holdmyspot.cat.codex;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.policy.CodexHookSandbox;
import com.holdmyspot.cat.codex.policy.CodexSession;
import com.holdmyspot.cat.common.SandboxWrites;
import com.holdmyspot.cat.common.WritablePathEntries;
import com.holdmyspot.cat.common.WritablePathEntries.Writable;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Checks the folders on {@code PATH} against the sandbox of the Codex session that runs a hook.
 */
final class CodexPathCheck
{
	/**
	 * The name of the harness, as the user knows it.
	 */
	private static final String HARNESS_NAME = "Codex";

	/**
	 * Prevents instantiation.
	 */
	private CodexPathCheck()
	{
	}

	/**
	 * Returns the notice that names the folders on {@code PATH} that the session's sandbox lets, or may let, its
	 * commands write. The reading overestimates: a grant that CAT cannot rule out is reported as possible. A part of the
	 * policy that CAT cannot read becomes a line of the notice that names what failed, and goes to {@code error} too,
	 * because the session's rules do not depend on it.
	 *
	 * @param event       the SessionStart input
	 * @param environment the hook's environment variables
	 * @param projectDir  the session's working directory
	 * @param sandbox     reads the session's sandbox
	 * @param error       the hook's diagnostic output
	 * @return the notice, or empty if no folder on {@code PATH} is writable and nothing could not be read
	 * @throws NullPointerException if any argument is null
	 */
	static Optional<String> notice(JsonNode event, Map<String, String> environment, Path projectDir,
		CodexHookSandbox sandbox, PrintStream error)
	{
		requireThat(event, "event").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(projectDir, "projectDir").isNotNull();
		requireThat(sandbox, "sandbox").isNotNull();
		requireThat(error, "error").isNotNull();
		Optional<Path> transcript = Optional.empty();
		String transcriptPath = event.path("transcript_path").asString("");
		if (!transcriptPath.isBlank())
		{
			try
			{
				transcript = Optional.of(Path.of(transcriptPath));
			}
			catch (InvalidPathException _)
			{
				// The session's rollout is then unknown, which the reading treats as no recorded policy
			}
		}
		List<String> problems = new ArrayList<>();
		SandboxWrites writes;
		try
		{
			CodexSession session = new CodexSession(sandbox.ancestors(), environment, sandbox.accountHome(),
				projectDir, transcript, event.path("session_id").asString(""));
			writes = sandbox.reader().readWideWrites(session, Optional.empty());
		}
		catch (IllegalArgumentException e)
		{
			problems.add("CAT could not describe this session: " + e.getMessage());
			writes = SandboxWrites.NONE;
		}
		problems.addAll(writes.problems());
		List<Writable> writable;
		try
		{
			writable = WritablePathEntries.find(WritablePathEntries.searchPath(environment), projectDir, writes);
		}
		catch (IOException e)
		{
			problems.add("CAT could not resolve the folders on PATH: " + e.getMessage());
			writable = List.of();
		}
		if (writable.isEmpty() && problems.isEmpty())
			return Optional.empty();
		for (String problem : problems)
			error.println("CAT SessionStart: could not check the folders on PATH against the sandbox: " + problem);
		return Optional.of(WritablePathEntries.notice(HARNESS_NAME, writable, problems));
	}
}
