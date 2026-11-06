package com.holdmyspot.cat.common.optimizer.workspace;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.SessionDirectory;
import com.holdmyspot.cat.common.SessionIgnore;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.git.Git;
import com.holdmyspot.cat.common.optimizer.inventory.CharacterDevices;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.inventory.FileSystemCharacterDevices;
import com.holdmyspot.cat.common.optimizer.inventory.InventoryScanner;
import com.holdmyspot.cat.common.optimizer.inventory.Overlay;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A campaign's fork of a base worktree: the recorded prompt system and the detached optimization worktree at the base's
 * {@code HEAD}.
 * <p>
 * Creating a fork requires a clean base worktree and a directory in which this process can register the optimization
 * worktree with Git, ensures through the base's {@code .gitignore} that Git ignores CAT's session directories so that
 * campaign files never make the base dirty, records the files, and adds the optimization worktree. Every Git operation
 * runs through {@link Git}.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class Fork implements ForkView
{
	private static final String CLEAN_PATHSPEC_EXCLUDING_SESSIONS = ":(exclude)" + SessionDirectory.ROOT;
	private final ForkTree recorded;
	private final ParticipantSelection selection;
	private final ForkTree tree;
	private final Workspace workspace;
	private final Path worktree;
	private final Optional<String> ignoreNotice;
	private final FileReplacer replacer;

	/**
	 * Creates a fork.
	 *
	 * @param recorded every recorded file, including the participants that the selection leaves out
	 * @param selection the participants recorded after creation that the fork's files hold
	 * @param worktree the optimization worktree's directory
	 * @param ignoreNotice the notice, for the agent that reads the command's output, that creating the fork added CAT's
	 *   session directories to the base's {@code .gitignore} or could not
	 * @param replacer replaces the stored participant selection
	 */
	private Fork(ForkTree recorded, ParticipantSelection selection, Path worktree, Optional<String> ignoreNotice,
		FileReplacer replacer)
	{
		this.recorded = recorded;
		this.selection = selection;
		this.tree = recorded.reachableFrom(selection::holds);
		this.workspace = new Workspace(tree, recorded.directory().getParent(), worktree);
		this.worktree = worktree;
		this.ignoreNotice = ignoreNotice;
		this.replacer = replacer;
	}

	/**
	 * Forks a base worktree.
	 * <p>
	 * The base must have no staged, unstaged or untracked changes outside CAT's session directories. An untracked
	 * character device counts as absent: it is no change, and the fork neither copies nor records it. A
	 * {@code .gitignore} whose only change, unstaged, is the lines that CAT appends to ignore its session directories
	 * counts as clean, and the fork records it with those lines. Otherwise this method stops before it creates anything,
	 * including those lines.
	 *
	 * @param base the base worktree, which is the top-level directory of its repository
	 * @param campaign the campaign storage directory
	 * @param participants ignored paths and ignored files relative to {@code base}, and absolute paths outside it, that
	 *   take part in the campaign; tracked files, and the existing targets outside {@code base} that recorded links
	 *   reach, always take part
	 * @param replacer replaces the fork's stored participant selection
	 * @return the fork
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code base} is not the top-level directory of its repository, or a
	 *   participant is not a valid path (see {@link ForkTree#record(Path, Path, Set)})
	 * @throws WorkspaceException if the base has uncommitted changes, a participant does not exist, or this process
	 *   cannot write the directory in which Git registers linked worktrees
	 * @throws IOException if Git fails or the files cannot be copied
	 */
	public static Fork create(Path base, Path campaign, Set<Path> participants, FileReplacer replacer)
		throws IOException
	{
		return create(base, campaign, participants, replacer, new FileSystemCharacterDevices());
	}

	/**
	 * Forks a base worktree, as {@link #create(Path, Path, Set, FileReplacer)} does, treating the untracked files that
	 * a classifier names as character devices as absent.
	 *
	 * @param base         the base worktree, which is the top-level directory of its repository
	 * @param campaign     the campaign storage directory
	 * @param participants ignored paths and ignored files relative to {@code base}, and absolute paths outside it, that
	 *                     take part in the campaign; tracked files, and the existing targets outside {@code base} that
	 *                     recorded links reach, always take part
	 * @param replacer     replaces the fork's stored participant selection
	 * @param devices      tells which files are character devices
	 * @return the fork
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code base} is not the top-level directory of its repository, or a
	 *                                  participant is not a valid path (see {@link ForkTree#record(Path, Path, Set)})
	 * @throws WorkspaceException       if the base has uncommitted changes, a participant does not exist, or this
	 *                                  process cannot write the directory in which Git registers linked worktrees
	 * @throws IOException              if Git fails or the files cannot be copied
	 */
	public static Fork create(Path base, Path campaign, Set<Path> participants, FileReplacer replacer,
		CharacterDevices devices) throws IOException
	{
		return create(base, campaign, participants, replacer, devices, new FileSystemWritableDirectories());
	}

	/**
	 * Forks a base worktree, as {@link #create(Path, Path, Set, FileReplacer, CharacterDevices)} does, confirming
	 * through {@code writable} that this process can write the directory in which Git registers the optimization
	 * worktree.
	 *
	 * @param base         the base worktree, which is the top-level directory of its repository
	 * @param campaign     the campaign storage directory
	 * @param participants ignored paths and ignored files relative to {@code base}, and absolute paths outside it, that
	 *                     take part in the campaign; tracked files, and the existing targets outside {@code base} that
	 *                     recorded links reach, always take part
	 * @param replacer     replaces the fork's stored participant selection
	 * @param devices      tells which files are character devices
	 * @param writable     confirms that this process can write a directory
	 * @return the fork
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code base} is not the top-level directory of its repository, or a
	 *                                  participant is not a valid path (see {@link ForkTree#record(Path, Path, Set)})
	 * @throws WorkspaceException       if the base has uncommitted changes, a participant does not exist, or this
	 *                                  process cannot write the directory in which Git registers linked worktrees
	 * @throws IOException              if Git fails or the files cannot be copied
	 */
	public static Fork create(Path base, Path campaign, Set<Path> participants, FileReplacer replacer,
		CharacterDevices devices, WritableDirectories writable) throws IOException
	{
		requireThat(base, "base").isNotNull();
		requireThat(campaign, "campaign").isNotNull();
		requireThat(participants, "participants").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		requireThat(devices, "devices").isNotNull();
		requireThat(writable, "writable").isNotNull();
		Path normalizedBase = base.toAbsolutePath().normalize();
		Path normalizedCampaign = campaign.toAbsolutePath().normalize();
		Git git = new Git(normalizedBase);
		requireTopLevel(git, normalizedBase);
		requireClean(git, normalizedBase, devices);
		Path registrations = worktreeRegistrations(git);
		requireWritable(writable, registrations);
		Optional<String> ignoreNotice = SessionIgnore.ensure(normalizedBase).
			map(SessionIgnore.IgnoreNotice::forAgent);

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
			addWorktree(git, worktree, registrations);
			worktreeAdded = true;
			Fork fork = new Fork(tree, ParticipantSelection.NONE, worktree, ignoreNotice, replacer);
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
	 * Lists the files that a project's Git index records as symbolic links.
	 *
	 * @param base the project root
	 * @return the files, relative to {@code base}, or an empty set if {@code base} is not inside a Git work tree
	 * @throws NullPointerException if {@code base} is null
	 * @throws IOException          if Git fails inside a work tree
	 */
	public static Set<Path> trackedLinks(Path base) throws IOException
	{
		requireThat(base, "base").isNotNull();
		Git git = new Git(base.toAbsolutePath().normalize());
		if (git.status("rev-parse", "--is-inside-work-tree") != 0)
			return Set.of();
		return ForkGit.trackedLinks(new String(git.bytes("ls-files", "--stage", "-z"), UTF_8));
	}

	/**
	 * Returns the Git diff of paths in a worktree against its {@code HEAD}, including files that {@code HEAD} lacks. A
	 * temporary index inside the Git directory marks each path that exists as an intended addition, so the worktree's
	 * index stays untouched.
	 *
	 * @param base the worktree
	 * @param paths the paths to compare, relative to {@code base}
	 * @return the diff, or an empty string if no path differs from {@code HEAD}
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException if Git fails
	 */
	public static String diff(Path base, List<Path> paths) throws IOException
	{
		requireThat(base, "base").isNotNull();
		requireThat(paths, "paths").isNotNull();
		List<String> targets = new ArrayList<>();
		List<String> existing = new ArrayList<>();
		for (Path path : paths)
		{
			targets.add(PortablePaths.text(path));
			if (Files.exists(base.resolve(path), LinkOption.NOFOLLOW_LINKS))
				existing.add(PortablePaths.text(path));
		}
		if (targets.isEmpty())
			return "";
		Git git = new Git(base);
		Path gitDirectory = Path.of(git.run("rev-parse", "--absolute-git-dir").strip());
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path index = cleanup.add(Files.createTempDirectory(gitDirectory, "cat-delivery-")).resolve("index");
			git.lines(index, "read-tree", "HEAD");
			if (!existing.isEmpty())
				git.lines(index, ForkGit.intentToAdd(existing));
			return ForkGit.diffText(git.lines(index, ForkGit.diffAgainstHead(targets)));
		}
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
		return ForkGit.trackedFiles(new String(git.bytes("ls-files", "-z"), UTF_8));
	}

	/**
	 * Removes what {@link #create(Path, Path, Set, FileReplacer)} created after it recorded the files, so that a retry
	 * starts clean.
	 * The lines that CAT added to the base's {@code .gitignore} stay, because removing them would let campaign files
	 * dirty the base. A failure to undo is
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
	 * Opens a fork that {@link #create(Path, Path, Set, FileReplacer)} created earlier, for example to resume a
	 * campaign.
	 *
	 * @param base the base worktree
	 * @param campaign the campaign storage directory
	 * @param replacer replaces the fork's stored participant selection
	 * @return the fork, which reports no notice about the base's {@code .gitignore}
	 * @throws NullPointerException if any of the arguments are null
	 * @throws WorkspaceException if the campaign holds no fork
	 * @throws IOException if the recorded files cannot be read
	 */
	public static Fork open(Path base, Path campaign, FileReplacer replacer) throws IOException
	{
		requireThat(base, "base").isNotNull();
		requireThat(campaign, "campaign").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		Path normalizedCampaign = campaign.toAbsolutePath().normalize();
		ForkTree tree = ForkTree.open(base, normalizedCampaign);
		Path worktree = worktreeOf(normalizedCampaign);
		if (!Files.isDirectory(worktree))
			throw new WorkspaceException("The campaign directory " + campaign + " holds no optimization worktree.");
		return new Fork(tree, ParticipantSelection.read(normalizedCampaign), worktree, Optional.empty(), replacer);
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
	 * Returns the directory in which Git registers a repository's linked worktrees: {@code worktrees} in its common Git
	 * directory, which the main worktree and every linked worktree share.
	 *
	 * @param git the base's Git
	 * @return the directory's absolute path
	 * @throws IOException if Git fails
	 */
	private static Path worktreeRegistrations(Git git) throws IOException
	{
		return ForkGit.worktreeRegistrations(git.run("rev-parse", "--path-format=absolute", "--git-common-dir"));
	}

	/**
	 * Requires that this process can write the directory in which Git registers linked worktrees, creating it if it
	 * does not exist.
	 *
	 * @param writable      confirms that this process can write a directory
	 * @param registrations the directory
	 * @throws WorkspaceException if this process cannot write the directory
	 */
	private static void requireWritable(WritableDirectories writable, Path registrations) throws WorkspaceException
	{
		try
		{
			writable.requireWritable(registrations);
		}
		catch (IOException e)
		{
			WorkspaceException refusal = unwritableRegistrations(registrations);
			refusal.initCause(e);
			throw refusal;
		}
	}

	/**
	 * Adds the optimization worktree, detached at the base's {@code HEAD}.
	 *
	 * @param git           the base's Git
	 * @param worktree      the optimization worktree's directory
	 * @param registrations the directory in which Git registers linked worktrees
	 * @throws WorkspaceException if Git cannot write {@code registrations} because the file system is read-only or
	 *                            denies the write
	 * @throws IOException        if Git fails otherwise
	 */
	private static void addWorktree(Git git, Path worktree, Path registrations) throws IOException
	{
		try
		{
			git.run("worktree", "add", "--detach", worktree.toString(), "HEAD");
		}
		catch (IOException e)
		{
			if (!ForkGit.deniedRegistrationWrite(e.getMessage(), registrations))
				throw e;
			WorkspaceException refusal = unwritableRegistrations(registrations);
			refusal.initCause(e);
			throw refusal;
		}
	}

	/**
	 * Returns the refusal, for the user, of a fork whose worktree Git cannot register.
	 *
	 * @param registrations the directory in which Git registers linked worktrees
	 * @return the refusal
	 */
	private static WorkspaceException unwritableRegistrations(Path registrations)
	{
		return new WorkspaceException("CAT cannot create the optimization worktree: this session cannot write " +
			registrations + ". Allow this session to write that directory, for example in your harness's sandbox " +
			"settings, then run start again.");
	}

	/**
	 * Requires that the base is the top-level directory of its repository, whose {@code .gitignore} holds the pattern
	 * that ignores CAT's session directories.
	 *
	 * @param git the base's Git
	 * @param base the base worktree
	 * @throws IOException if Git fails
	 */
	private static void requireTopLevel(Git git, Path base) throws IOException
	{
		if (!ForkGit.isTopLevel(git.lines("rev-parse", "--show-cdup")))
			throw new IllegalArgumentException(base + " is not the top-level directory of its repository.");
	}

	/**
	 * Requires that the base has no staged, unstaged or untracked changes, ignoring CAT's session directories, the
	 * untracked character devices, which count as absent, and a {@code .gitignore} whose only change is the unstaged
	 * lines that CAT appends.
	 *
	 * @param git the base's Git
	 * @param base the base worktree
	 * @param devices tells which files are character devices
	 * @throws WorkspaceException if the base has changes
	 * @throws IOException if Git fails or an untracked file's type cannot be read
	 */
	private static void requireClean(Git git, Path base, CharacterDevices devices) throws IOException
	{
		String status = git.run("status", "--porcelain", "-z", "--untracked-files=all", "--", ".",
			CLEAN_PATHSPEC_EXCLUDING_SESSIONS);
		List<String> changes = ForkGit.changes(status, entry ->
		{
			String untracked = ForkGit.untrackedPath(entry);
			return (untracked != null && isCharacterDevice(base.resolve(untracked), devices)) ||
				isOnlyIgnoreLines(git, base, entry);
		});
		if (!changes.isEmpty())
			throw ForkGit.dirtyBase(base, changes);
	}

	/**
	 * Indicates whether a file is a character device.
	 *
	 * @param file the file
	 * @param devices tells which files are character devices
	 * @return {@code true} if {@code file} is a character device; {@code false} if it is another kind of file or no
	 *   longer exists
	 * @throws IOException if the file's type cannot be read
	 */
	private static boolean isCharacterDevice(Path file, CharacterDevices devices) throws IOException
	{
		BasicFileAttributes attributes;
		try
		{
			attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		}
		catch (NoSuchFileException _)
		{
			return false;
		}
		return devices.isCharacterDevice(file, attributes);
	}

	/**
	 * Indicates whether a {@code git status --porcelain} entry is the base's {@code .gitignore} whose only change is the
	 * lines that CAT appends to ignore its session directories, unstaged, with nothing else in the file changed.
	 *
	 * @param git the base's Git
	 * @param base the base worktree
	 * @param entry the status entry
	 * @return {@code true} if the entry is that change and nothing more
	 * @throws IOException if Git fails or the file cannot be read
	 */
	private static boolean isOnlyIgnoreLines(Git git, Path base, String entry) throws IOException
	{
		ForkGit.IgnoreFileChange change = ForkGit.ignoreFileChange(entry);
		if (change == ForkGit.IgnoreFileChange.NONE)
			return false;
		String before = "";
		if (change == ForkGit.IgnoreFileChange.UNSTAGED)
			before = new String(git.bytes("show", ":" + SessionIgnore.FILE_NAME), UTF_8);
		Path file = base.resolve(SessionIgnore.FILE_NAME);
		return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) &&
			SessionIgnore.isAppended(before, Files.readString(file, UTF_8));
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
		next[0].write(campaign, replacer);
		ForkTree extended = recorded.extend(base, chosen, target ->
		{
			next[0] = next[0].withTarget(target);
			next[0].write(campaign, replacer);
		});
		Fork fork = new Fork(extended, next[0], worktree, ignoreNotice, replacer);
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
		stored[0].write(campaign, replacer);
		ForkTree extended = recorded.extend(base, chosen, target ->
		{
			stored[0] = stored[0].withTarget(target);
			stored[0].write(campaign, replacer);
		});
		Set<Path> held = new HashSet<>(selection.selected());
		held.addAll(chosen);
		return new Fork(extended, stored[0].withSelected(held), worktree, ignoreNotice, replacer);
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
		return new Fork(recorded, selection.withSelected(held), worktree, ignoreNotice, replacer);
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
			stored.writeVersion(campaign, current.identity(), replacer);
		stored.withSelected(selection.selected()).write(campaign, replacer);
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
	public Optional<String> ignoreNotice()
	{
		return ignoreNotice;
	}
}
