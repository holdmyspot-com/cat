package com.holdmyspot.cat.common.optimizer.cli.test;

import static com.holdmyspot.cat.common.optimizer.workspace.test.ProjectFixtures.EXECUTABLE;
import static com.holdmyspot.cat.common.optimizer.workspace.test.ProjectFixtures.READ_WRITE;
import static com.holdmyspot.cat.common.optimizer.workspace.test.ProjectFixtures.writeFile;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.optimizer.workspace.ForkView;
import com.holdmyspot.cat.common.optimizer.workspace.test.ForksContract;
import com.holdmyspot.cat.common.test.ProgramFixture;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Runs the {@link ForksContract} cases that {@code ForkTest} runs on {@code GitForks} against {@link GitlessForks}, so
 * that the forks that the optimizer's command and driver tests use answer as Git does. Each test owns a project that
 * is no Git repository: {@code prompt.md} with text {@code prompt} and the fixture program, both tracked, and
 * {@code ignored.md} with text {@code ignored}, which is not.
 */
public final class GitlessForksContractTest
{
	/**
	 * The files of the project that the forks treat as tracked by Git.
	 */
	private static final Set<Path> TRACKED = Set.of(Path.of("prompt.md"), Path.of(ProgramFixture.name("run")));

	/**
	 * Creates the test.
	 */
	public GitlessForksContractTest()
	{
	}

	/**
	 * Verifies that a base that Git reports dirty is refused before campaign storage is created.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesDirtyBase() throws IOException
	{
		// Requirement: R-W1
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			fixture.forks().makeDirty();

			fixture.contract().refusesDirtyBase();
		}
	}

	/**
	 * Verifies that creation reports the notice about the base's {@code .gitignore} when CAT added its lines, and that
	 * a reopened fork reports none.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void reportsIgnoreNoticeOnlyAtCreation() throws IOException
	{
		// Requirement: R-W1
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			fixture.forks().noticeIgnoreOnCreate("CAT added its session directories to .gitignore.");

			ForkView created = fixture.create(Set.of());

			fixture.contract().reportsIgnoreNoticeOnlyAtCreation(created, true);
		}
	}

	/**
	 * Verifies that creation reports no notice when Git already ignored CAT's session directories.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void reportsNoIgnoreNoticeWhenAlreadyIgnored() throws IOException
	{
		// Requirement: R-W1
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);

			ForkView created = fixture.create(Set.of());

			fixture.contract().reportsIgnoreNoticeOnlyAtCreation(created, false);
		}
	}

	/**
	 * Verifies the tracked files and links, the empty diff of unchanged files and of an empty selection, and that a
	 * project with no tracked links reports none.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void reportsProjectQueries() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			ForksContract contract = fixture.contract();
			contract.reportsTrackedFiles(TRACKED);
			contract.hasNoTrackedLinksOutsideRepository();
			contract.unchangedSelectionHasNoDiff(List.of(Path.of("prompt.md")));
			contract.emptySelectionHasNoDiff();

			fixture.forks().supplyTrackedLinks(Set.of(Path.of("alias.md")));

			contract.reportsTrackedLinks(Set.of(Path.of("alias.md")));
		}
	}

	/**
	 * Verifies that a created fork exists and reopens with the inventory that creation returned.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void createdForkCanBeReopened() throws IOException
	{
		// Requirement: R-W2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);

			ForkView created = fixture.create(Set.of());

			fixture.contract().createdForkCanBeReopened(created);
		}
	}

	/**
	 * Verifies that a fork captures the tracked files and the participants, and keeps the captured bytes after the base
	 * changes.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void recordsTrackedAndParticipantFiles() throws IOException
	{
		// Requirement: R-W2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			Path external = writeFile(fixture.base().resolveSibling("external/shared.md"), "shared", READ_WRITE);

			ForkView created = fixture.create(Set.of(Path.of("ignored.md"), external));

			fixture.contract().recordsTrackedAndParticipantFiles(created, external);
		}
	}

	/**
	 * Verifies that a missing participant stops the fork before a worktree or fork record appears.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void missingParticipantCreatesNoWorktree() throws IOException
	{
		// Requirement: R-W2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture.create(cleanup).contract().missingParticipantCreatesNoWorktree();
		}
	}

	/**
	 * Verifies that deselection leaves a participant out of the fork and that reselection restores its recorded
	 * content.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void leavesOutDeselectedParticipants() throws IOException
	{
		// Requirement: R-W2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			fixture.create(Set.of());

			fixture.contract().leavesOutDeselectedParticipants();
		}
	}

	/**
	 * Verifies that an extension becomes the current fork only when it is adopted.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void extensionBecomesCurrentWhenAdopted() throws IOException
	{
		// Requirement: R-W2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			fixture.create(Set.of());

			fixture.contract().extensionBecomesCurrentWhenAdopted();
		}
	}

	/**
	 * Verifies that participants selected after creation are recorded and materialized.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void addsParticipantsAfterCreation() throws IOException
	{
		// Requirement: R-W2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			fixture.create(Set.of());

			fixture.contract().addsParticipantsAfterCreation();
		}
	}

	/**
	 * Verifies that removing a fork deletes its worktree, makes it impossible to open, and can be repeated.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void removesExistingWorktree() throws IOException
	{
		// Requirement: R-W9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			fixture.create(Set.of());

			fixture.contract().removesExistingWorktree();
		}
	}

	/**
	 * Verifies that a removal that Git would refuse is reported and leaves the fork in place.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void reportsAFailedRemoval() throws IOException
	{
		// Requirement: R-W9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			fixture.create(Set.of());

			fixture.contract().reportsAFailedRemoval(() -> fixture.forks().failRemovalWith(
				new IOException("Git command failed: git worktree remove: fatal: not a git repository: .git")));
		}
	}

	/**
	 * Verifies that removing one campaign's fork keeps another campaign's fork of the same base.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void removalKeepsAnotherCampaignsFork() throws IOException
	{
		// Requirement: R-W9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			fixture.create(Set.of());

			fixture.contract().removalKeepsAnotherCampaignsFork();
		}
	}

	/**
	 * Verifies that a worktree removed outside the forks makes the fork absent and impossible to open.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void missingWorktreeIsNotAFork() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			fixture.create(Set.of());

			fixture.contract().missingWorktreeIsNotAFork();
		}
	}

	/**
	 * A test-owned project, the campaign storage of its current fork, and the forks under contract.
	 *
	 * @param base     the project directory
	 * @param campaign the current fork's campaign storage directory
	 * @param forks    the forks under contract
	 */
	private record Fixture(Path base, Path campaign, GitlessForks forks)
	{
		/**
		 * Creates the project in a directory that the cleanup scope owns.
		 *
		 * @param cleanup removes the project when the test ends
		 * @return the fixture
		 * @throws IOException if the project cannot be written
		 */
		static Fixture create(TemporaryDirectories cleanup) throws IOException
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-gitless-forks-"));
			Path base = Files.createDirectories(root.resolve("project"));
			writeFile(base.resolve("prompt.md"), "prompt", READ_WRITE);
			writeFile(base.resolve("ignored.md"), "ignored", READ_WRITE);
			writeFile(base.resolve(ProgramFixture.name("run")), ProgramFixture.TEXT, EXECUTABLE);
			return new Fixture(base, root.resolve("campaigns/c1"), new GitlessForks(TRACKED, Yielding.fileReplacer()));
		}

		/**
		 * Creates the current fork.
		 *
		 * @param participants the paths to record beyond the tracked files
		 * @return the fork that creation returned
		 * @throws IOException if the fork cannot be created
		 */
		ForkView create(Set<Path> participants) throws IOException
		{
			return forks.create(base, campaign, participants);
		}

		/**
		 * Returns the contract over this fixture's forks, project and current campaign.
		 *
		 * @return the contract
		 */
		ForksContract contract()
		{
			return new ForksContract(forks, base, campaign);
		}
	}
}
