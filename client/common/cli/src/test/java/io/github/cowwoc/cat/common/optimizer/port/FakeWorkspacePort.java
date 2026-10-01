package io.github.cowwoc.cat.common.optimizer.port;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@link WorkspacePort} that keeps every directory and inventory in memory.
 * <p>
 * Returned paths are names under a root that the fake never reads or writes. An authoring copy, and the effects of
 * an author session that ran on it, scan as the working candidate's snapshot unless the test records an author's
 * edits with {@link #editAuthoringCopy(Path, FileInventory)}.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class FakeWorkspacePort implements WorkspacePort
{
	private final Object lock = new Object();
	private final Path root;
	private final Set<Sha256> staged = new LinkedHashSet<>();
	private final Map<Path, FileInventory> authoringCopies = new HashMap<>();
	private final List<Overlay> materialized = new ArrayList<>();
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
		this.worktree = worktree;
	}

	@Override
	public Path stage(Overlay candidate)
	{
		synchronized (lock)
		{
			requireThat(candidate, "candidate").isNotNull();
			Sha256 identity = candidate.identity();
			staged.add(identity);
			return root.resolve("staged").resolve(identity.hex());
		}
	}

	@Override
	public void deleteStaged(Sha256 candidate)
	{
		synchronized (lock)
		{
			requireThat(candidate, "candidate").isNotNull();
			staged.remove(candidate);
		}
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

	@Override
	public Path prepareAuthoringCopy(Overlay working)
	{
		synchronized (lock)
		{
			requireThat(working, "working").isNotNull();
			++copiesPrepared;
			Path copy = root.resolve("authoring").resolve(String.valueOf(copiesPrepared));
			authoringCopies.put(copy, working.snapshot());
			return copy;
		}
	}

	/**
	 * Records what an author left in an authoring copy, which both the copy and the effects of a session that ran on
	 * it scan as.
	 *
	 * @param copy a directory that {@link #prepareAuthoringCopy(Overlay)} returned
	 * @param edited the copy's inventory after the author's edits
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
			authoringCopies.put(copy, edited);
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
	public FileInventory scanAuthorEffects(Path copy, Path effects)
	{
		synchronized (lock)
		{
			requireThat(effects, "effects").isNotNull();
			return scanAuthoringCopy(copy);
		}
	}

	@Override
	public void deleteAuthoringCopy(Path copy)
	{
		synchronized (lock)
		{
			requireThat(copy, "copy").isNotNull();
			authoringCopies.remove(copy);
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
			materialized.add(candidate);
			worktree = candidate.snapshot();
		}
	}

	/**
	 * Returns the candidates written into the optimization worktree.
	 *
	 * @return the candidates, in the order they were written
	 */
	public List<Overlay> materialized()
	{
		synchronized (lock)
		{
			return List.copyOf(materialized);
		}
	}
}
