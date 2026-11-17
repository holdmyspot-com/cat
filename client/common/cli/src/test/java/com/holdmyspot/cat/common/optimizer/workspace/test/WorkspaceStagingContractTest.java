package com.holdmyspot.cat.common.optimizer.workspace.test;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.port.contract.test.WorkspaceContract;
import com.holdmyspot.cat.common.optimizer.workspace.Workspace;
import com.holdmyspot.cat.common.optimizer.workspace.WorkspaceException;

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
	 * Verifies that the real workspace stages candidate bytes at recorded link destinations.
	 *
	 * @throws IOException if the fixture or check fails
	 */
	@Test
	public void stagesRecordedLinksWithCandidateBytes() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, false, true, ContractWorkspace.LinkFixture.STAGING)).
				stagesRecordedLinksWithCandidateBytes();
		}
	}

	/**
	 * Verifies that the real workspace rejects cyclic links on repeated staging attempts.
	 *
	 * @throws IOException if the fixture or refusal check fails
	 */
	@Test(expectedExceptions = WorkspaceException.class,
		expectedExceptionsMessageRegExp = ".*links[/\\\\]cycle-one: link cycle.*")
	public void rejectsCyclicLinksWithoutCachingSource() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, false, true, ContractWorkspace.LinkFixture.CYCLE)).
				rejectsCyclicLinksWithoutCachingSource();
		}
	}

	/**
	 * Verifies that the real workspace rejects an excluded missing link on repeated staging attempts.
	 *
	 * @throws IOException if the fixture or refusal check fails
	 */
	@Test(expectedExceptions = WorkspaceException.class,
		expectedExceptionsMessageRegExp = ".*links[/\\\\]missing-alias.*missing target.*")
	public void rejectsMissingLinksWithoutCachingSource() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, false, true, ContractWorkspace.LinkFixture.MISSING)).
				rejectsMissingLinksWithoutCachingSource();
		}
	}

	/**
	 * Verifies that the workspace reads the files of a saved candidate.
	 *
	 * @throws IOException if the fixture or check fails
	 */
	@Test
	public void readsFilesOfASavedCandidate() throws IOException
	{
		// Requirement: R-E18
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, false, true)).readsFilesOfASavedCandidate();
		}
	}

	/**
	 * Verifies that the real workspace reads its recorded bytes after the live project file is changed and deleted,
	 * and that candidate writes take precedence.
	 *
	 * @throws IOException if the fixture or check fails
	 */
	@Test
	public void readsCapturedFilesAfterProjectChanges() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, false, true)).readsCapturedFilesAfterProjectChanges();
		}
	}

	/**
	 * Verifies that the real workspace preserves an externally changed intact worktree during reconnect.
	 *
	 * @throws IOException if the fixture or check fails
	 */
	@Test
	public void reconnectPreservesIntactWorktree() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, false, true)).reconnectPreservesIntactWorktree();
		}
	}

	/**
	 * Verifies that the workspace copies a plugin base by the digest of its files.
	 *
	 * @throws IOException if the fixture or check fails
	 */
	@Test
	public void copiesAPluginBaseByTheDigestOfItsFiles() throws IOException
	{
		// Requirement: R-E18
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, false, true)).
				copiesAPluginBaseByTheDigestOfItsFiles();
		}
	}

	/**
	 * Verifies that the workspace copies a plugin per staged source and set of removals.
	 *
	 * @throws IOException if the fixture or check fails
	 */
	@Test
	public void copiesAPluginPerStagedSourceAndRemovals() throws IOException
	{
		// Requirement: R-E18
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, false, true)).
				copiesAPluginPerStagedSourceAndRemovals();
		}
	}

	/**
	 * Verifies that real plugin copies apply mapped physical source files and retain base files.
	 *
	 * @throws IOException if the fixture or check fails
	 */
	@Test
	public void copiesMappedStagedFilesIntoPlugins() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, false, true)).
				copiesMappedStagedFilesIntoPlugins();
		}
	}

	/**
	 * Verifies that real plugin copies apply dependent removals to mapped files.
	 *
	 * @throws IOException if the fixture or check fails
	 */
	@Test
	public void appliesPluginRemovalsAfterMappings() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, false, true)).
				appliesPluginRemovalsAfterMappings();
		}
	}

	/**
	 * Verifies that real plugin copying rejects an installed plugin without its base.
	 *
	 * @throws IOException if the fixture or refusal check fails
	 */
	@Test
	public void rejectsInstalledPluginWithoutBase() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, false, true)).
				rejectsInstalledPluginWithoutBase();
		}
	}

	/**
	 * Verifies that real plugin copying rejects a built plugin without its base.
	 *
	 * @throws IOException if the fixture or refusal check fails
	 */
	@Test
	public void rejectsBuiltPluginWithoutBase() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, false, true)).
				rejectsBuiltPluginWithoutBase();
		}
	}

	/**
	 * Verifies that real plugin copying rejects a dependent absent from the plugin.
	 *
	 * @throws IOException if the fixture or refusal check fails
	 */
	@Test
	public void rejectsMissingPluginDependent() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, false, true)).
				rejectsMissingPluginDependent();
		}
	}

	/**
	 * Verifies that real plugin copies reject changed stored bases for installed, uncached built and cached built
	 * plugins.
	 *
	 * @throws IOException if the fixture or refusal check fails
	 */
	@Test
	public void rejectsChangedPluginBase() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, false, true)).rejectsChangedPluginBase();
		}
	}

	/**
	 * Verifies that the workspace's worktree is an absolute directory.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void locatesTheWorktree() throws IOException
	{
		// Requirement: R-E18
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, false, true)).locatesTheWorktree();
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
			new WorkspaceContract(new ContractWorkspace(root, false, true)).
				stagesSelectedFiles();
		}
	}

	/**
	 * Verifies that exclusion variants stage and remove independent file sets.
	 *
	 * @throws IOException if the fixture or check fails
	 */
	@Test
	public void stagesExcludedFiles() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, false, true)).stagesExcludedFiles();
		}
	}

	/**
	 * Verifies that staged deletion removes every variant and preserves another candidate's source.
	 *
	 * @throws IOException if the fixture or check fails
	 */
	@Test
	public void deletesVariantsKeepsOtherCandidates() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, false, true)).deletesVariantsKeepsOtherCandidates();
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
	public void scansIndependentPromptAuthorEffects() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, false, true)).
				scansIndependentPromptAuthorEffects();
		}
	}

	/**
	 * Verifies that a session without edits scans as an unchanged effects tree.
	 *
	 * @throws IOException if the fixture or check fails
	 */
	@Test
	public void scansUnchangedPromptAuthorEffects() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-contract-"));
			new WorkspaceContract(new ContractWorkspace(root, false, true)).scansUnchangedPromptAuthorEffects();
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
