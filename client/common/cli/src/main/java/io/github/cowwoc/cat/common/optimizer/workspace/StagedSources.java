package io.github.cowwoc.cat.common.optimizer.workspace;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds and deletes the staged source of each candidate: the directory that every trial session copies.
 * <p>
 * A staged source holds the fork's recorded files, including the participating ignored and external files and the
 * trial fixtures, with the candidate's overlay applied. Every symbolic link is replaced by a copy of its resolved
 * target, so a trial write through a link reaches only the trial's copy. CAT's work directory is excluded. A staged
 * source is a cache that is rebuilt from the overlay when needed.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe. Concurrent requests for one candidate share one staged source.
 */
public final class StagedSources
{
	private final ForkTree tree;
	private final Path storage;

	/**
	 * Creates the staged sources of a fork.
	 *
	 * @param tree the fork's files
	 * @param storage the directory that holds the staged sources, which this class creates if needed
	 * @throws NullPointerException if any of the arguments are null
	 */
	public StagedSources(ForkTree tree, Path storage)
	{
		requireThat(tree, "tree").isNotNull();
		requireThat(storage, "storage").isNotNull();
		this.tree = tree;
		this.storage = storage.toAbsolutePath().normalize();
	}

	/**
	 * Returns a staged source for a candidate, building it if it does not exist.
	 *
	 * @param candidate the candidate's overlay on the fork
	 * @return the directory that trial sessions copy, named by the candidate's identity
	 * @throws NullPointerException if {@code candidate} is null
	 * @throws IllegalArgumentException if {@code candidate} is an overlay on a different fork
	 * @throws WorkspaceException if a symbolic link forms a cycle or leads to a missing target
	 * @throws IOException if the source cannot be built
	 */
	public Path stage(Overlay candidate) throws IOException
	{
		requireThat(candidate, "candidate").isNotNull();
		tree.requireSameFork(candidate);
		Path directory = storage.resolve(candidate.identity().hex());
		if (Files.isDirectory(directory))
			return directory;
		Dereferenced files = Dereferenced.of(candidate.snapshot());
		files.requireResolvable();
		Files.createDirectories(storage);
		// Build beside the final directory and rename, so that no reader sees a half-built source
		Path partial = Files.createTempDirectory(storage, "partial-");
		try
		{
			files.writeTo(tree, candidate, partial);
			try
			{
				Files.move(partial, directory, ATOMIC_MOVE);
			}
			catch (FileSystemException e)
			{
				// A concurrent request built the same source first if the directory exists now
				if (!Files.isDirectory(directory))
					throw e;
			}
		}
		finally
		{
			TemporaryDirectories.deleteRecursively(partial);
		}
		return directory;
	}

	/**
	 * Deletes a candidate's staged source. Deleting a source that does not exist does nothing.
	 *
	 * @param candidate the candidate's identity
	 * @throws NullPointerException if {@code candidate} is null
	 * @throws IOException if the source cannot be deleted
	 */
	public void delete(Sha256 candidate) throws IOException
	{
		requireThat(candidate, "candidate").isNotNull();
		TemporaryDirectories.deleteRecursively(storage.resolve(candidate.hex()));
	}
}
