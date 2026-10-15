package io.github.cowwoc.cat.common.optimizer.workspace;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.PortablePaths;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;

/**
 * Builds and deletes the staged source of each candidate: the directory that every trial session copies.
 * <p>
 * A staged source holds the fork's recorded files, including the participating ignored and external files and the
 * trial fixtures, with the candidate's overlay applied and the paths that the caller excludes left out. Every symbolic
 * link is replaced by a copy of its resolved target, so a trial write through a link reaches only the trial's copy.
 * CAT's work directory is excluded. A staged source is a cache that is rebuilt from the overlay when needed.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe. Concurrent requests for one candidate share one staged source.
 */
public final class StagedSources
{
	/**
	 * Separates a candidate's identity from the digest of the paths that its staged source leaves out.
	 */
	private static final String EXCLUSION_SEPARATOR = "-";
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
	 * @param excluded  the project-relative paths to leave out of the source, with everything below them
	 * @return the directory that trial sessions copy, named by the candidate's identity and the excluded paths
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code candidate} is an overlay on a different fork, or an excluded path is
	 *   absolute
	 * @throws WorkspaceException if a symbolic link forms a cycle or leads to a missing target
	 * @throws IOException if the source cannot be built
	 */
	public Path stage(Overlay candidate, Set<Path> excluded) throws IOException
	{
		requireThat(candidate, "candidate").isNotNull();
		requireThat(excluded, "excluded").isNotNull();
		tree.requireSameFork(candidate);
		for (Path path : excluded)
			requireThat(path.isAbsolute(), "path.isAbsolute()").withContext(path, "path").isFalse();
		Path directory = storage.resolve(name(candidate.identity(), excluded));
		if (Files.isDirectory(directory))
			return directory;
		Dereferenced files = Dereferenced.of(candidate.snapshot());
		files.requireResolvable();
		FileTree.build(directory, partial -> files.writeTo(tree, candidate, partial, excluded));
		return directory;
	}

	/**
	 * Returns the name of a staged source.
	 *
	 * @param candidate the candidate's identity
	 * @param excluded  the paths that the source leaves out
	 * @return the candidate's identity, followed by a digest of the excluded paths if there are any
	 */
	private static String name(Sha256 candidate, Set<Path> excluded)
	{
		if (excluded.isEmpty())
			return candidate.hex();
		StringJoiner paths = new StringJoiner("\n");
		for (Path path : new TreeSet<>(excluded))
			paths.add(PortablePaths.text(path.normalize()));
		return candidate.hex() + EXCLUSION_SEPARATOR + Sha256.of(paths.toString().getBytes(UTF_8)).hex();
	}

	/**
	 * Deletes a candidate's staged sources, whatever paths they leave out. Deleting a source that does not exist does
	 * nothing.
	 *
	 * @param candidate the candidate's identity
	 * @throws NullPointerException if {@code candidate} is null
	 * @throws IOException if a source cannot be deleted
	 */
	public void delete(Sha256 candidate) throws IOException
	{
		requireThat(candidate, "candidate").isNotNull();
		TemporaryDirectories.deleteRecursively(storage.resolve(candidate.hex()));
		if (Files.notExists(storage))
			return;
		try (DirectoryStream<Path> sources = Files.newDirectoryStream(storage,
			candidate.hex() + EXCLUSION_SEPARATOR + "*"))
		{
			for (Path source : sources)
				TemporaryDirectories.deleteRecursively(source);
		}
	}
}
