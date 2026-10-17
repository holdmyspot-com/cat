package io.github.cowwoc.cat.claude;

import io.github.cowwoc.cat.common.SessionHarness;
import java.nio.file.Path;
import java.util.List;

/**
 * Supplies the Claude Code parts of a CAT session directory.
 */
public final class ClaudeSessionHarness
{
	/**
	 * The Claude Code session harness. Claude Code gives its tool shells the session's ID in
	 * {@code CLAUDE_CODE_SESSION_ID}, keeps its configuration in {@code CLAUDE_CONFIG_DIR} or else {@code ~/.claude},
	 * and keeps a session's transcript in {@code projects/<project>/<session-id>.jsonl} there.
	 */
	public static final SessionHarness HARNESS = new SessionHarness(ClaudePluginInfo.HARNESS_ID,
		"CLAUDE_CODE_SESSION_ID", "CLAUDE_CONFIG_DIR", Path.of(".claude"),
		List.of("projects/*/" + SessionHarness.SESSION_ID + ".jsonl"));

	/**
	 * Returns the Claude Code session harness to a {@link java.util.ServiceLoader}.
	 *
	 * @return {@link #HARNESS}
	 */
	public static SessionHarness provider()
	{
		return HARNESS;
	}

	/**
	 * Prevents instantiation.
	 */
	private ClaudeSessionHarness()
	{
	}
}
