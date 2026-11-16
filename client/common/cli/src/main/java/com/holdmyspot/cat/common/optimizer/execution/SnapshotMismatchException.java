package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Reports that a staged target prompt does not match its frozen snapshot.
 */
public final class SnapshotMismatchException extends IOException
{
	private static final long serialVersionUID = 1L;

	/**
	 * Creates an integrity failure for the staged target.
	 *
	 * @param file   the absolute staged target path
	 * @param reason the established inconsistency
	 * @param cause  the underlying failure, or {@code null} if none occurred
	 * @throws NullPointerException     if {@code file} or {@code reason} are null
	 * @throws IllegalArgumentException if {@code file} is relative or {@code reason} is blank
	 */
	public SnapshotMismatchException(Path file, String reason, Throwable cause)
	{
		super("Staged target differs from its snapshot at " + file.toAbsolutePath() + ": " + reason +
			". Restage the frozen snapshot before retrying.", cause);
		requireThat(file, "file").isAbsolute();
		requireThat(reason, "reason").isNotBlank();
	}
}
