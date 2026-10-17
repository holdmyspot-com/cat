package io.github.cowwoc.cat.common.optimizer.workspace;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

import io.github.cowwoc.cat.common.SessionWorkDirectory;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.git.Git;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.InventoryScanner;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A campaign's fork of a base worktree: the recorded prompt system and the detached optimization worktree at the base's
 * {@code HEAD}.
 * <p>
 * Creating a fork requires a clean base worktree, ensures that Git ignores CAT's work directory so that campaign files
 * never make the base dirty, records the files, and adds the optimization worktree. Every Git operation runs through
 * {@link Git}.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class Fork implements ForkView
{
	private static final String[] CHECK_IGNORE = {"check-ignore", "--quiet", "--no-index",
		SessionWorkDirectory.WORK_ROOT + "/probe"};
	private static final String CLEAN_PATHSPEC_EXCLUDING_WORK = ":(exclude)" + SessionWorkDirectory.WORK_ROOT;
	private final ForkTree recorded;
	private final ParticipantSelection selection;
	private final ForkTree tree;
	private final Workspace workspace;
	private final Path worktree;
	private final boolean excludeAdded;

	/**
	 * Creates a fork.
	 *
	 * @param recorded every recorded file, including the participants that the selection leaves out
	 * @param selection the participants recorded after creation that the fork's files hold
	 * @param worktree the optimization worktree's directory
	 * @param excludeAdded {@code true} if creating the fork appended CAT's work directory to the local exclude
	 */
	private Fork(ForkTree recorded, ParticipantSelection selection, Path worktree, boolean excludeAdded)
	{
		this.recorded = recorded;
		this.selection = selection;
		this.tree = recorded.reachableFrom(selection::holds);
		this.workspace = new Workspace(tree, recorded.directory().getParent(), worktree);
		this.worktree = worktree;
		this.excludeAdded = excludeAdded;
	}

	/**
	 * Forks a base worktree.
	 * <p>
	 * The base must have no staged, unstaged or untracked changes outside CAT's work directory. Otherwise this method
	 * stops before it creates anything, including the local exclude line.
	 *
	 * @param base the base worktree, which is the top-level directory of its repository
	 * @param campaign the campaign storage directory
	 * @param participants ignored paths and trial fixtures relative to {@code base}, and absolute paths outside it, that
	 *   take part in the campaign; tracked files, and the existing targets outside {@code base} that recorded links
	 *   reach, always take part
	 * @return the fork
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code base} is not the top-level directory of its repository, or a
	 *   participant is not a valid path (see {@link ForkTree#record(Path, Path, Set)})
	 * @throws WorkspaceException if the base has uncommitted changes or a participant does not exist
	 * @throws IOException if Git fails or the files cannot be copied
	 */
	public static Fork create(Path base, Path campaign, Set<Path> participants) throws IOException
	{
		requireThat(base, "base").isNotNull();
		requireThat(campaign, "campaign").isNotNull();
		requireThat(participants, "participants").isNotNull();
		Path normalizedBase = base.toAbsolutePath().normalize();
		Path normalizedCampaign = campaign.toAbsolutePath().normalize();
		Git git = new Git(normalizedBase);
		Path exclude = requireTopLevel(git, normalizedBase);
		requireClean(git, normalizedBase);
		boolean excludeAdded = ensureWorkDirectoryIgnored(git, exclude);

		Set<Path> paths = new HashSet<>(participants);
		for (Path path : tracked(git))
		{
			if (!InventoryScanner.isOmitted(path))
				paths.add(path);
		}
		ForkTree tree = ForkTree.record(normalizedBase, normalizedCampaign, paths);
		Path worktree = worktreeOf(normalizedCampaign);
		boolean worktreeAdded = false;
		try
		{
			git.run("worktree", "add", "--detach", worktree.toString(), "HEAD");
			worktreeAdded = true;
			Fork fork = new Fork(tree, ParticipantSelection.NONE, worktree, excludeAdded);
			// Git checks out the tracked files only, so write the participants and the recorded modes as well
			fork.workspace.materialize(Overlay.of(fork.tree.inventory()));
			return fork;
		}
		catch (IOException | RuntimeException e)
		{
			undo(git, tree, worktree, worktreeAdded, e);
			throw e;
		}
	}

	/**
	 * Lists the files that Git tracks in a worktree.
	 *
	 * @param base the worktree, which is the top-level directory of its repository
	 * @return the files, relative to {@code base}
	 * @throws NullPointerException if {@code base} is null
	 * @throws IOException if Git fails
	 */
	public static Set<Path> tracked(Path base) throws IOException
	{
		requireThat(base, "base").isNotNull();
		return tracked(new Git(base.toAbsolutePath().normalize()));
	}

	/**
	 * Lists the files that Git tracks in a worktree.
	 *
	 * @param git the worktree's Git
	 * @return the files, relative to the worktree
	 * @throws IOException if Git fails
	 */
	private static Set<Path> tracked(Git git) throws IOException
	{
		Set<Path> paths = new HashSet<>();
		for (String tracked : new String(git.bytes("ls-files", "-z"), UTF_8).split("\0"))
		{
			if (!tracked.isEmpty())
				paths.add(Path.of(tracked));
		}
		return paths;
	}

	/**
	 * Removes what {@link #create(Path, Path, Set)} created after it recorded the files, so that a retry starts clean.
	 * The local exclude line stays, because removing it would let campaign files dirty the base. A failure to undo is
	 * attached to the original failure as a suppressed exception.
	 *
	 * @param git the base's Git
	 * @param tree the recorded files
	 * @param worktree the optimization worktree's directory
	 * @param worktreeAdded {@code true} if {@code git worktree add} succeeded, so the worktree is CAT's to remove
	 * @param failure the failure that stops the fork
	 */
	private static void undo(Git git, ForkTree tree, Path worktree, boolean worktreeAdded, Throwable failure)
	{
		if (worktreeAdded)
		{
			attempt(failure, () -> git.run("worktree", "remove", "--force", worktree.toString()));
			attempt(failure, () -> TemporaryDirectories.deleteRecursively(worktree));
			attempt(failure, () -> git.run("worktree", "prune"));
		}
		attempt(failure, () -> TemporaryDirectories.deleteRecursively(tree.directory()));
	}

	/**
	 * Runs one undo step, attaching its failure to the original failure instead of throwing it.
	 *
	 * @param failure the failure that stops the fork
	 * @param step the undo step
	 */
	private static void attempt(Throwable failure, UndoStep step)
	{
		try
		{
			step.run();
		}
		catch (IOException e)
		{
			failure.addSuppressed(e);
		}
	}

	/**
	 * One step that undoes part of a failed fork.
	 */
	@FunctionalInterface
	private interface UndoStep
	{
		/**
		 * Runs the step.
		 *
		 * @throws IOException if the step fails
		 */
		void run() throws IOException;
	}

	/**
	 * Opens a fork that {@link #create(Path, Path, Set)} created earlier, for example to resume a campaign.
	 *
	 * @param base the base worktree
	 * @param campaign the campaign storage directory
	 * @return the fork, which reports that it did not add the local exclude
	 * @throws NullPointerException if any of the arguments are null
	 * @throws WorkspaceException if the campaign holds no fork
	 * @throws IOException if the recorded files cannot be read
	 */
	public static Fork open(Path base, Path campaign) throws IOException
	{
		requireThat(base, "base").isNotNull();
		requireThat(campaign, "campaign").isNotNull();
		Path normalizedCampaign = campaign.toAbsolutePath().normalize();
		ForkTree tree = ForkTree.open(base, normalizedCampaign);
		Path worktree = worktreeOf(normalizedCampaign);
		if (!Files.isDirectory(worktree))
			throw new WorkspaceException("The campaign directory " + campaign + " holds no optimization worktree.");
		return new Fork(tree, ParticipantSelection.read(normalizedCampaign), worktree, false);
	}

	/**
	 * Removes the optimization worktree that a campaign holds, and Git's record of it. Removing a worktree that does
	 * not exist only prunes Git's records of missing worktrees.
	 *
	 * @param base the base worktree
	 * @param campaign the campaign storage directory
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException if Git fails or the worktree cannot be deleted
	 */
	public static void remove(Path base, Path campaign) throws IOException
	{
		requireThat(base, "base").isNotNull();
		requireThat(campaign, "campaign").isNotNull();
		Git git = new Git(base.toAbsolutePath().normalize());
		Path worktree = worktreeOf(campaign.toAbsolutePath().normalize());
		// A worktree whose deletion was interrupted may have lost its ".git" file, which Git needs to remove it
		if (Files.exists(worktree.resolve(".git"), LinkOption.NOFOLLOW_LINKS))
			git.run("worktree", "remove", "--force", worktree.toString());
		TemporaryDirectories.deleteRecursively(worktree);
		git.run("worktree", "prune");
	}

	/**
	 * Returns where a campaign holds its optimization worktree.
	 *
	 * @param campaign the campaign storage directory
	 * @return the worktree's directory
	 */
	static Path worktreeOf(Path campaign)
	{
		return campaign.resolve("worktree");
	}

	/**
	 * Requires that the base is the top-level directory of its repository, which the local exclude pattern assumes.
	 * One Git process answers both this check and the exclude's location.
	 *
	 * @param git the base's Git
	 * @param base the base worktree
	 * @return the repository's local exclude file
	 * @throws IOException if Git fails
	 */
	private static Path requireTopLevel(Git git, Path base) throws IOException
	{
		// The first line is the path from the base to the top-level directory, which is empty at the top level
		List<String> lines = git.lines("rev-parse", "--show-cdup", "--git-path", "info/exclude");
		if (lines.size() != 2 || !lines.getFirst().isEmpty())
			throw new IllegalArgumentException(base + " is not the top-level directory of its repository.");
		return base.resolve(lines.getLast());
	}

	/**
	 * Requires that the base has no staged, unstaged or untracked changes, ignoring CAT's work directory.
	 *
	 * @param git the base's Git
	 * @param base the base worktree
	 * @throws WorkspaceException if the base has changes
	 * @throws IOException if Git fails
	 */
	private static void requireClean(Git git, Path base) throws IOException
	{
		List<String> changes = git.lines("status", "--porcelain", "--untracked-files=all", "--", ".",
			CLEAN_PATHSPEC_EXCLUDING_WORK);
		if (changes.isEmpty())
			return;
		throw new WorkspaceException("The base worktree " + base + " has staged, unstaged or untracked changes, so " +
			"CAT cannot fork it: commit, stash or remove the changes, then start the campaign again. CAT stopped " +
			"before it created anything. Changes:\n" + String.join("\n", changes));
	}

	/**
	 * Appends CAT's work directory to the repository's local exclude if Git does not already ignore it.
	 *
	 * @param git the base's Git
	 * @param exclude the repository's local exclude file
	 * @return {@code true} if this method appended the line
	 * @throws IOException if Git fails or the exclude cannot be written
	 */
	private static boolean ensureWorkDirectoryIgnored(Git git, Path exclude) throws IOException
	{
		int status = git.status(CHECK_IGNORE);
		if (status == 0)
			return false;
		if (status != 1)
		{
			throw new IOException("Git command failed with status " + status + ": git " +
				String.join(" ", CHECK_IGNORE));
		}
		Files.createDirectories(exclude.getParent());
		String existing = "";
		if (Files.exists(exclude))
			existing = Files.readString(exclude, UTF_8);
		String separator = "";
		if (!existing.isEmpty() && !existing.endsWith("\n"))
			separator = "\n";
		Files.writeString(exclude, separator + EXCLUDE_ENTRY + "\n", UTF_8, CREATE, APPEND);
		return true;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The selection is stored in campaign storage before any file is copied, so that a copy that a failure interrupts
	 * is never taken for a path that the fork was created with. The optimization worktree is then rewritten to hold
	 * the fork's files. Each target outside the project that a participant's links reach is stored in the selection
	 * before it is copied, and it leaves the fork's files with the participant unless a link that the fork still
	 * holds reaches it.
	 *
	 * @throws IllegalArgumentException if a path cannot be recorded
	 */
	@Override
	public Fork selectParticipants(Path base, Set<Path> participants) throws IOException
	{
		requireThat(base, "base").isNotNull();
		requireThat(participants, "participants").isNotNull();
		Set<Path> chosen = notCreated(participants);
		recorded.requireExtensible(base, chosen);

		Path campaign = recorded.directory().getParent();
		ParticipantSelection[] next = {selection.select(chosen)};
		next[0].write(campaign);
		ForkTree extended = recorded.extend(base, chosen, target ->
		{
			next[0] = next[0].withTarget(target);
			next[0].write(campaign);
		});
		Fork fork = new Fork(extended, next[0], worktree, excludeAdded);
		fork.workspace.materialize(Overlay.of(fork.tree.inventory()));
		return fork;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Each path that the fork has not recorded yet is stored in the campaign's selection as left out before it is
	 * copied, so that the current fork stays as it is and an interrupted copy is never taken for a path that the fork
	 * was created with.
	 *
	 * @throws IllegalArgumentException if a path cannot be recorded
	 */
	@Override
	public Fork extend(Path base, Set<Path> participants) throws IOException
	{
		requireThat(base, "base").isNotNull();
		requireThat(participants, "participants").isNotNull();
		Set<Path> chosen = notCreated(participants);
		recorded.requireExtensible(base, chosen);

		Path campaign = recorded.directory().getParent();
		ParticipantSelection[] stored = {ParticipantSelection.read(campaign).withAdded(chosen)};
		stored[0].write(campaign);
		ForkTree extended = recorded.extend(base, chosen, target ->
		{
			stored[0] = stored[0].withTarget(target);
			stored[0].write(campaign);
		});
		Set<Path> held = new HashSet<>(selection.selected());
		held.addAll(chosen);
		return new Fork(extended, stored[0].withSelected(held), worktree, excludeAdded);
	}

	@Override
	public Fork including(Set<Path> participants)
	{
		requireThat(participants, "participants").isNotNull();
		Set<Path> held = new HashSet<>(selection.selected());
		for (Path participant : notCreated(participants))
		{
			if (selection.added().contains(participant))
				held.add(participant);
		}
		return new Fork(recorded, selection.withSelected(held), worktree, excludeAdded);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The selection of the fork that was current is stored under its identity before this fork's selection replaces
	 * it, so an interrupted call leaves the earlier fork current.
	 */
	@Override
	public void adopt() throws IOException
	{
		Path campaign = recorded.directory().getParent();
		ParticipantSelection stored = ParticipantSelection.read(campaign).withAdded(selection.added());
		FileInventory current = recorded.reachableFrom(stored::holds).inventory();
		if (!current.equals(tree.inventory()))
			stored.writeVersion(campaign, current.identity());
		stored.withSelected(selection.selected()).write(campaign);
	}

	@Override
	public Optional<FileInventory> version(Sha256 identity) throws IOException
	{
		requireThat(identity, "identity").isNotNull();
		if (identity.equals(tree.inventory().identity()))
			return Optional.of(tree.inventory());
		Path campaign = recorded.directory().getParent();
		// Paths recorded after a fork was current lie outside it, so every fork is rebuilt with every recorded path
		ParticipantSelection stored = ParticipantSelection.read(campaign).withAdded(selection.added());
		List<Set<Path>> selections = new ArrayList<>();
		selections.add(stored.selected());
		ParticipantSelection.readVersion(campaign, identity).ifPresent(version -> selections.add(version.selected()));
		for (Set<Path> selected : selections)
		{
			FileInventory fork = recorded.reachableFrom(stored.withSelected(selected)::holds).inventory();
			if (fork.identity().equals(identity))
				return Optional.of(fork);
		}
		return Optional.empty();
	}

	/**
	 * Returns the participants that the fork was not created with.
	 *
	 * @param participants the participants
	 * @return those that lie at or below no path that the fork was created with
	 */
	private Set<Path> notCreated(Set<Path> participants)
	{
		FileInventory created = recorded.restrict(selection::isCreated).inventory();
		Set<Path> chosen = new HashSet<>();
		for (Path participant : participants)
		{
			if (!created.holds(participant))
				chosen.add(participant);
		}
		return chosen;
	}

	@Override
	public FileInventory inventory()
	{
		return tree.inventory();
	}

	/**
	 * Returns the workspace that stages sources and manages the optimization worktree.
	 *
	 * @return the workspace
	 */
	@Override
	public Workspace workspace()
	{
		return workspace;
	}

	@Override
	public boolean excludeAdded()
	{
		return excludeAdded;
	}
}
