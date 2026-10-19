package com.holdmyspot.cat.common.optimizer.port.test;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.port.contract.test.WorkspaceContract;
import com.holdmyspot.cat.common.optimizer.workspace.test.ContractWorkspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Runs {@link WorkspaceContract} against {@link FakeWorkspacePort}.
 */
public final class FakeWorkspaceContractTest
{
	/**
	 * Creates the test.
	 */
	public FakeWorkspaceContractTest()
	{
	}

	/**
	 * Returns the fake modes: staged files and directories represented only in memory.
	 *
	 * @return the fake modes
	 */
	@DataProvider
	public Object[][] modes()
	{
		return new Object[][] {{true}, {false}};
	}

	/**
	 * Verifies that staging preserves candidate bytes and omits deleted files.
	 *
	 * @throws IOException if the fixture or check fails
	 */
	@Test
	public void stagesCandidateBytes() throws IOException
	{
		// Requirement: R-E18
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, true, true)).
				stagesCandidateBytes();
		}
	}

	/**
	 * Verifies that a staged source with a selection holds only the selected files.
	 *
	 * @throws IOException if the fixture or check fails
	 */
	@Test
	public void stagesSelectedFiles() throws IOException
	{
		// Requirement: R-W15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, true, true)).
				stagesSelectedFiles();
		}
	}

	/**
	 * Verifies that repeated staging reuses the cached source.
	 *
	 * @throws IOException if the fixture or check fails
	 */
	@Test
	public void reusesStagedSource() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, true, true)).
				reusesStagedSource();
		}
	}

	/**
	 * Verifies that staged deletion is idempotent and the source can be rebuilt.
	 *
	 * @param stagedFiles whether the fake writes staged files
	 * @throws IOException if the fixture or check fails
	 */
	@Test(dataProvider = "modes")
	public void deletesAndRebuildsStagedSource(boolean stagedFiles) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, true, stagedFiles)).
				deletesAndRebuildsStagedSource();
		}
	}

	/**
	 * Verifies that authoring copies are rooted at their directories and remain independent.
	 *
	 * @param stagedFiles whether the fake writes staged files
	 * @throws IOException if the fixture or check fails
	 */
	@Test(dataProvider = "modes")
	public void preparesIndependentAuthoringCopies(boolean stagedFiles) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, true, stagedFiles)).
				preparesIndependentAuthoringCopies();
		}
	}

	/**
	 * Verifies that effects are separate from their source authoring copy.
	 *
	 * @param stagedFiles whether the fake writes staged files
	 * @throws IOException if the fixture or check fails
	 */
	@Test(dataProvider = "modes")
	public void scansIndependentPromptAuthorEffects(boolean stagedFiles) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, true, stagedFiles)).
				scansIndependentPromptAuthorEffects();
		}
	}

	/**
	 * Verifies that a prompt author session without edits scans as an unchanged effects tree.
	 *
	 * @param stagedFiles whether the fake writes staged files
	 * @throws IOException if the fixture or check fails
	 */
	@Test(dataProvider = "modes")
	public void scansUnchangedPromptAuthorEffects(boolean stagedFiles) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, true, stagedFiles)).scansUnchangedPromptAuthorEffects();
		}
	}

	/**
	 * Verifies that relative copy and effects paths produce inventories with absolute roots.
	 *
	 * @throws IOException if the fixture or check fails
	 */
	@Test
	public void acceptsRelativeDirectories() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			// Under the working directory, so that a relative path reaches it even when the temporary directory is on
			// another drive
			Path relativeRoot = cleanup.add(Files.createTempDirectory(Path.of("target"), "workspace-contract-"));
			WorkspaceContract contract = new WorkspaceContract(new ContractWorkspace(relativeRoot, true, false));
			contract.preparesIndependentAuthoringCopies();
			contract.scansUnchangedPromptAuthorEffects();
			contract.scansIndependentPromptAuthorEffects();
		}
	}

	/**
	 * Verifies that deleting an authoring copy leaves another copy available.
	 *
	 * @param stagedFiles whether the fake writes staged files
	 * @throws IOException if the fixture or check fails
	 */
	@Test(dataProvider = "modes")
	public void deletesOnlySelectedAuthoringCopy(boolean stagedFiles) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, true, stagedFiles)).
				deletesOnlySelectedAuthoringCopy();
		}
	}

	/**
	 * Verifies that worktree scans observe edits made outside the workspace port.
	 *
	 * @param stagedFiles whether the fake writes staged files
	 * @throws IOException if the fixture or check fails
	 */
	@Test(dataProvider = "modes")
	public void scansWorktreeChanges(boolean stagedFiles) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, true, stagedFiles)).
				scansWorktreeChanges();
		}
	}

	/**
	 * Verifies that materialization applies the candidate and can restore the fork.
	 *
	 * @param stagedFiles whether the fake writes staged files
	 * @throws IOException if the fixture or check fails
	 */
	@Test(dataProvider = "modes")
	public void materializesCandidateAndRestoresFork(boolean stagedFiles) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, true, stagedFiles)).
				materializesCandidateAndRestoresFork();
		}
	}
}
