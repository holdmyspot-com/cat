package io.github.cowwoc.cat.codex;

import io.github.cowwoc.cat.common.SessionHarness;
import java.nio.file.Path;
import java.util.List;

/**
 * Supplies the Codex parts of a CAT session directory.
 */
public final class CodexSessionHarness
{
	/**
	 * The Codex session harness. Codex gives each thread's tool shells the thread's ID in {@code CODEX_THREAD_ID},
	 * keeps its configuration in {@code CODEX_HOME} or else {@code ~/.codex}, and keeps a thread's transcript in
	 * {@code sessions/<date directories>/rollout-<time>-<thread-id>.jsonl} there, or in {@code archived_sessions/} once
	 * the thread is archived; an archived thread can be unarchived.
	 */
	public static final SessionHarness HARNESS = new SessionHarness(CodexPluginInfo.HARNESS_ID, "CODEX_THREAD_ID",
		"CODEX_HOME", Path.of(".codex"), List.of("sessions/**/rollout-*-" + SessionHarness.SESSION_ID + ".jsonl",
		"archived_sessions/rollout-*-" + SessionHarness.SESSION_ID + ".jsonl"));

	/**
	 * Returns the Codex session harness to a {@link java.util.ServiceLoader}.
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
	private CodexSessionHarness()
	{
	}
}
