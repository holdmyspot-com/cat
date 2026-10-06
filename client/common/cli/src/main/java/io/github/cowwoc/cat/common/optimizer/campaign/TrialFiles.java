package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;

/**
 * Where a judged trial session's harness events and file changes were retained. Either path may no longer exist when
 * it is read.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param transcript the file that holds the trial session's harness events
 * @param effects the directory that holds the files that the trial session changed
 */
public record TrialFiles(Path transcript, Path effects)
{
	/**
	 * Creates the paths.
	 *
	 * @param transcript the file that holds the trial session's harness events
	 * @param effects the directory that holds the files that the trial session changed
	 * @throws NullPointerException if any of the arguments are null
	 */
	public TrialFiles
	{
		requireThat(transcript, "transcript").isNotNull();
		requireThat(effects, "effects").isNotNull();
	}
}
