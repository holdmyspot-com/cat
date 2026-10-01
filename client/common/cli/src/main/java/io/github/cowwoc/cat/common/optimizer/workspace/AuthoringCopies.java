package io.github.cowwoc.cat.common.optimizer.workspace;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.InventoryScanner;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Prepares the directory that an author session edits and captures the session's delta.
 * <p>
 * An authoring copy holds the working candidate as a staged source does, with every symbolic link replaced by a copy
 * of its target. Scanning the copy before and after the session yields two inventories whose difference is the
 * session's delta. An author session that edits the runner's copy of the authoring copy leaves its edits in its
 * effects directory instead, which is scanned under the same rules.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe. Each copy is a separate directory.
 */
public final class AuthoringCopies
{
	private final ForkTree tree;
	private final Path storage;

	/**
	 * Creates the authoring copies of a fork.
	 *
	 * @param tree the fork's files
	 * @param storage the directory that holds the copies, which this class creates if needed
	 * @throws NullPointerException if any of the arguments are null
	 */
	public AuthoringCopies(ForkTree tree, Path storage)
	{
		requireThat(tree, "tree").isNotNull();
		requireThat(storage, "storage").isNotNull();
		this.tree = tree;
		this.storage = storage.toAbsolutePath().normalize();
	}

	/**
	 * Prepares a copy of the working candidate for an author session.
	 *
	 * @param working the working candidate's overlay on the fork
	 * @return the directory that the author edits
	 * @throws NullPointerException if {@code working} is null
	 * @throws IllegalArgumentException if {@code working} is an overlay on a different fork
	 * @throws WorkspaceException if a symbolic link forms a cycle or leads to a missing target
	 * @throws IOException if the copy cannot be prepared
	 */
	public Path prepare(Overlay working) throws IOException
	{
		requireThat(working, "working").isNotNull();
		tree.requireSameFork(working);
		Dereferenced files = Dereferenced.of(working.snapshot());
		files.requireResolvable();
		Files.createDirectories(storage);
		Path copy = Files.createTempDirectory(storage, "copy-");
		try
		{
			files.writeTo(tree, working, copy);
		}
		catch (IOException | RuntimeException e)
		{
			try
			{
				TemporaryDirectories.deleteRecursively(copy);
			}
			catch (IOException suppressed)
			{
				e.addSuppressed(suppressed);
			}
			throw e;
		}
		return copy;
	}

	/**
	 * Scans an authoring copy, without following links.
	 *
	 * @param copy a directory that {@link #prepare(Overlay)} returned
	 * @return the copy's inventory, rooted at {@code copy}
	 * @throws NullPointerException if {@code copy} is null
	 * @throws IllegalArgumentException if {@code copy} is not a directory that this class prepared
	 * @throws IOException if the copy cannot be read
	 */
	public FileInventory scan(Path copy) throws IOException
	{
		return InventoryScanner.scan(requireCopy(copy), Set.of());
	}

	/**
	 * Scans the effects of an author session that ran on an authoring copy, under the rules that
	 * {@link #scan(Path)} applies to the copy.
	 *
	 * @param copy a directory that {@link #prepare(Overlay)} returned, which the session's project source was
	 * @param effects the directory that holds the session's project effects
	 * @return the effects' inventory, rooted at {@code effects}
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code copy} is not a directory that this class prepared, or
	 *   {@code effects} is not a directory
	 * @throws IOException if the effects cannot be read
	 */
	public FileInventory scanEffects(Path copy, Path effects) throws IOException
	{
		requireCopy(copy);
		requireThat(effects, "effects").isNotNull();
		return InventoryScanner.scan(effects, Set.of());
	}

	/**
	 * Deletes an authoring copy. Deleting a copy that does not exist does nothing.
	 *
	 * @param copy a directory that {@link #prepare(Overlay)} returned
	 * @throws NullPointerException if {@code copy} is null
	 * @throws IllegalArgumentException if {@code copy} is not a directory that this class prepared
	 * @throws IOException if the copy cannot be deleted
	 */
	public void delete(Path copy) throws IOException
	{
		TemporaryDirectories.deleteRecursively(requireCopy(copy));
	}

	/**
	 * Requires that a path is a directory that this class prepares.
	 *
	 * @param copy the path
	 * @return the normalized path
	 */
	private Path requireCopy(Path copy)
	{
		requireThat(copy, "copy").isNotNull();
		Path normalized = copy.toAbsolutePath().normalize();
		if (!storage.equals(normalized.getParent()))
			throw new IllegalArgumentException(copy + " is not an authoring copy under " + storage + ".");
		return normalized;
	}
}
