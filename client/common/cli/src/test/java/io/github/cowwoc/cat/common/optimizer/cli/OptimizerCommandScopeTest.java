package io.github.cowwoc.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.campaign.Builder;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.Harness;
import io.github.cowwoc.cat.common.optimizer.port.FakeSessionPort;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tests the behavior that {@link OptimizerCommandScope} shares with every variant.
 */
public final class OptimizerCommandScopeTest
{
	/**
	 * Creates the test.
	 */
	public OptimizerCommandScopeTest()
	{
	}

	/**
	 * Verifies that the project root is the nearest directory at or above the working directory that holds
	 * {@code .git}, and that the store and the root stay the same for the scope's lifetime.
	 *
	 * @throws IOException if the project cannot be written
	 * @throws CommandRefusedException if the project is not found
	 */
	@Test
	public void resolvesProjectRootAboveWorkingDirectory() throws IOException, CommandRefusedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("optimizer-scope")).toRealPath();
			Files.createDirectory(root.resolve(".git"));
			Path directory = Files.createDirectories(root.resolve("a/b"));
			try (TestOptimizerCommandScope<FakeSessionPort> scope = TestOptimizerCommandScope.of(Harness.CLAUDE,
				directory))
			{
				requireThat(scope.projectRoot(), "projectRoot").isEqualTo(root);
				requireThat(scope.store() == scope.store(), "sameStore").isTrue();
			}
		}
	}

	/**
	 * Verifies that the held-out paths are those of the campaigns in the project's store, even when the command runs
	 * in a subdirectory of the project.
	 *
	 * @throws IOException if the project cannot be written
	 * @throws CommandRefusedException if the project is not found
	 */
	@Test
	public void listsHeldOutPathsOfProjectCampaigns() throws IOException, CommandRefusedException
	{
		// Requirement: R-E24
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("optimizer-scope")).toRealPath();
			Files.createDirectory(root.resolve(".git"));
			Path directory = Files.createDirectories(root.resolve("a/b"));
			try (TestOptimizerCommandScope<FakeSessionPort> scope = TestOptimizerCommandScope.of(Harness.CLAUDE,
				directory))
			{
				Campaign campaign = new Builder().confirmed().build();
				scope.store().replaceSnapshot(campaign);
				Path campaignDirectory = root.resolve(".cat/work/optimizer").resolve(campaign.header().id());

				requireThat(Set.copyOf(scope.heldOutPaths()), "heldOutPaths").isEqualTo(Set.of(
					campaignDirectory.resolve("campaign.json"), campaignDirectory.resolve("trials")));
			}
		}
	}

	/**
	 * Verifies that a working directory outside a Git repository is refused when the project is first needed.
	 *
	 * @throws IOException if the directory cannot be created
	 */
	@Test
	public void refusesDirectoryOutsideGit() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			FileSystem isolated = FileSystems.newFileSystem(cleanup.add(Files.createTempDirectory("optimizer-scope")).
				resolve("no-repository.zip"), Map.of("create", "true")))
		{
			Path directory = isolated.getPath("/");
			try (TestOptimizerCommandScope<FakeSessionPort> scope = TestOptimizerCommandScope.of(Harness.CLAUDE,
				directory))
			{
				CommandRefusedException refusal = null;
				try
				{
					scope.store();
				}
				catch (CommandRefusedException e)
				{
					refusal = e;
				}
				requireThat(refusal, "refusal").isNotNull();
				requireThat(refusal.getMessage(), "message").contains("is not inside a Git repository");
			}
		}
	}

	/**
	 * Verifies that a closed scope rejects access to the values that every variant shares.
	 *
	 * @throws IOException if the directory cannot be created
	 */
	@Test
	public void closedScopeRejectsAccess() throws IOException
	{
		// Requirement: R-E4
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("optimizer-scope")).toRealPath();
			TestOptimizerCommandScope<FakeSessionPort> scope = TestOptimizerCommandScope.of(Harness.CLAUDE, directory);
			try (scope)
			{
			}
			requireThat(scope.isClosed(), "isClosed").isTrue();
			for (Runnable access : List.<Runnable>of(scope::profiles, scope::sliceSettings,
				scope::json, scope::sessions))
			{
				IllegalStateException failure = null;
				try
				{
					access.run();
				}
				catch (IllegalStateException e)
				{
					failure = e;
				}
				requireThat(failure, "failure").isNotNull();
			}
		}
	}
}
