package io.github.cowwoc.cat.common.optimizer.workspace;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.port.contract.WorkspaceContract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.annotations.Test;

/**
 * Runs {@link WorkspaceContract} against {@link Workspace}.
 */
public final class WorkspaceStagingContractTest
{
	/**
	 * Creates the test.
	 */
	public WorkspaceStagingContractTest()
	{
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
			new WorkspaceContract(new ContractWorkspace(root, false, true)).
				stagesCandidateBytes();
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
			new WorkspaceContract(new ContractWorkspace(root, false, true)).
				reusesStagedSource();
		}
	}

	/**
	 * Verifies that staged deletion is idempotent and the source can be rebuilt.
	 *
	 * @throws IOException if the fixture or check fails
	 */
	@Test
	public void deletesAndRebuildsStagedSource() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, false, true)).
				deletesAndRebuildsStagedSource();
		}
	}

	/**
	 * Verifies that authoring copies are rooted at their directories and remain independent.
	 *
	 * @throws IOException if the fixture or check fails
	 */
	@Test
	public void preparesIndependentAuthoringCopies() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, false, true)).
				preparesIndependentAuthoringCopies();
		}
	}

	/**
	 * Verifies that effects are separate from their source authoring copy.
	 *
	 * @throws IOException if the fixture or check fails
	 */
	@Test
	public void scansIndependentAuthorEffects() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, false, true)).
				scansIndependentAuthorEffects();
		}
	}

	/**
	 * Verifies that a session without edits scans as an unchanged effects tree.
	 *
	 * @throws IOException if the fixture or check fails
	 */
	@Test
	public void scansUnchangedAuthorEffects() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, false, true)).scansUnchangedAuthorEffects();
		}
	}

	/**
	 * Verifies that relative workspace and effects paths produce inventories with absolute roots.
	 *
	 * @throws IOException if the fixture or check fails
	 */
	@Test
	public void acceptsRelativeDirectories() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			Path relativeRoot = Path.of("").toAbsolutePath().relativize(root);
			WorkspaceContract contract = new WorkspaceContract(new ContractWorkspace(relativeRoot, false, true));
			contract.preparesIndependentAuthoringCopies();
			contract.scansUnchangedAuthorEffects();
			contract.scansIndependentAuthorEffects();
		}
	}

	/**
	 * Verifies that deleting an authoring copy leaves another copy available.
	 *
	 * @throws IOException if the fixture or check fails
	 */
	@Test
	public void deletesOnlySelectedAuthoringCopy() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, false, true)).
				deletesOnlySelectedAuthoringCopy();
		}
	}

	/**
	 * Verifies that worktree scans observe edits made outside the workspace port.
	 *
	 * @throws IOException if the fixture or check fails
	 */
	@Test
	public void scansWorktreeChanges() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, false, true)).
				scansWorktreeChanges();
		}
	}

	/**
	 * Verifies that materialization applies the candidate and can restore the fork.
	 *
	 * @throws IOException if the fixture or check fails
	 */
	@Test
	public void materializesCandidateAndRestoresFork() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, false, true)).
				materializesCandidateAndRestoresFork();
		}
	}
}
