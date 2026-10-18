package io.github.cowwoc.cat.common.optimizer.workspace;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.PortablePaths;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.port.SourceSelection;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Builds and deletes the staged source of each candidate: the directory that every trial session copies.
 * <p>
 * A staged source holds the fork's recorded files, including the participating ignored and external files, with the
 * candidate's overlay applied and the paths that the caller excludes left out. A caller can
 * also select the files that a source holds, so that a trial receives only those. Every symbolic
 * link is replaced by a copy of its resolved target, so a trial write through a link reaches only the trial's copy.
 * CAT's session directories are excluded. A staged source is a cache that is rebuilt from the overlay when needed.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe. Concurrent requests for one candidate share one staged source.
 */
public final class StagedSources
{
	/**
	 * Separates a candidate's identity from the digest of the paths that its staged source leaves out and of the
	 * files that it selects.
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
		return stage(candidate, excluded, Optional.empty());
	}

	/**
	 * Returns a staged source for a candidate that holds only some of its files, building it if it does not exist.
	 *
	 * @param candidate the candidate's overlay on the fork
	 * @param excluded  the project-relative paths to leave out of the source, with everything below them, even where
	 *                  {@code selection} holds them
	 * @param selection the files to stage
	 * @return the directory that trial sessions copy, named by the candidate's identity, the excluded paths and the
	 *   selection
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code candidate} is an overlay on a different fork, or an excluded path is
	 *   absolute
	 * @throws WorkspaceException if a symbolic link forms a cycle or leads to a missing target
	 * @throws IOException if the source cannot be built
	 */
	public Path stage(Overlay candidate, Set<Path> excluded, SourceSelection selection) throws IOException
	{
		requireThat(selection, "selection").isNotNull();
		return stage(candidate, excluded, Optional.of(selection));
	}

	/**
	 * Returns a staged source for a candidate, building it if it does not exist.
	 *
	 * @param candidate the candidate's overlay on the fork
	 * @param excluded  the project-relative paths to leave out of the source, with everything below them
	 * @param selection the files to stage, or empty to stage every file
	 * @return the directory that trial sessions copy
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code candidate} is an overlay on a different fork, or an excluded path is
	 *   absolute
	 * @throws WorkspaceException if a symbolic link forms a cycle or leads to a missing target
	 * @throws IOException if the source cannot be built
	 */
	private Path stage(Overlay candidate, Set<Path> excluded, Optional<SourceSelection> selection)
		throws IOException
	{
		requireThat(candidate, "candidate").isNotNull();
		requireThat(excluded, "excluded").isNotNull();
		tree.requireSameFork(candidate);
		for (Path path : excluded)
			requireThat(path.isAbsolute(), "path.isAbsolute()").withContext(path, "path").isFalse();
		Path directory = storage.resolve(name(candidate.identity(), excluded, selection));
		if (Files.isDirectory(directory))
			return directory;
		Dereferenced files = Dereferenced.of(candidate.snapshot());
		files.requireResolvable();
		Predicate<Path> kept = file -> excluded.stream().noneMatch(file::startsWith) &&
			selection.map(chosen -> chosen.selects(file)).orElse(true);
		FileTree.build(directory, partial -> files.writeTo(tree, candidate, partial, kept));
		return directory;
	}

	/**
	 * Returns the name of a staged source.
	 *
	 * @param candidate the candidate's identity
	 * @param excluded  the paths that the source leaves out
	 * @param selection the files that the source holds, or empty if it holds every file
	 * @return the candidate's identity, followed by a digest of the excluded paths and the selection if the source
	 *   leaves out any path or has a selection
	 */
	private static String name(Sha256 candidate, Set<Path> excluded, Optional<SourceSelection> selection)
	{
		if (excluded.isEmpty() && selection.isEmpty())
			return candidate.hex();
		StringJoiner lines = new StringJoiner("\n");
		for (Path path : new TreeSet<>(excluded))
			lines.add(PortablePaths.text(path.normalize()));
		selection.ifPresent(chosen -> lines.add("selection").add(chosen.describe()));
		return candidate.hex() + EXCLUSION_SEPARATOR + Sha256.of(lines.toString().getBytes(UTF_8)).hex();
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
