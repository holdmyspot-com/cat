package com.holdmyspot.cat.codex;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.policy.CodexHookSandbox;
import com.holdmyspot.cat.codex.policy.CodexSession;
import com.holdmyspot.cat.common.WritablePathEntries;
import com.holdmyspot.cat.common.WritablePathEntries.Writable;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
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
	 * Returns the notice that names the folders on {@code PATH} that the session's sandbox lets its commands write.
	 * A policy that CAT cannot read safely gives no notice for the user: the reason goes to {@code error}, because the
	 * session's rules do not depend on it.
	 *
	 * @param event       the SessionStart input
	 * @param environment the hook's environment variables
	 * @param projectDir  the session's working directory
	 * @param sandbox     reads the session's sandbox
	 * @param error       the hook's diagnostic output
	 * @return the notice, or empty if no folder on {@code PATH} is writable, the session has no sandbox, or the policy
	 *   cannot be read
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
		try
		{
			Optional<Path> transcript = Optional.empty();
			String transcriptPath = event.path("transcript_path").asString("");
			if (!transcriptPath.isBlank())
				transcript = Optional.of(Path.of(transcriptPath));
			CodexSession session = new CodexSession(sandbox.ancestors(), environment, sandbox.accountHome(),
				projectDir, transcript, event.path("session_id").asString(""));
			List<Writable> writable = WritablePathEntries.find(WritablePathEntries.searchPath(environment),
				projectDir, sandbox.reader().readWrites(session, Optional.empty()));
			if (writable.isEmpty())
				return Optional.empty();
			return Optional.of(WritablePathEntries.notice(HARNESS_NAME, writable));
		}
		catch (PolicyRefusal e)
		{
			error.println("CAT SessionStart: could not check the folders on PATH against the sandbox: " +
				e.messageFor(HARNESS_NAME));
			return Optional.empty();
		}
		catch (IOException | IllegalArgumentException e)
		{
			error.println("CAT SessionStart: could not check the folders on PATH against the sandbox: " +
				e.getMessage());
			return Optional.empty();
		}
	}
}
