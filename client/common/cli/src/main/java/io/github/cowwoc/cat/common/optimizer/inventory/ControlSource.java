package io.github.cowwoc.cat.common.optimizer.inventory;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;

import java.nio.file.Path;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The source that control trials run on: the incumbent with every target prompt file emptied to zero bytes. The files
 * still exist but give no instruction. The source depends only on the fork and the {@link Authority}, so it is the same
 * for every candidate.
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
	 * Returns the target prompt files: the regular files inside the project whose content the contract lets a
	 * candidate edit. A file that a prompt-source link reaches is listed at its own path, not at the link's.
	 *
	 * @param fork      the files recorded at the fork
	 * @param authority the edits that the contract authorizes
	 * @return the paths relative to the project directory, in path order
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static SortedSet<Path> promptFiles(FileInventory fork, Authority authority)
	{
		requireThat(fork, "fork").isNotNull();
		requireThat(authority, "authority").isNotNull();
		SortedSet<Path> promptFiles = new TreeSet<>();
		for (Entry<Path, FileEntry> entry : fork.entries().entrySet())
		{
			Path path = entry.getKey();
			if (entry.getValue() instanceof RegularFile && !Authority.isExternal(path) &&
				fork.resolve(path).equals(Optional.of(path)) &&
				authority.limitOf(Operation.EDIT_CONTENT, path).isEmpty())
			{
				promptFiles.add(path);
			}
		}
		return promptFiles;
	}

	/**
	 * Returns the control source.
	 *
	 * @param fork      the files recorded at the fork
	 * @param authority the edits that the contract authorizes
	 * @return the overlay that empties every target prompt file
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static Overlay of(FileInventory fork, Authority authority)
	{
		Overlay control = Overlay.of(fork);
		for (Path path : promptFiles(fork, authority))
		{
			RegularFile file = (RegularFile) fork.entry(path).orElseThrow();
			control = control.write(path, EMPTY, file.permissions());
		}
		return control;
	}
}
