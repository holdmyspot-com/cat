package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;

/**
 * Where a judged trial session's harness events and file changes were retained. Any path may no longer exist when it
 * is read.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param transcript the file that holds the trial session's harness events
 * @param effects the directory that holds the files that the trial session changed
 * @param initialProject the directory that holds the trial session's project as it was before the first turn
 */
public record TrialFiles(Path transcript, Path effects, Path initialProject)
{
	/**
	 * Creates the paths.
	 *
	 * @param transcript the file that holds the trial session's harness events
	 * @param effects the directory that holds the files that the trial session changed
	 * @param initialProject the directory that holds the trial session's project as it was before the first turn
	 * @throws NullPointerException if any of the arguments are null
	 */
	public TrialFiles
	{
		requireThat(transcript, "transcript").isNotNull();
		requireThat(effects, "effects").isNotNull();
		requireThat(initialProject, "initialProject").isNotNull();
	}
}
