package com.holdmyspot.cat.common.runner;

import java.nio.file.Path;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Identifies the isolated directory and generated identifier for one harness execution.
 *
 * @param harness the harness name
 *
 * @param id the generated run identifier
 *
 * @param directory the owned run directory
 */
public record HarnessRun(String harness, String id, Path directory)
{
	/**
	 * The name of the directory, in a run's {@code artifacts} directory, that holds the copies of the harness's native
	 * session transcripts. A run has it only if its caller asked for session transcripts.
	 */
	public static final String SESSION_TRANSCRIPTS = "session-transcripts";


	/**
	 * Validates the owned run identity.
	 *
	 * @param harness the harness name
	 *
	 * @param id the generated run identifier
	 *
	 * @param directory the owned run directory
	 *
	 * @throws NullPointerException if {@code harness}, {@code id}, or {@code directory} are null
	 *
	 * @throws IllegalArgumentException if {@code harness} or {@code id} are blank
	 */
	public HarnessRun
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(id, "id").isNotBlank();
		requireThat(directory, "directory").isNotNull();
		if (!harness.matches("[a-z0-9]+") || !id.matches("[a-z0-9]+-[a-z0-9]+"))
			throw new IllegalArgumentException("harness and id must be generated runner identifiers");
	}

	/**
	 * Returns the directory that holds the copies of the harness's native session transcripts, whether or not the run
	 * has it.
	 *
	 * @return the directory
	 */
	public Path sessionTranscripts()
	{
		return directory.resolve("artifacts").resolve(SESSION_TRANSCRIPTS);
	}
}
