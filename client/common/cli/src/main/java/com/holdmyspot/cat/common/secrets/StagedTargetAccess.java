package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.optimizer.execution.SnapshotMismatchException;
import com.holdmyspot.cat.common.optimizer.execution.StagedTarget;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * Exposes the attribute-read failure classification used by {@link StagedTarget}.
 */
@FunctionalInterface
public interface StagedTargetAccess
{
	/**
	 * Classifies a missing target as an integrity mismatch and preserves every other attribute-read failure.
	 *
	 * @param file    the absolute staged target path
	 * @param failure the failure from reading the target's attributes
	 * @return a {@link SnapshotMismatchException} retaining a {@link NoSuchFileException} cause, or the unchanged
	 *         failure otherwise
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code file} is relative
	 */
	IOException classifyReadFailure(Path file, IOException failure);
}
