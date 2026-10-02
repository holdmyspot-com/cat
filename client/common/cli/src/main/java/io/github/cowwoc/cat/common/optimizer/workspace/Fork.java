package io.github.cowwoc.cat.common.optimizer.workspace;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.git.Git;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.InventoryScanner;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
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
	private static final String EXCLUDE_ENTRY = "/.cat/work/";
	private static final String[] CHECK_IGNORE = {"check-ignore", "--quiet", "--no-index", ".cat/work/probe"};
	private static final String CLEAN_PATHSPEC_EXCLUDING_WORK = ":(exclude).cat/work";
	private final ForkTree tree;
	private final Workspace workspace;
	private final Path worktree;
	private final boolean excludeAdded;

	/**
	 * Creates a fork.
	 *
	 * @param tree the recorded files
	 * @param workspace the fork's workspace
	 * @param worktree the optimization worktree's directory
	 * @param excludeAdded {@code true} if creating the fork appended CAT's work directory to the local exclude
	 */
	private Fork(ForkTree tree, Workspace workspace, Path worktree, boolean excludeAdded)
	{
		this.tree = tree;
		this.workspace = workspace;
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
		requireTopLevel(git, normalizedBase);
		requireClean(git, normalizedBase);
		boolean excludeAdded = ensureWorkDirectoryIgnored(git, normalizedBase);

		Set<Path> paths = new HashSet<>(participants);
		for (String tracked : new String(git.bytes("ls-files", "-z"), UTF_8).split("\0"))
		{
			Path path = Path.of(tracked);
			if (!tracked.isEmpty() && !InventoryScanner.isOmitted(path))
				paths.add(path);
		}
		ForkTree tree = ForkTree.record(normalizedBase, normalizedCampaign, paths);
		Path worktree = worktreeOf(normalizedCampaign);
		boolean worktreeAdded = false;
		try
		{
			git.run("worktree", "add", "--detach", worktree.toString(), "HEAD");
			worktreeAdded = true;
			Workspace workspace = new Workspace(tree, normalizedCampaign, worktree);
			// Git checks out the tracked files only, so write the participants and the recorded modes as well
			workspace.materialize(Overlay.of(tree.inventory()));
			return new Fork(tree, workspace, worktree, excludeAdded);
		}
		catch (IOException | RuntimeException e)
		{
			undo(git, tree, worktree, worktreeAdded, e);
			throw e;
		}
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
		return new Fork(tree, new Workspace(tree, normalizedCampaign, worktree), worktree, false);
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
	 *
	 * @param git the base's Git
	 * @param base the base worktree
	 * @throws IOException if Git fails
	 */
	private static void requireTopLevel(Git git, Path base) throws IOException
	{
		List<String> prefix = git.lines("rev-parse", "--show-cdup");
		if (!prefix.stream().allMatch(String::isEmpty))
			throw new IllegalArgumentException(base + " is not the top-level directory of its repository.");
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
	 * @param base the base worktree
	 * @return {@code true} if this method appended the line
	 * @throws IOException if Git fails or the exclude cannot be written
	 */
	private static boolean ensureWorkDirectoryIgnored(Git git, Path base) throws IOException
	{
		Path exclude = base.resolve(git.lines("rev-parse", "--git-path", "info/exclude").getFirst());
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
	 * Records further participants in the fork and writes them into the optimization worktree. A participant that the
	 * fork already holds is left alone. This fork stays unchanged: callers use the returned fork, whose inventory is
	 * extended, so any overlay on the old inventory must be saved again for the new one.
	 *
	 * @param base the base worktree that the fork was created from
	 * @param participants the paths to add, named as in {@link #create(Path, Path, Set)}
	 * @return the extended fork
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if a path cannot be recorded
	 * @throws WorkspaceException if a path does not exist
	 * @throws IOException if the files cannot be copied or written
	 */
	@Override
	public Fork addParticipants(Path base, Set<Path> participants) throws IOException
	{
		requireThat(base, "base").isNotNull();
		requireThat(participants, "participants").isNotNull();
		ForkTree extended = tree.extend(base, participants);
		Workspace extendedWorkspace = new Workspace(extended, tree.directory().getParent(), worktree);
		extendedWorkspace.materialize(Overlay.of(extended.inventory()));
		return new Fork(extended, extendedWorkspace, worktree, excludeAdded);
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

	/**
	 * Indicates whether creating this fork appended CAT's work directory to the local exclude, which CAT then tells
	 * the user.
	 *
	 * @return {@code true} if this fork appended the line
	 */
	public boolean excludeAdded()
	{
		return excludeAdded;
	}
}
