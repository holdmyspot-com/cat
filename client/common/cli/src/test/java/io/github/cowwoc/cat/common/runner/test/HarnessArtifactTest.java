package io.github.cowwoc.cat.common.runner.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import io.github.cowwoc.cat.common.PluginInfo;
import io.github.cowwoc.cat.common.PortableFiles;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.runner.HarnessArtifact;
import org.testng.annotations.Test;

import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Tests {@link HarnessArtifact}.
 */
public final class HarnessArtifactTest
{
	/**
	 * Creates the test fixture.
	 */
	public HarnessArtifactTest()
	{
	}

	/**
	 * Verifies a complete harness artifact is accepted.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void acceptsCompleteArtifact() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = createArtifact(cleanup);

			requireThat(HarnessArtifact.validate(artifact, Path.of(".harness/plugin.json")), "artifact").isEqualTo(
				artifact);
		}
	}

	/**
	 * Verifies a missing executable launcher is rejected.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test(expectedExceptions = IOException.class)
	public void rejectsMissingExecutableLauncher() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = createArtifact(cleanup);
			Files.delete(artifact.resolve("client/bin/plugin-info"));

			HarnessArtifact.validate(artifact, Path.of(".harness/plugin.json"));
		}
	}

	/**
	 * Creates a complete minimal harness artifact.
	 *
	 * @param cleanup the temporary-directory cleanup scope
	 *
	 * @return the unpacked plugin directory
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	private static Path createArtifact(TemporaryDirectories cleanup) throws IOException
	{
		Path artifact = cleanup.add(Files.createTempDirectory("cat-artifact-"));
		Files.createDirectories(artifact.resolve("client/bin"));
		Files.createDirectories(artifact.resolve(".harness"));
		Files.writeString(artifact.resolve("client/VERSION"), PluginInfo.CAT_VERSION);
		Files.writeString(artifact.resolve(".harness/plugin.json"), "{}");
		Set<PosixFilePermission> permissions = Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE);
		Path pluginInfo = artifact.resolve("client/bin/plugin-info");
		Path sessionStart = artifact.resolve("client/bin/session-start");
		Files.writeString(pluginInfo, "#!/bin/sh\n");
		Files.writeString(sessionStart, "#!/bin/sh\n");
		PortableFiles.setMode(pluginInfo, permissions);
		PortableFiles.setMode(sessionStart, permissions);
		return artifact;
	}
}
