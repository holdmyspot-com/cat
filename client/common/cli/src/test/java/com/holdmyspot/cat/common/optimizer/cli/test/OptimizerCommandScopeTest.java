package com.holdmyspot.cat.common.optimizer.cli.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.campaign.test.Builder;
import com.holdmyspot.cat.common.optimizer.cli.CommandRefusedException;
import com.holdmyspot.cat.common.optimizer.cli.OptimizerCommandScope;
import com.holdmyspot.cat.common.optimizer.port.test.FakeSessionPort;
import com.holdmyspot.cat.common.secrets.OptimizerCommandScopeAccess;
import com.holdmyspot.cat.common.secrets.OptimizerCommandScopeSecrets;
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
	 * A host session other than the one that runs the commands of the tests.
	 */
	private static final String OTHER_SESSION = "5a7c9e1b-3d2f-4a6b-8c0e-9f1d3b5a7c2e";

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
				requireThat(OptimizerCommandScopeSecrets.getOptimizerCommandScopeAccess().
					projectRoot(scope), "projectRoot").isEqualTo(root);
				OptimizerCommandScopeAccess access = OptimizerCommandScopeSecrets.getOptimizerCommandScopeAccess();
				requireThat(access.store(scope) == access.store(scope), "sameStore").isTrue();
			}
		}
	}

	/**
	 * Verifies that the held-out directories are those of the campaigns of every session of the project, even when the
	 * command runs in a subdirectory of the project.
	 *
	 * @throws IOException if the project cannot be written
	 * @throws CommandRefusedException if the project is not found
	 */
	@Test
	public void listsProjectHeldOutDirectories() throws IOException, CommandRefusedException
	{
		// Requirement: R-E24
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("optimizer-scope")).toRealPath();
			Files.createDirectory(root.resolve(".git"));
			Path directory = Files.createDirectories(root.resolve("a/b"));
			Path otherHeldOut;
			try (TestOptimizerCommandScope<FakeSessionPort> other = TestOptimizerCommandScope.of(Harness.CODEX,
				OTHER_SESSION, root))
			{
				Campaign campaign = new Builder().confirmed().build();
				OptimizerCommandScopeSecrets.getOptimizerCommandScopeAccess().store(other).replaceSnapshot(campaign);
				otherHeldOut = OptimizerCommandScopeSecrets.getOptimizerCommandScopeAccess().
					sessionDirectory(other).campaigns().
					resolve(campaign.header().id()).resolve("held_out");
			}
			try (TestOptimizerCommandScope<FakeSessionPort> scope = TestOptimizerCommandScope.of(Harness.CLAUDE,
				directory))
			{
				Campaign campaign = new Builder().confirmed().build();
				OptimizerCommandScopeSecrets.getOptimizerCommandScopeAccess().store(scope).replaceSnapshot(campaign);
				Path campaignDirectory = OptimizerCommandScopeSecrets.getOptimizerCommandScopeAccess().
					sessionDirectory(scope).campaigns().
					resolve(campaign.header().id());

				requireThat(Set.copyOf(OptimizerCommandScopeSecrets.getOptimizerCommandScopeAccess().heldOutDirectories(scope)),
					"heldOutDirectories").isEqualTo(Set.of(campaignDirectory.resolve("held_out"), otherHeldOut));
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
					OptimizerCommandScopeSecrets.getOptimizerCommandScopeAccess().store(scope);
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
			for (Runnable access : List.<Runnable>of(scope::profiles,
				() -> OptimizerCommandScopeSecrets.getOptimizerCommandScopeAccess().
					sliceSettings(scope), scope::json, scope::sessions))
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
