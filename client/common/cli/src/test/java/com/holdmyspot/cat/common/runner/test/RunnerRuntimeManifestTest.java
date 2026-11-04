package com.holdmyspot.cat.common.runner.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.release.fakes.test.FakeRelease;
import com.holdmyspot.cat.common.runner.RunnerRuntimeManifest;
import com.holdmyspot.cat.common.runner.RuntimeBinding;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Tests {@link RunnerRuntimeManifest}.
 */
public final class RunnerRuntimeManifestTest
{
	/**
	 * Creates a test instance.
	 */
	public RunnerRuntimeManifestTest()
	{
	}

	/**
	 * Verifies prompt content does not change the runner environment.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void separatesPromptContentFromRunnerRuntime() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("runner-runtime-manifest"));
			Path artifact = packagedArtifact(root.resolve("artifact"), "runtime v1\n");
			Path rule = write(artifact, "rules/include/common/rule.md", "rule v1\n");
			RunnerRuntimeManifest.write(artifact);
			RuntimeBinding.write(artifact, runtime(root.resolve("runtime"), "runtime one\n"));

			String baseline = RunnerRuntimeManifest.fingerprint(artifact);
			Files.writeString(rule, "rule v2\n");
			requireThat(RunnerRuntimeManifest.fingerprint(artifact), "promptOnlyFingerprint").isEqualTo(baseline);
		}
	}

	/**
	 * Rejects a package whose executable hook changed after its manifest was written.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*does not match.*")
	public void rejectsChangedExecutableHook() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("runner-runtime-manifest"));
			Path artifact = packagedArtifact(root.resolve("artifact"), "runtime v1\n");
			RunnerRuntimeManifest.write(artifact);
			RuntimeBinding.write(artifact, runtime(root.resolve("runtime"), "runtime one\n"));

			Files.writeString(artifact.resolve("hooks/session-start.sh"), "hook v2\n");
			RunnerRuntimeManifest.fingerprint(artifact);
		}
	}

	/**
	 * Rejects a package whose launcher in {@code bin/}, which agents run, changed after its manifest was written.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*does not match.*")
	public void rejectsChangedAgentLauncher() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("runner-runtime-manifest"));
			Path artifact = packagedArtifact(root.resolve("artifact"), "runtime v1\n");
			Path launcher = write(artifact, "bin/plugin-info", "launcher v1\n");
			RunnerRuntimeManifest.write(artifact);
			RuntimeBinding.write(artifact, runtime(root.resolve("runtime"), "runtime one\n"));

			Files.writeString(launcher, "launcher v2\n");
			RunnerRuntimeManifest.fingerprint(artifact);
		}
	}

	/**
	 * Verifies that the application modules of a plugin define both its packaged identity and its execution identity.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void identifiesApplicationModules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("runner-runtime-modules"));
			Path runtime = runtime(root.resolve("runtime"), "runtime one\n");
			Path first = packagedArtifact(root.resolve("first"), "application one\n");
			Path second = packagedArtifact(root.resolve("second"), "application two\n");
			for (Path artifact : List.of(first, second))
			{
				RunnerRuntimeManifest.write(artifact);
				RuntimeBinding.write(artifact, runtime);
			}

			requireThat(RunnerRuntimeManifest.packagedFingerprint(second), "secondPackagedIdentity").isNotEqualTo(
				RunnerRuntimeManifest.packagedFingerprint(first), "firstPackagedIdentity");
			requireThat(RunnerRuntimeManifest.fingerprint(second), "secondExecutionIdentity").isNotEqualTo(
				RunnerRuntimeManifest.fingerprint(first), "firstExecutionIdentity");
		}
	}

	/**
	 * Verifies that binding a packaged plugin to another installation's runtime leaves its packaged identity unchanged,
	 * because the binding is written by the installation rather than packaged.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void excludesBindingFromPackagedIdentity() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("runner-runtime-binding"));
			Path artifact = packagedArtifact(root.resolve("artifact"), "application one\n");
			RuntimeBinding.write(artifact, runtime(root.resolve("first-installation"), "runtime one\n"));
			RunnerRuntimeManifest.write(artifact);
			String firstInstallation = RunnerRuntimeManifest.packagedFingerprint(artifact);

			RuntimeBinding.write(artifact, runtime(root.resolve("second-installation"), "runtime two\n"));

			requireThat(RunnerRuntimeManifest.packagedFingerprint(artifact), "secondInstallation").
				isEqualTo(firstInstallation, "firstInstallation");
		}
	}

	/**
	 * Verifies that the startup cache that an installation writes leaves the plugin's packaged and execution identities
	 * unchanged, because the cache is written by the installation rather than packaged.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void excludesStartupCacheFromIdentity() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("runner-runtime-cache"));
			Path artifact = packagedArtifact(root.resolve("artifact"), "application one\n");
			RunnerRuntimeManifest.write(artifact);
			RuntimeBinding.write(artifact, runtime(root.resolve("runtime"), "runtime one\n"));
			String packaged = RunnerRuntimeManifest.packagedFingerprint(artifact);
			String execution = RunnerRuntimeManifest.fingerprint(artifact);

			write(artifact, "client/aot/session-start.aot", "cache\n");

			requireThat(RunnerRuntimeManifest.packagedFingerprint(artifact), "packagedWithCache").
				isEqualTo(packaged, "packagedWithoutCache");
			requireThat(RunnerRuntimeManifest.fingerprint(artifact), "executionWithCache").
				isEqualTo(execution, "executionWithoutCache");
		}
	}

	/**
	 * Verifies that replacing the Java runtime that a plugin is bound to changes its execution identity, so that
	 * results recorded with one runtime are not reused with another, while its packaged identity stays unchanged.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void includesBoundRuntimeInExecutionIdentity() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("runner-runtime-identity"));
			Path artifact = packagedArtifact(root.resolve("artifact"), "application one\n");
			Path runtime = runtime(root.resolve("runtime"), "runtime one\n");
			RunnerRuntimeManifest.write(artifact);
			RuntimeBinding.write(artifact, runtime);
			String packaged = RunnerRuntimeManifest.packagedFingerprint(artifact);
			String withFirstRuntime = RunnerRuntimeManifest.fingerprint(artifact);

			// The setup of a newer release replaces the shared runtime in place, at the same path
			TemporaryDirectories.deleteRecursively(runtime);
			runtime(runtime, "runtime two\n");

			requireThat(RunnerRuntimeManifest.fingerprint(artifact), "withSecondRuntime").
				isNotEqualTo(withFirstRuntime, "withFirstRuntime");
			requireThat(RunnerRuntimeManifest.packagedFingerprint(artifact), "packagedWithSecondRuntime").
				isEqualTo(packaged, "packagedWithFirstRuntime");
		}
	}

	/**
	 * Rejects an execution identity for a plugin that is bound to no runtime, naming the missing binding.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		".*client[/\\\\]runtime-binding.*")
	public void rejectsMissingBinding() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("runner-runtime-unbound"));
			Path artifact = packagedArtifact(root.resolve("artifact"), "application one\n");
			RunnerRuntimeManifest.write(artifact);

			RunnerRuntimeManifest.fingerprint(artifact);
		}
	}

	/**
	 * Rejects an execution identity for a plugin whose bound runtime no longer matches its manifest, naming that
	 * manifest.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*cat-runtime\\.manifest.*")
	public void rejectsChangedRuntime() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("runner-runtime-changed"));
			Path artifact = packagedArtifact(root.resolve("artifact"), "application one\n");
			Path runtime = runtime(root.resolve("runtime"), "runtime one\n");
			RunnerRuntimeManifest.write(artifact);
			RuntimeBinding.write(artifact, runtime);

			Files.writeString(runtime.resolve("lib/modules"), "tampered runtime\n");
			RunnerRuntimeManifest.fingerprint(artifact);
		}
	}

	/**
	 * Creates the packaged portion of one plugin fixture: an application module, a launcher, a hook and a harness
	 * registration.
	 *
	 * @param artifact    the artifact root
	 * @param application the content of the application module
	 * @return {@code artifact}
	 * @throws IOException if fixture I/O fails
	 */
	private static Path packagedArtifact(Path artifact, String application) throws IOException
	{
		write(artifact, "client/modules/application.jar", application);
		write(artifact, "client/bin/runner", "runtime v1\n");
		write(artifact, "hooks/session-start.sh", "hook v1\n");
		write(artifact, ".codex-plugin/plugin.json", "{}\n");
		return artifact;
	}

	/**
	 * Creates a shared Java runtime with its manifest.
	 *
	 * @param directory the runtime's directory
	 * @param modules   the content of its module image, which distinguishes one runtime from another
	 * @return the runtime's absolute normalized directory
	 * @throws IOException if fixture I/O fails
	 */
	private static Path runtime(Path directory, String modules) throws IOException
	{
		write(directory, "lib/modules", modules);
		return FakeRelease.runtime(directory, Platform.current());
	}

	/**
	 * Writes one text fixture relative to an artifact root.
	 *
	 * @param artifact the artifact root
	 *
	 * @param relativePath the relative file path
	 *
	 * @param contents the UTF-8 fixture contents
	 *
	 * @return the written path
	 *
	 * @throws IOException if fixture I/O fails
	 */
	private static Path write(Path artifact, String relativePath, String contents) throws IOException
	{
		Path result = artifact.resolve(relativePath);
		Files.createDirectories(result.getParent());
		Files.writeString(result, contents);
		return result;
	}
}
