package com.holdmyspot.cat.common.optimizer.cli.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.inventory.InventoryScanner;
import com.holdmyspot.cat.common.optimizer.inventory.Overlay;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.port.WorkspacePort;
import com.holdmyspot.cat.common.optimizer.workspace.Fork;
import com.holdmyspot.cat.common.optimizer.workspace.ForkGit;
import com.holdmyspot.cat.common.optimizer.workspace.ForkTree;
import com.holdmyspot.cat.common.optimizer.workspace.ForkView;
import com.holdmyspot.cat.common.optimizer.workspace.Forks;
import com.holdmyspot.cat.common.optimizer.workspace.test.PlainWorktreeLinks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@link Forks} for a project that is no Git repository. Everything a fork does with files is real: {@link ForkTree}
 * records them, {@link Fork} holds them and the fork's workspace writes the optimization worktree. Only what Git
 * would answer is supplied by the test: the tracked files, the tracked symbolic links, whether the base is dirty, the
 * notice about the base's {@code .gitignore} and the failure of a removal. {@code GitlessForksContractTest} runs on
 * this class the {@code ForksContract} cases that {@code ForkTest} runs on {@code GitForks}, so each answer is one that
 * Git gives. Its diff treats every path as unchanged since {@code HEAD}, because no Git records the base; tests that
 * read a diff use {@code GitForks}.
 */
public final class GitlessForks implements Forks
{
	private final Set<Path> tracked;
	private final FileReplacer replacer;
	private Set<Path> trackedLinks = Set.of();
	private boolean dirty;
	private Optional<String> ignoreNotice = Optional.empty();
	private Optional<IOException> removalFailure = Optional.empty();

	/**
	 * Creates the forks of a project.
	 *
	 * @param tracked the files, relative to the project, that Git would track
	 * @param replacer replaces each fork's stored participant selection
	 * @throws NullPointerException if any argument is null
	 */
	public GitlessForks(Set<Path> tracked, FileReplacer replacer)
	{
		requireThat(tracked, "tracked").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		this.tracked = Set.copyOf(tracked);
		this.replacer = replacer;
	}

	/**
	 * Makes the next {@code create} refuse the base as Git reports it dirty.
	 */
	void makeDirty()
	{
		dirty = true;
	}

	/**
	 * Supplies the notice that creating a fork returns when Git did not ignore CAT's session directories, so that CAT
	 * added them to the base's {@code .gitignore}.
	 *
	 * @param notice the notice for the user
	 */
	void noticeIgnoreOnCreate(String notice)
	{
		ignoreNotice = Optional.of(notice);
	}

	/**
	 * Supplies the symbolic links that Git's index records, independently of the checkout.
	 *
	 * @param trackedLinks the project-relative symbolic-link paths
	 */
	void supplyTrackedLinks(Set<Path> trackedLinks)
	{
		this.trackedLinks = Set.copyOf(trackedLinks);
	}

	/**
	 * Makes every later {@code remove} fail with the given failure and leave the optimization worktree in place, as the
	 * removal does when Git refuses it, until {@link #stopFailingRemoval()}.
	 *
	 * @param failure the failure that Git's failure would cause
	 * @throws NullPointerException if {@code failure} is null
	 */
	public void failRemovalWith(IOException failure)
	{
		requireThat(failure, "failure").isNotNull();
		removalFailure = Optional.of(failure);
	}

	/**
	 * Makes every later {@code remove} succeed again, as when the base's Git works again.
	 */
	public void stopFailingRemoval()
	{
		removalFailure = Optional.empty();
	}

	@Override
	public ForkView create(Path base, Path campaign, Set<Path> participants) throws IOException
	{
		if (dirty)
			throw ForkGit.dirtyBase(base, List.of("?? scratch.md"));
		Set<Path> paths = new HashSet<>(participants);
		for (Path path : tracked)
		{
			if (!InventoryScanner.isOmitted(path))
				paths.add(path);
		}
		ForkTree.record(base.toAbsolutePath().normalize(), campaign.toAbsolutePath().normalize(), paths);
		// A Git worktree holds the tracked files apart from the base; here the workspace writes all of them
		Files.createDirectories(campaign.resolve("worktree"));
		Fork fork = Fork.open(base, campaign, replacer, PlainWorktreeLinks.INSTANCE);
		fork.workspace().materialize(Overlay.of(fork.inventory()));
		return new Noticing(fork, ignoreNotice);
	}

	@Override
	public Set<Path> tracked(Path base)
	{
		return tracked;
	}

	@Override
	public Set<Path> trackedLinks(Path base)
	{
		return trackedLinks;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * With no Git, nothing differs from {@code HEAD}.
	 */
	@Override
	public String diff(Path base, List<Path> paths)
	{
		return "";
	}

	@Override
	public ForkView open(Path base, Path campaign) throws IOException
	{
		return new Noticing(Fork.open(base, campaign, replacer, PlainWorktreeLinks.INSTANCE), Optional.empty());
	}

	@Override
	public boolean exists(Path campaign)
	{
		return Files.isDirectory(campaign.resolve("worktree"));
	}

	@Override
	public void remove(Path base, Path campaign) throws IOException
	{
		if (removalFailure.isPresent())
			throw removalFailure.get();
		TemporaryDirectories.deleteRecursively(campaign.resolve("worktree"));
	}

	/**
	 * A fork that reports the notice that creating it returned, and whose forks derived from it report the same.
	 *
	 * @param fork the real fork
	 * @param notice the notice about the base's {@code .gitignore}
	 */
	private record Noticing(ForkView fork, Optional<String> notice) implements ForkView
	{
		@Override
		public Optional<String> ignoreNotice()
		{
			return notice;
		}

		@Override
		public FileInventory inventory()
		{
			return fork.inventory();
		}

		@Override
		public WorkspacePort workspace()
		{
			return fork.workspace();
		}

		@Override
		public ForkView selectParticipants(Path base, Set<Path> participants) throws IOException
		{
			return new Noticing(fork.selectParticipants(base, participants), notice);
		}

		@Override
		public ForkView extend(Path base, Set<Path> participants) throws IOException
		{
			return new Noticing(fork.extend(base, participants), notice);
		}

		@Override
		public ForkView including(Set<Path> participants)
		{
			return new Noticing(fork.including(participants), notice);
		}

		@Override
		public void adopt() throws IOException
		{
			fork.adopt();
		}

		@Override
		public Optional<FileInventory> version(Sha256 identity) throws IOException
		{
			return fork.version(identity);
		}
	}
}
