package com.holdmyspot.cat.common.updater.activation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;

/**
 * Replaces a directory that the harness loads CAT from with a copy of the validated release.
 * <p>
 * <b>Effect:</b> the core stages a copy of the validated release at {@code <slotParent>/.cat-staging-<random>}. It
 * deletes an older {@code <slotParent>/.cat-previous-<live name>} left by an earlier activation, renames {@code live}
 * to that previous-installation slot, and then renames the staged copy to {@code live}.
 * <p>
 * <b>Undo:</b> the core renames {@code live} to a discard name in {@code slotParent}, deletes it, and renames the
 * previous-installation slot back to {@code live}.
 * <p>
 * {@code slotParent} usually equals {@code live}'s parent. A provider whose harness scans the siblings of
 * {@code live}, and would load the staged copy or the previous installation as another version, names a directory
 * outside the scanned one instead. Either way, {@code slotParent} must lie on the same file system as {@code live}, so
 * that both renames are atomic.
 *
 * @param live       the directory that the harness loads CAT from, which exists before the step runs
 * @param slotParent the directory that holds this step's staging copy and previous-installation slot, on the same file
 *                   system as {@code live}
 */
public record ReplaceDirectory(Path live, Path slotParent) implements ActivationStep
{
	/**
	 * Creates a step.
	 *
	 * @param live       the directory that the harness loads CAT from, which exists before the step runs
	 * @param slotParent the directory that holds this step's staging copy and previous-installation slot, on the same
	 *                   file system as {@code live}
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if either path is relative, contains a dot or dot-dot name, or is a
	 *                                  root, or {@code slotParent} lies inside {@code live}
	 */
	public ReplaceDirectory
	{
		ActivationPaths.requireAbsoluteNormalized(live, "live");
		ActivationPaths.requireAbsoluteNormalized(slotParent, "slotParent");
		requireThat(slotParent.startsWith(live), "slotParent.startsWith(live)").isFalse();
	}
}
