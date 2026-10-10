package io.github.cowwoc.cat.common.runner.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.runner.RunnerRuntimeManifest;
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
			Path artifact = cleanup.add(Files.createTempDirectory("runner-runtime-manifest"));
			write(artifact, "client/bin/runner", "runtime v1\n");
			write(artifact, "hooks/session-start.sh", "hook v1\n");
			write(artifact, ".codex-plugin/plugin.json", "{}\n");
			Path rule = write(artifact, "rules/include/common/rule.md", "rule v1\n");
			RunnerRuntimeManifest.write(artifact);

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
			Path artifact = cleanup.add(Files.createTempDirectory("runner-runtime-manifest"));
			write(artifact, "client/bin/runner", "runtime v1\n");
			Path hook = write(artifact, "hooks/session-start.sh", "hook v1\n");
			write(artifact, ".codex-plugin/plugin.json", "{}\n");
			RunnerRuntimeManifest.write(artifact);

			Files.writeString(hook, "hook v2\n");
			RunnerRuntimeManifest.fingerprint(artifact);
		}
	}

	/**
	 * Verifies jlink-generated runtime bytes are verified within each package without splitting equivalent builds into
	 * different evaluation identities.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*does not match.*")
	public void keepsGeneratedRuntimeOutOfIdentity() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path first = cleanup.add(Files.createTempDirectory("runner-runtime-first"));
			Path second = cleanup.add(Files.createTempDirectory("runner-runtime-second"));
			writeRuntimeFixture(first, "generated runtime one\n");
			writeRuntimeFixture(second, "generated runtime two\n");
			RunnerRuntimeManifest.write(first);
			RunnerRuntimeManifest.write(second);

			requireThat(RunnerRuntimeManifest.fingerprint(second), "equivalentRuntimeIdentity").isEqualTo(
				RunnerRuntimeManifest.fingerprint(first));

			Files.writeString(second.resolve("client/lib/modules"), "tampered generated runtime\n");
			RunnerRuntimeManifest.fingerprint(second);
		}
	}

	/**
	 * Verifies a lifecycle check can identify an exact generated runtime without changing the stable comparison identity.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void includesGeneratedRuntimeInPackageHash() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path first = cleanup.add(Files.createTempDirectory("runner-runtime-first"));
			Path second = cleanup.add(Files.createTempDirectory("runner-runtime-second"));
			writeRuntimeFixture(first, "generated runtime one\n");
			writeRuntimeFixture(second, "generated runtime two\n");
			RunnerRuntimeManifest.write(first);
			RunnerRuntimeManifest.write(second);

			requireThat(RunnerRuntimeManifest.packagedFingerprint(second), "packagedRuntimeIdentity").isNotEqualTo(
				RunnerRuntimeManifest.packagedFingerprint(first));
		}
	}

	/**
	 * Creates the stable and generated portions of one package fixture.
	 *
	 * @param artifact the artifact root
	 *
	 * @param generatedRuntime the jlink-produced runtime bytes
	 *
	 * @throws IOException if fixture I/O fails
	 */
	private static void writeRuntimeFixture(Path artifact, String generatedRuntime) throws IOException
	{
		write(artifact, "client/bin/runner", "runtime v1\n");
		write(artifact, "client/lib/modules", generatedRuntime);
		write(artifact, "hooks/session-start.sh", "hook v1\n");
		write(artifact, ".codex-plugin/plugin.json", "{}\n");
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
