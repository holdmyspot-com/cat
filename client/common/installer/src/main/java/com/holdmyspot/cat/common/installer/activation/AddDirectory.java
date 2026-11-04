package com.holdmyspot.cat.common.installer.activation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;

/**
 * Adds a directory, holding a copy of the validated release, where the harness looks for CAT's versions.
 * <p>
 * <b>Effect:</b> the core stages a copy of the validated release at {@code <stagingParent>/.cat-staging-<random>} and
 * renames it to {@code target}. The step fails, changing nothing, when {@code target} already exists.
 * <p>
 * <b>Undo:</b> the core deletes {@code target}.
 * <p>
 * A provider names a {@code stagingParent} outside the directory that its harness scans, when the harness would load
 * a staged copy found there. {@code stagingParent} must lie on the same file system as {@code target}, so that the
 * rename is atomic.
 *
 * @param target        the directory to create, which does not exist before the step runs
 * @param stagingParent the directory that holds this step's staging copy, on the same file system as {@code target}
 */
public record AddDirectory(Path target, Path stagingParent) implements ActivationStep
{
	/**
	 * Creates a step.
	 *
	 * @param target        the directory to create, which does not exist before the step runs
	 * @param stagingParent the directory that holds this step's staging copy, on the same file system as
	 *                      {@code target}
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if either path is relative, contains a dot or dot-dot name, or is a
	 *                                  root, or {@code stagingParent} lies inside {@code target}
	 */
	public AddDirectory
	{
		ActivationPaths.requireAbsoluteNormalized(target, "target");
		ActivationPaths.requireAbsoluteNormalized(stagingParent, "stagingParent");
		requireThat(stagingParent.startsWith(target), "stagingParent.startsWith(target)").isFalse();
	}
}
