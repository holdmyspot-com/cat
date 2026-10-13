package io.github.cowwoc.cat.common.optimizer.inventory;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;

import java.nio.file.Path;

/**
 * The source that control trials run on: the incumbent with the target prompt file emptied to zero bytes. The file
 * still exists but gives no instruction, and every other file of the prompt system stays as the incumbent has it, so
 * control trials measure the agent that the user would have without the target. The source depends only on the fork and
 * the target, so it is the same for every candidate.
 * <p>
 * <b>Thread Safety:</b> This class is stateless and thread-safe.
 */
public final class ControlSource
{
	/**
	 * The content of an emptied prompt file.
	 */
	private static final FileContent EMPTY = new FileContent(new byte[0]);

	/**
	 * Prevents instantiation.
	 */
	private ControlSource()
	{
	}

	/**
	 * Returns the control source.
	 *
	 * @param fork   the files recorded at the fork
	 * @param target the target prompt file, relative to the project directory
	 * @return the overlay that empties the target prompt file
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if the fork does not record {@code target} as a regular file
	 */
	public static Overlay of(FileInventory fork, Path target)
	{
		requireThat(fork, "fork").isNotNull();
		requireThat(target, "target").isNotNull();
		if (!(fork.entry(target).orElse(null) instanceof RegularFile file))
			throw new IllegalArgumentException("The fork does not record the target prompt file " + target);
		return Overlay.of(fork).write(target, EMPTY, file.permissions());
	}
}
