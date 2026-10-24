package com.holdmyspot.cat.common.optimizer.cli.test;

import static com.holdmyspot.cat.common.optimizer.workspace.test.ProjectFixtures.EXECUTABLE;
import static com.holdmyspot.cat.common.optimizer.workspace.test.ProjectFixtures.READ_WRITE;
import static com.holdmyspot.cat.common.optimizer.workspace.test.ProjectFixtures.writeFile;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.workspace.ForkView;
import com.holdmyspot.cat.common.optimizer.workspace.test.ForksContract;
import com.holdmyspot.cat.common.test.ProgramFixture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.testng.annotations.Test;

/**
 * Runs the shared fork contract against {@link InMemoryForks}. These checks verify test-support assumptions; the
 * same contract runs against the real adapter in the workspace tests.
 */
public final class InMemoryForksTest
{
	/**
	 * Creates the test.
	 */
	public InMemoryForksTest()
	{
	}

	/**
	 * Verifies the fake's deselection, reopening and recorded-content restoration behavior.
	 *
	 * @throws IOException if fixture files cannot be used
	 */
	@Test
	public void leavesOutDeselectedParticipants() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-memory-fork-contract-"));
			contract(root).leavesOutDeselectedParticipants();
		}
	}

	/**
	 * Verifies the fake's prospective extension, adoption and earlier-version retention behavior.
	 *
	 * @throws IOException if fixture files cannot be used
	 */
	@Test
	public void extensionBecomesCurrentWhenAdopted() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-memory-fork-contract-"));
			contract(root).extensionBecomesCurrentWhenAdopted();
		}
	}

	/**
	 * Verifies that the fake materializes selected participants and refuses missing ones as the real adapter does.
	 *
	 * @throws IOException if fixture files cannot be used
	 */
	@Test
	public void addsParticipantsAfterCreation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-memory-fork-contract-"));
			contract(root).addsParticipantsAfterCreation();
		}
	}

	/**
	 * Verifies the fake deletes the worktree and refuses opening it after repeated removal.
	 *
	 * @throws IOException if fixture files or the contract check fail
	 */
	@Test
	public void removesExistingWorktree() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-memory-fork-contract-"));
			contract(root).removesExistingWorktree();
		}
	}

	/**
	 * Verifies the fake observes an independently removed worktree instead of relying only on its inventory map.
	 *
	 * @throws IOException if fixture files or the contract check fail
	 */
	@Test
	public void missingWorktreeIsNotAFork() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-memory-fork-contract-"));
			contract(root).missingWorktreeIsNotAFork();
		}
	}

	/**
	 * Verifies captured regular-file contents, executable modes and participants against the real adapter's contract.
	 *
	 * @throws IOException if fixture files or the contract check fail
	 */
	@Test
	public void recordsTrackedAndParticipantFiles() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-memory-fork-contract-"));
			Path base = Files.createDirectories(root.resolve("project"));
			writeFile(base.resolve("prompt.md"), "prompt", READ_WRITE);
			writeFile(base.resolve("ignored.md"), "ignored", READ_WRITE);
			writeFile(base.resolve(ProgramFixture.name("run")), ProgramFixture.TEXT, EXECUTABLE);
			Path external = writeFile(root.resolve("external/shared.md"), "shared", READ_WRITE);
			Path campaign = root.resolve("campaign");
			InMemoryForks forks = new InMemoryForks(base, Set.of(Path.of("prompt.md"),
				Path.of(ProgramFixture.name("run"))));
			ForkView fork = forks.create(base, campaign, Set.of(Path.of("ignored.md"), external));

			new ForksContract(forks, base, campaign).recordsTrackedAndParticipantFiles(fork, external);
		}
	}

	/**
	 * Verifies that a missing participant leaves no worktree or fork record, as the real adapter requires.
	 *
	 * @throws IOException if fixture files or the contract check fail
	 */
	@Test
	public void missingParticipantCreatesNoWorktree() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-memory-fork-contract-"));
			Path base = Files.createDirectories(root.resolve("project"));
			writeFile(base.resolve("prompt.md"), "prompt", READ_WRITE);
			InMemoryForks forks = new InMemoryForks(base, Set.of(Path.of("prompt.md")));

			new ForksContract(forks, base, root.resolve("campaign")).missingParticipantCreatesNoWorktree();
		}
	}

	/**
	 * Creates the caller-owned project and current fork required by the shared checks.
	 *
	 * @param root the isolated directory that the caller cleans up
	 * @return the checks for the prepared in-memory fork
	 * @throws IOException if fixture files cannot be created
	 */
	private static ForksContract contract(Path root) throws IOException
	{
		Path base = Files.createDirectories(root.resolve("project"));
		Files.writeString(base.resolve("prompt.md"), "prompt");
		Files.writeString(base.resolve("ignored.md"), "ignored");
		Path campaign = root.resolve("campaign");
		InMemoryForks forks = new InMemoryForks(base, Set.of(Path.of("prompt.md")));
		forks.create(base, campaign, Set.of());
		return new ForksContract(forks, base, campaign);
	}
}
