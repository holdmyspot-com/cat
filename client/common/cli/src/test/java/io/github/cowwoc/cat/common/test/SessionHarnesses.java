package io.github.cowwoc.cat.common.test;

import io.github.cowwoc.cat.common.SessionHarness;
import java.nio.file.Path;
import java.util.List;

/**
 * Harness values that model the two transcript layouts that a CAT session directory's sweep handles: one transcript
 * file in any project directory, and transcripts in nested or archived directories.
 */
public final class SessionHarnesses
{
	/**
	 * A harness that keeps each transcript in one directory per project.
	 */
	public static final SessionHarness HARNESS_A = new SessionHarness("harness-a", "HARNESS_A_SESSION",
		"HARNESS_A_CONFIGURATION", Path.of(".harness-a"), List.of("projects/*/" + SessionHarness.SESSION_ID + ".jsonl"));
	/**
	 * A harness that keeps transcripts in dated directories and moves archived ones to a flat directory.
	 */
	public static final SessionHarness HARNESS_B = new SessionHarness("harness-b", "HARNESS_B_SESSION",
		"HARNESS_B_CONFIGURATION", Path.of(".harness-b"),
		List.of("sessions/**/rollout-*-" + SessionHarness.SESSION_ID + ".jsonl",
			"archived_sessions/rollout-*-" + SessionHarness.SESSION_ID + ".jsonl"));

	/**
	 * Prevents instantiation.
	 */
	private SessionHarnesses()
	{
	}
}
