package com.holdmyspot.cat.codex.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.optimizer.CodexOptimizerBoundary;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.cli.Command;
import com.holdmyspot.cat.common.runner.RequestServer;
import com.holdmyspot.cat.common.runner.launcher.ParentPolicySource;
import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Exercises the real launch boundary with protected-session records supplied in memory, without a host process.
 */
public final class CodexOptimizerBoundaryTest
{
	/**
	 * Creates the test.
	 */
	public CodexOptimizerBoundaryTest()
	{
	}

	/**
	 * When the installation probe cannot write, background mode reports the host reason and foreground remains
	 * available. A missing directory simulates probe refusal on every platform.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void sandboxedBackgroundNamesReason() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("optimizer-boundary"));
			CodexOptimizerBoundary boundary = new CodexOptimizerBoundary(project.resolve("unwritable-install"),
				project, () ->
				{
					throw new AssertionError("Foreground sandbox needs no unsandboxed identity read");
				}, () -> "the permission profile denies reads, so the launcher stayed sandboxed");
			requireThat(boundary.backgroundRefusal().orElseThrow(), "reason").contains("denies reads").
				contains("foreground slices");
			boundary.validate(new Command.Run("campaign-1"));
		}
	}

	/**
	 * An unsandboxed launcher rejects an untrusted caller before campaign work.
	 *
	 * @throws IOException if validation fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*thread identity is untrusted.*")
	public void unsandboxedLaunchRequiresTrustedIdentity() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("optimizer-install"));
			CodexOptimizerBoundary boundary = new CodexOptimizerBoundary(plugin, plugin, () ->
			{
				throw new PolicyRefusal("thread identity is untrusted", "Start a new session");
			}, () -> "sandboxed");
			boundary.validate(new Command.Run("campaign-1"));
		}
	}

	/**
	 * The exception accepts a validated project target and refuses an existing file outside that project.
	 *
	 * @throws IOException if validation or fixture creation fails
	 */
	@Test
	public void targetMustBelongToValidatedHostProject() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("optimizer-boundary"));
			Path plugin = Files.createDirectory(root.resolve("plugin"));
			Path project = Files.createDirectory(root.resolve("project"));
			Path target = Files.writeString(project.resolve("prompt.md"), "prompt");
			Path outside = Files.writeString(root.resolve("outside.md"), "other project");
			ParentPolicy policy = new ParentPolicy(true, List.of(project), List.of(), List.of(), List.of(),
				HostAccess.NONE, false, List.of());
			RequestServer.Session session = new RequestServer.Session(new ParentPolicySource()
			{
				@Override
				public String harnessName()
				{
					return "Codex";
				}

				@Override
				public String permissionEntries(List<Path> writes, Set<String> hosts)
				{
					return "fixture permissions";
				}

				@Override
				public ParentPolicy read(Optional<String> askingThread)
				{
					return policy;
				}
			}, project.resolve("rollout.jsonl"), project, Optional.empty(), new ProcessIdentity(42, "fixture"));
			CodexOptimizerBoundary boundary = new CodexOptimizerBoundary(plugin, project, () -> session,
				() -> "sandboxed");
			requireThat(boundary.backgroundRefusal(), "outsidesandbox").isEmpty();
			boundary.validate(new Command.Start(target, 1, null, null));
			requireThat(boundary.session(), "validatedhost").isEqualTo(session);
			try
			{
				boundary.validate(new Command.Start(outside, 1, null, null));
				throw new AssertionError("An outside target was accepted");
			}
			catch (IOException refused)
			{
				requireThat(refused.getMessage(), "refusal").contains("outside the session");
			}
		}
	}
	/**
	 * Git environment overrides are refused before an unsandboxed optimizer can execute them.
	 *
	 * @throws Exception if validation fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Git override GIT_CONFIG_COUNT.*")
	public void refusesGitEnvironmentOverrides() throws Exception
	{
		CodexOptimizerBoundary.validateEnvironment(java.util.Map.of("GIT_CONFIG_COUNT", "1"),
			ParentPolicy.UNSANDBOXED);
	}

	/**
	 * Environment path variables cannot reinterpret a relative directory outside the validated project.
	 *
	 * @throws Exception if validation fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*absolute directory in CODEX_HOME.*")
	public void refusesRelativeConfigurationHome() throws Exception
	{
		CodexOptimizerBoundary.validateEnvironment(java.util.Map.of("CODEX_HOME", "relative"), ParentPolicy.UNSANDBOXED);
	}
}
