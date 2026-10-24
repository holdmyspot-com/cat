package com.holdmyspot.cat.common.optimizer.port.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry.RegularFile;
import com.holdmyspot.cat.common.optimizer.inventory.InventoryScanner;
import com.holdmyspot.cat.common.optimizer.inventory.Overlay;
import com.holdmyspot.cat.common.optimizer.inventory.OverlayEntry.Write;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.port.PluginRemovals;
import com.holdmyspot.cat.common.optimizer.port.SourceSelection;
import com.holdmyspot.cat.common.optimizer.port.WorkspacePort;
import com.holdmyspot.cat.common.optimizer.port.contract.test.WorkspaceContract;
import com.holdmyspot.cat.common.optimizer.workspace.Workspace;
import io.github.cowwoc.pouch10.core.WrappedCheckedException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.stream.Stream;

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
 * {@link Workspace}.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class FakeWorkspacePort implements WorkspacePort
{
	private final Object lock = new Object();
	private final Path root;
	/**
	 * The project directory that staged sources are copied from, or {@code null} if they are not written.
	 */
	private final Path project;
	private final Set<Sha256> staged = new LinkedHashSet<>();
	/**
	 * The paths that the latest request to stage each candidate excluded.
	 */
	private final Map<Sha256, Set<Path>> exclusions = new HashMap<>();
	/**
	 * The selections of the requests to stage each candidate, in request order.
	 */
	private final Map<Sha256, List<SourceSelection>> selections = new HashMap<>();
	/**
	 * The candidates that a request staged whole, without a selection.
	 */
	private final Set<Sha256> stagedWhole = new HashSet<>();
	/**
	 * The directories of the staged sources that exist.
	 */
	private final Set<Path> directories = new LinkedHashSet<>();
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
	 * The fake names the source after the candidate's identity and a digest of the selection.
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
			Sha256 identity = candidate.identity();
			exclusions.put(identity, Set.copyOf(excluded));
			if (selection.isPresent())
				selections.computeIfAbsent(identity, _ -> new ArrayList<>()).add(selection.get());
			else
				stagedWhole.add(identity);
			String name = selection.map(chosen -> identity.hex() + "-" +
				Sha256.of(chosen.describe().getBytes(UTF_8)).hex()).orElse(identity.hex());
			Path directory = root.resolve("staged").resolve(name);
			if (project == null && directories.contains(directory) || project != null && Files.isDirectory(directory))
				return directory;
			if (project != null)
			{
				for (Path file : candidate.snapshot().entries().keySet())
				{
					if (file.isAbsolute() || excluded.stream().anyMatch(file::startsWith))
						continue;
					if (selection.isPresent() && !selection.get().selects(file))
						continue;
					Path target = directory.resolve(file);
					Files.createDirectories(target.getParent());
					FileContent captured = contents.get(file);
					if (candidate.entries().get(file) instanceof Write write)
						Files.write(target, write.content().bytes());
					else if (captured != null)
					{
						Files.write(target, captured.bytes());
						if (candidate.snapshot().entries().get(file) instanceof RegularFile regular)
							PortableFiles.setMode(target, regular.permissions());
					}
					else
						Files.copy(project.resolve(file), target, StandardCopyOption.REPLACE_EXISTING);
				}
			}
			Files.createDirectories(directory);
			staged.add(identity);
			directories.add(directory);
			return directory;
		}
	}

	@Override
	public void deleteStaged(Sha256 candidate)
	{
		synchronized (lock)
		{
			requireThat(candidate, "candidate").isNotNull();
			try
			{
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
			}
			catch (IOException e)
			{
				throw WrappedCheckedException.wrap(e);
			}
			staged.remove(candidate);
		}
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The fake names the copy after the staged source and the plugin under {@code plugins}, with the suffix
	 * {@code -without-dependents} when there are removals, and creates nothing.
	 */
	@Override
	public Path pluginCopy(TrialPlugin plugin, Path source, PluginRemovals removals)
	{
		requireThat(plugin, "plugin").isNotNull();
		requireThat(source, "source").isNotNull();
		requireThat(removals, "removals").isNotNull();
		String name = plugin.name();
		if (!removals.isEmpty())
			name += "-without-dependents";
		return root.resolve("plugins").resolve(source.getFileName().toString()).resolve(name);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The fake copies the directory's files under {@code plugin-bases} in its root.
	 */
	@Override
	public String copyPluginBase(Path directory) throws IOException
	{
		requireThat(directory, "directory").isNotNull();
		String digest = InventoryScanner.scan(directory, Set.of()).identity().hex();
		Path base = pluginBase(digest);
		synchronized (lock)
		{
			if (Files.isDirectory(base))
				return digest;
			try (Stream<Path> files = Files.walk(directory))
			{
				for (Path file : files.filter(Files::isRegularFile).toList())
				{
					Path target = base.resolve(directory.relativize(file).toString());
					Files.createDirectories(target.getParent());
					Files.copy(file, target);
				}
			}
			Files.createDirectories(base);
		}
		return digest;
	}

	@Override
	public Path pluginBase(String digest)
	{
		requireThat(digest, "digest").isNotNull();
		return root.toAbsolutePath().resolve("plugin-bases").resolve(digest);
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
	 * Indicates whether a request staged a candidate whole, without a selection.
	 *
	 * @param candidate the candidate's identity
	 * @return {@code true} if a request staged every file of the candidate
	 * @throws NullPointerException if {@code candidate} is null
	 */
	public boolean stagedWhole(Sha256 candidate)
	{
		synchronized (lock)
		{
			requireThat(candidate, "candidate").isNotNull();
			return stagedWhole.contains(candidate);
		}
	}

	/**
	 * Returns the selections of the requests to stage a candidate.
	 *
	 * @param candidate the candidate's identity
	 * @return the selections in request order, or an empty list if every request staged the whole candidate
	 * @throws NullPointerException if {@code candidate} is null
	 */
	public List<SourceSelection> selections(Sha256 candidate)
	{
		synchronized (lock)
		{
			requireThat(candidate, "candidate").isNotNull();
			return List.copyOf(selections.getOrDefault(candidate, List.of()));
		}
	}

	/**
	 * Returns the paths that the latest request to stage a candidate excluded.
	 *
	 * @param candidate the candidate's identity
	 * @return the excluded paths
	 * @throws NullPointerException     if {@code candidate} is null
	 * @throws IllegalArgumentException if the candidate was never staged
	 */
	public Set<Path> excluded(Sha256 candidate)
	{
		synchronized (lock)
		{
			requireThat(candidate, "candidate").isNotNull();
			Set<Path> excluded = exclusions.get(candidate);
			if (excluded == null)
				throw new IllegalArgumentException("The candidate " + candidate + " was never staged");
			return excluded;
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
