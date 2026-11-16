package com.holdmyspot.cat.common.optimizer.port.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry.RegularFile;
import com.holdmyspot.cat.common.optimizer.inventory.Overlay;
import com.holdmyspot.cat.common.optimizer.inventory.OverlayEntry.Write;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.port.PluginRemovals;
import com.holdmyspot.cat.common.optimizer.port.SourceSelection;
import com.holdmyspot.cat.common.optimizer.port.WorkspacePort;
import com.holdmyspot.cat.common.optimizer.port.contract.test.WorkspaceContract;
import com.holdmyspot.cat.common.secrets.PluginCopiesAccess;
import com.holdmyspot.cat.common.secrets.PluginCopiesSecrets;
import com.holdmyspot.cat.common.secrets.StagedSourcesAccess.Source;
import com.holdmyspot.cat.common.secrets.StagedSourcesSecrets;
import com.holdmyspot.cat.common.optimizer.workspace.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.TreeSet;

/**
 * A {@link WorkspacePort} that keeps every directory and inventory in memory.
 * <p>
 * Returned paths are names under a root that the fake never reads or writes, except that it creates each staged
 * source's directory, which a trial record compares with the trial's effects. A fake created with a project directory
 * fills that directory with the fork's project files, copied from the project directory; otherwise it stays empty.
 * Supplied captured bytes take precedence over project files for saved reads and staged sources.
 * Each authoring copy scans as the working candidate's snapshot rooted at the copy. Prompt author edits are supplied
 * with {@link #editAuthoringCopy(Path, FileInventory)}; an inventory rooted at a session's effects directory changes
 * those effects independently of the source copy. {@link WorkspaceContract} checks these operations against
	 * {@link Workspace}: reading a saved candidate's files, staging, plugin bases and the identity and lifecycle of
	 * virtual plugin copies,
 * authoring copies, the worktree and materialization. {@link #staged()} and {@link #stagedSources()} report which
 * of those directories the fake holds, for the tests of its callers.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class FakeWorkspacePort implements WorkspacePort
{
	private final Object lock = new Object();
	private final Path root;
	private final PluginCopiesAccess.Copies pluginCopier;
	/**
	 * The project directory that staged sources are copied from, or {@code null} if they are not written.
	 */
	private final Path project;
	private final Set<Sha256> staged = new LinkedHashSet<>();
	/**
	 * The directories of the staged sources that exist.
	 */
	private final Set<Path> directories = new LinkedHashSet<>();
	/**
	 * Plugin paths returned by the production copier that still exist.
	 */
	private final Set<Path> pluginCopies = new LinkedHashSet<>();
	private final Map<Path, FileInventory> authoringCopies = new HashMap<>();
	private final Map<Path, FileContent> contents = new HashMap<>();
	private final Map<Path, FileInventory> promptAuthorEffects = new HashMap<>();
	private FileInventory worktree;
	private int copiesPrepared;
	/**
	 * Creates a workspace.
	 *
	 * @param root the directory that returned paths are named under
	 * @param worktree the optimization worktree's initial inventory
	 * @throws NullPointerException if any argument is null
	 */
	public FakeWorkspacePort(Path root, FileInventory worktree)
	{
		requireThat(root, "root").isNotNull();
		requireThat(worktree, "worktree").isNotNull();
		this.root = root;
		this.project = null;
		this.worktree = worktree;
		this.pluginCopier = PluginCopiesSecrets.getPluginCopiesAccess().create(root.resolve("plugins"));
	}

	/**
	 * Creates a workspace whose staged sources hold the fork's project files.
	 *
	 * @param root the directory that returned paths are named under
	 * @param worktree the optimization worktree's initial inventory
	 * @param project the project directory that each staged source copies the fork's project files from
	 * @throws NullPointerException if any argument is null
	 */
	public FakeWorkspacePort(Path root, FileInventory worktree, Path project)
	{
		requireThat(root, "root").isNotNull();
		requireThat(worktree, "worktree").isNotNull();
		requireThat(project, "project").isNotNull();
		this.root = root;
		this.project = project;
		this.worktree = worktree;
		this.pluginCopier = PluginCopiesSecrets.getPluginCopiesAccess().create(root.resolve("plugins"));
	}

	/**
	 * Supplies the immutable fork bytes that a snapshot reader may request.
	 *
	 * @param path the resolved recorded file
	 * @param content its bytes
	 */
	public void supplyFile(Path path, FileContent content)
	{
		synchronized (lock)
		{
			contents.put(path, content);
		}
	}

	@Override
	public Optional<FileContent> readFile(Overlay candidate, Path path) throws IOException
	{
		FileInventory.requireInventoryPath(path, "path");
		Path target = candidate.snapshot().resolve(path).orElse(null);
		if (target == null || !candidate.snapshot().entries().containsKey(target))
			return Optional.empty();
		if (candidate.entries().get(target) instanceof Write write)
			return Optional.of(write.content());
		synchronized (lock)
		{
			FileContent content = contents.get(target);
			if (content != null)
				return Optional.of(content);
			if (project != null)
				return Optional.of(new FileContent(Files.readAllBytes(project.resolve(target))));
			throw new IOException("The in-memory workspace has no supplied content for " + target);
		}
	}

	@Override
	public Path stage(Overlay candidate, Set<Path> excluded) throws IOException
	{
		return stage(candidate, excluded, Optional.empty());
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The fake includes excluded paths in the source name, with the selection when present.
	 */
	@Override
	public Path stage(Overlay candidate, Set<Path> excluded, SourceSelection selection) throws IOException
	{
		requireThat(selection, "selection").isNotNull();
		return stage(candidate, excluded, Optional.of(selection));
	}

	/**
	 * Returns a staged source for a candidate.
	 *
	 * @param candidate the candidate's overlay on the fork
	 * @param excluded  the project-relative paths to leave out of the source, with everything below them
	 * @param selection the files to stage, or empty to stage every file
	 * @return the directory of the staged source
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException if a file cannot be copied
	 */
	private Path stage(Overlay candidate, Set<Path> excluded, Optional<SourceSelection> selection) throws IOException
	{
		synchronized (lock)
		{
			requireThat(candidate, "candidate").isNotNull();
			requireThat(excluded, "excluded").isNotNull();
			FileInventory snapshot = candidate.snapshot();
			List<Source> sources = StagedSourcesSecrets.getStagedSourcesAccess().sources(snapshot);
			Sha256 identity = candidate.identity();
			String name;
			if (excluded.isEmpty())
				name = selection.map(chosen -> identity.hex() + "-" +
					Sha256.of(chosen.describe().getBytes(UTF_8)).hex()).orElse(identity.hex());
			else
			{
				StringJoiner identityParts = new StringJoiner("\n");
				Set<Path> sortedExclusions = new TreeSet<>();
				for (Path path : excluded)
					sortedExclusions.add(path.normalize());
				for (Path path : sortedExclusions)
					identityParts.add(PortablePaths.text(path));
				selection.ifPresent(chosen -> identityParts.add("selection").add(chosen.describe()));
				name = identity.hex() + "-" + Sha256.of(identityParts.toString().getBytes(UTF_8)).hex();
			}
			Path directory = root.resolve("staged").resolve(name);
			if (project == null && directories.contains(directory) || project != null && Files.isDirectory(directory))
				return directory;
			if (project != null)
			{
				for (Source source : sources)
				{
					Path destination = source.destination();
					if (excluded.stream().anyMatch(destination::startsWith))
						continue;
					if (selection.isPresent() && !selection.get().selects(destination))
						continue;
					Path target = directory.resolve(destination);
					Files.createDirectories(target.getParent());
					if (candidate.entries().get(source.source()) instanceof Write write)
						Files.write(target, write.content().bytes());
					else if (contents.containsKey(source.source()))
					{
						Files.write(target, contents.get(source.source()).bytes());
					}
					else
						Files.copy(project.resolve(source.source()), target, StandardCopyOption.REPLACE_EXISTING);
					RegularFile regular = (RegularFile) snapshot.entries().get(source.source());
					PortableFiles.setMode(target, regular.permissions());
				}
			}
			Files.createDirectories(directory);
			staged.add(identity);
			directories.add(directory);
			return directory;
		}
	}

	@Override
	public void deleteStaged(Sha256 candidate) throws IOException
	{
		synchronized (lock)
		{
			requireThat(candidate, "candidate").isNotNull();
			Path parent = root.resolve("staged");
			TemporaryDirectories.deleteRecursively(parent.resolve(candidate.hex()));
			for (Path directory : List.copyOf(directories))
			{
				String name = directory.getFileName().toString();
				if (directory.getParent().equals(parent) &&
					(name.equals(candidate.hex()) || name.startsWith(candidate.hex() + "-")))
				{
					TemporaryDirectories.deleteRecursively(directory);
					directories.remove(directory);
				}
			}
			pluginCopier.delete(candidate);
			pluginCopies.removeIf(copy -> !Files.isDirectory(copy));
			staged.remove(candidate);
		}
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The fake delegates plugin copies to the production copier under its owned root and records returned directories
	 * that remain after candidate deletion.
	 */
	@Override
	public Path pluginCopy(TrialPlugin plugin, Path source, PluginRemovals removals) throws IOException
	{
		requireThat(plugin, "plugin").isNotNull();
		requireThat(source, "source").isNotNull();
		requireThat(removals, "removals").isNotNull();
		synchronized (lock)
		{
			Path copy = pluginCopier.copy(plugin, source, removals);
			pluginCopies.add(copy);
			return copy;
		}
	}

	/**
	 * Returns the production copier's returned plugin paths that still exist under the fake's owned root.
	 *
		 * @return an immutable snapshot of live plugin-copy paths
	 */
	public Set<Path> pluginCopies()
	{
		synchronized (lock)
		{
			return Set.copyOf(pluginCopies);
		}
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The fake delegates copying under its owned root to the production copier.
	 */
	@Override
	public String copyPluginBase(Path directory) throws IOException
	{
		return pluginCopier.copyBase(directory);
	}

	@Override
	public Path pluginBase(String digest)
	{
		return pluginCopier.base(digest);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The fake's worktree is {@code worktree} in its root, which the test fills.
	 */
	@Override
	public Path worktree()
	{
		return root.toAbsolutePath().resolve("worktree");
	}

	/**
	 * Returns the identities of the candidates whose staged sources exist.
	 *
	 * @return the identities
	 */
	public Set<Sha256> staged()
	{
		synchronized (lock)
		{
			return Set.copyOf(staged);
		}
	}

	/**
	 * Returns the staged-source directories currently held by this fake.
	 *
	 * @return the current staged directories
	 */
	public Set<Path> stagedSources()
	{
		synchronized (lock)
		{
			return Set.copyOf(directories);
		}
	}

	@Override
	public Path prepareAuthoringCopy(Overlay working)
	{
		synchronized (lock)
		{
			requireThat(working, "working").isNotNull();
			++copiesPrepared;
			Path copy = root.resolve("authoring").resolve(String.valueOf(copiesPrepared));
			authoringCopies.put(copy, new FileInventory(copy.toAbsolutePath().normalize(), working.snapshot().entries()));
			return copy;
		}
	}

	/**
	 * Records a prompt author's edits to a copy or to the session's separate effects directory. An inventory rooted at
	 * the copy changes the copy; one rooted at an effects directory changes only those effects.
	 *
	 * @param copy a directory that {@link #prepareAuthoringCopy(Overlay)} returned
	 * @param edited the edited inventory, rooted at the copy or the session's effects directory
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code copy} does not exist
	 */
	public void editAuthoringCopy(Path copy, FileInventory edited)
	{
		synchronized (lock)
		{
			requireThat(copy, "copy").isNotNull();
			requireThat(edited, "edited").isNotNull();
			requireThat(authoringCopies.containsKey(copy), "authoringCopies.containsKey(copy)").isTrue();
			if (edited.root().equals(copy.toAbsolutePath().normalize()))
				authoringCopies.put(copy, edited);
			else
				promptAuthorEffects.put(copy, edited);
		}
	}

	@Override
	public FileInventory scanAuthoringCopy(Path copy)
	{
		synchronized (lock)
		{
			requireThat(copy, "copy").isNotNull();
			requireThat(authoringCopies.containsKey(copy), "authoringCopies.containsKey(copy)").isTrue();
			return authoringCopies.get(copy);
		}
	}

	@Override
	public FileInventory scanPromptAuthorEffects(Path copy, Path effects)
	{
		synchronized (lock)
		{
			requireThat(effects, "effects").isNotNull();
			FileInventory source = scanAuthoringCopy(copy);
			FileInventory edited = promptAuthorEffects.get(copy);
			if (edited != null && edited.root().equals(effects.toAbsolutePath().normalize()))
				return edited;
			return new FileInventory(effects.toAbsolutePath().normalize(), source.entries());
		}
	}

	@Override
	public void deleteAuthoringCopy(Path copy)
	{
		synchronized (lock)
		{
			requireThat(copy, "copy").isNotNull();
			authoringCopies.remove(copy);
			promptAuthorEffects.remove(copy);
		}
	}

	/**
	 * Returns the authoring copies that exist.
	 *
	 * @return the copies' directories
	 */
	public Set<Path> authoringCopies()
	{
		synchronized (lock)
		{
			return Set.copyOf(authoringCopies.keySet());
		}
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The in-memory worktree has no links to a repository, so nothing changes.
	 */
	@Override
	public void reconnectWorktree(Overlay candidate)
	{
		requireThat(candidate, "candidate").isNotNull();
	}

	@Override
	public FileInventory scanWorktree()
	{
		synchronized (lock)
		{
			return worktree;
		}
	}

	/**
	 * Replaces the optimization worktree's inventory, for example to simulate a change that CAT did not make.
	 *
	 * @param inventory the new inventory
	 * @throws NullPointerException if {@code inventory} is null
	 */
	public void changeWorktree(FileInventory inventory)
	{
		synchronized (lock)
		{
			requireThat(inventory, "inventory").isNotNull();
			this.worktree = inventory;
		}
	}

	@Override
	public void materialize(Overlay candidate)
	{
		synchronized (lock)
		{
			requireThat(candidate, "candidate").isNotNull();
			worktree = candidate.snapshot();
		}
	}
}
