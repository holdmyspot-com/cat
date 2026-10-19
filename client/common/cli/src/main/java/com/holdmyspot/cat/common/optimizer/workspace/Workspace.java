package com.holdmyspot.cat.common.optimizer.workspace;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry.RegularFile;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry.SpecialFile;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry.SymbolicLink;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.inventory.InventoryDiff;
import com.holdmyspot.cat.common.optimizer.inventory.Overlay;
import com.holdmyspot.cat.common.optimizer.inventory.PathChange;
import com.holdmyspot.cat.common.optimizer.inventory.PathChange.Added;
import com.holdmyspot.cat.common.optimizer.inventory.PathChange.Modified;
import com.holdmyspot.cat.common.optimizer.inventory.PathChange.Removed;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.port.PluginRemovals;
import com.holdmyspot.cat.common.optimizer.port.SourceSelection;
import com.holdmyspot.cat.common.optimizer.port.WorkspacePort;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * The file-system implementation of {@link WorkspacePort}: staged sources, authoring copies, and the optimization
 * worktree.
 * <p>
 * The optimization worktree holds the working candidate's project files. Copies of the read-only external
 * participants live in a mirror beside it, never in the live files. A scan of the worktree reports the mirror's files
 * under their absolute paths, so it is comparable with a candidate's {@link Overlay#snapshot() snapshot}.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe. Writes to the worktree and scans of it do not overlap.
 */
public final class Workspace implements WorkspacePort
{
	private final Object worktreeLock = new Object();
	private final ForkTree tree;
	private final StagedSources stagedSources;
	private final AuthoringCopies authoringCopies;
	private final PluginCopies pluginCopies;
	private final Path worktree;
	private final Path worktreeMirror;

	/**
	 * Creates the workspace of a fork.
	 *
	 * @param tree the fork's files
	 * @param storage the campaign storage directory, which holds the staged sources, the authoring copies, the
	 *   plugin copies and the mirror of the read-only external participants
	 * @param worktree the optimization worktree's directory
	 * @throws NullPointerException if any of the arguments are null
	 */
	public Workspace(ForkTree tree, Path storage, Path worktree)
	{
		requireThat(tree, "tree").isNotNull();
		requireThat(storage, "storage").isNotNull();
		requireThat(worktree, "worktree").isNotNull();
		Path normalizedStorage = storage.toAbsolutePath().normalize();
		this.tree = tree;
		this.stagedSources = new StagedSources(tree, normalizedStorage.resolve("staged"));
		this.authoringCopies = new AuthoringCopies(tree, normalizedStorage.resolve("authoring"));
		this.pluginCopies = new PluginCopies(normalizedStorage.resolve("plugins"));
		this.worktree = worktree.toAbsolutePath().normalize();
		this.worktreeMirror = normalizedStorage.resolve("worktree-external");
	}

	@Override
	public Optional<FileContent> readFile(Overlay candidate, Path path) throws IOException
	{
		tree.requireSameFork(candidate);
		FileInventory.requireInventoryPath(path, "path");
		Path target = candidate.snapshot().resolve(path).orElse(null);
		if (target == null || !(candidate.snapshot().entries().get(target) instanceof RegularFile))
			return Optional.empty();
		return Optional.of(tree.readFile(candidate, target));
	}

	@Override
	public Path stage(Overlay candidate, Set<Path> excluded) throws IOException
	{
		return stagedSources.stage(candidate, excluded);
	}

	@Override
	public Path stage(Overlay candidate, Set<Path> excluded, SourceSelection selection) throws IOException
	{
		return stagedSources.stage(candidate, excluded, selection);
	}

	@Override
	public void deleteStaged(Sha256 candidate) throws IOException
	{
		stagedSources.delete(candidate);
		pluginCopies.delete(candidate);
	}

	@Override
	public Path pluginCopy(TrialPlugin plugin, Path source, PluginRemovals removals) throws IOException
	{
		return pluginCopies.copy(plugin, source, removals);
	}

	@Override
	public String copyPluginBase(Path directory) throws IOException
	{
		return pluginCopies.copyBase(directory);
	}

	@Override
	public Path pluginBase(String digest)
	{
		return pluginCopies.base(digest);
	}

	@Override
	public Path worktree()
	{
		return worktree;
	}

	@Override
	public Path prepareAuthoringCopy(Overlay working) throws IOException
	{
		return authoringCopies.prepare(working);
	}

	@Override
	public FileInventory scanAuthoringCopy(Path copy) throws IOException
	{
		return authoringCopies.scan(copy);
	}

	@Override
	public FileInventory scanPromptAuthorEffects(Path copy, Path effects) throws IOException
	{
		return authoringCopies.scanEffects(copy, effects);
	}

	@Override
	public void deleteAuthoringCopy(Path copy) throws IOException
	{
		authoringCopies.delete(copy);
	}

	@Override
	public FileInventory scanWorktree() throws IOException
	{
		synchronized (worktreeLock)
		{
			return FileTree.scan(worktree, worktree, worktreeMirror);
		}
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The worktree ends up holding exactly the candidate's files: a file that the candidate lacks is deleted, and a
	 * file that differs from the candidate's is rewritten. CAT checks the worktree for unowned changes before it
	 * materializes a candidate.
	 *
	 * @throws IllegalArgumentException if {@code candidate} is an overlay on a different fork
	 */
	@Override
	public void materialize(Overlay candidate) throws IOException
	{
		requireThat(candidate, "candidate").isNotNull();
		tree.requireSameFork(candidate);
		synchronized (worktreeLock)
		{
			Files.createDirectories(worktree);
			FileInventory expected = candidate.snapshot();
			for (PathChange change : InventoryDiff.between(expected, scanWorktree()).changes())
			{
				Path location = FileTree.locate(worktree, worktreeMirror, change.path());
				switch (change)
				{
					case Added _ -> Files.deleteIfExists(location);
					case Removed removed -> restore(candidate, change.path(), location, removed.before());
					case Modified modified -> restore(candidate, change.path(), location, modified.before());
				}
			}
		}
	}

	/**
	 * Writes one entry of a candidate into the worktree, replacing whatever is there.
	 *
	 * @param candidate the candidate
	 * @param key the entry's key
	 * @param location where the worktree holds the entry
	 * @param entry the candidate's entry
	 * @throws IOException if the entry cannot be written
	 */
	private void restore(Overlay candidate, Path key, Path location, FileEntry entry) throws IOException
	{
		Files.createDirectories(location.getParent());
		Files.deleteIfExists(location);
		switch (entry)
		{
			case RegularFile file -> tree.writeFile(candidate, key, file, location);
			case SymbolicLink link -> Files.createSymbolicLink(location, link.target());
			case SpecialFile _ -> throw new IOException("The fork records " + key + " as a special file, which a " +
				"worktree cannot hold.");
		}
	}
}
