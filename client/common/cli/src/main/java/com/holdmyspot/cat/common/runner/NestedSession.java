package com.holdmyspot.cat.common.runner;

import java.nio.file.Path;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Identifies the isolated directory and generated identifier for one harness execution.
 *
 * @param harness the harness name
 *
 * @param id the generated slot ID
 *
 * @param directory the owned session folder
 */
public record NestedSession(String harness, String id, Path directory)
{
	/**
	 * The name of the directory, in a nested session's {@code artifacts} directory, that holds the copies of the
	 * harness's native session transcripts. A nested session has it only if its caller asked for session transcripts.
	 */
	public static final String SESSION_TRANSCRIPTS = "session-transcripts";


	/**
	 * Validates the owned nested session identity.
	 *
	 * @param harness the harness name
	 *
	 * @param id the nested session's identifier: a generated or slot identifier, or for a results copy the identifier
	 *           that the harness gave its session
	 *
	 * @param directory the owned session folder
	 *
	 * @throws NullPointerException if {@code harness}, {@code id}, or {@code directory} are null
	 *
	 * @throws IllegalArgumentException if {@code harness} or {@code id} are blank
	 */
	public NestedSession
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(id, "id").isNotBlank();
		requireThat(directory, "directory").isNotNull();
		if (!harness.matches("[a-z0-9]+") || !NestedSessionManager.HARNESS_SESSION_ID.matcher(id).matches())
			throw new IllegalArgumentException("harness and id must be runner or harness session identifiers");
	}

	/**
	 * Returns the directory that holds the copies of the harness's native session transcripts, whether or not the nested
	 * session has it.
	 *
	 * @return the directory
	 */
	public Path sessionTranscripts()
	{
		return directory.resolve("artifacts").resolve(SESSION_TRANSCRIPTS);
	}
}
