package io.github.cowwoc.cat.distribution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests assembly of the complete cross-platform edition release trees.
 */
public final class ReleaseTreeAssemblerTest
{
	/**
	 * Creates the test fixture.
	 */
	public ReleaseTreeAssemblerTest()
	{
	}

	/**
	 * Verifies platform-native inputs produce all eight immutable targets in each edition tree.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void assemblesEveryReleaseTarget() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("release-tree-assembly-"));
			Path inputs = temporary.resolve("inputs");
			Files.createDirectories(inputs);
			Files.writeString(inputs.resolve("LICENSE.md"), "license");
			Files.writeString(inputs.resolve("README.md"), "readme");
			for (ReleaseManifest.ReleaseTarget target : ReleaseManifest.targets())
				createTarget(inputs.resolve(target.platform().id()).resolve(target.edition().externalValue()).resolve(
					target.repositoryDirectory()));

			Path release = temporary.resolve("release");
			ReleaseTreeAssembler.assemble("1.0", inputs, release);

			for (ReleaseManifest.Edition edition : ReleaseManifest.Edition.values())
			{
				Path editionRoot = release.resolve(edition.externalValue());
				requireThat(editionRoot.resolve("LICENSE.md"), "license").isRegularFile();
				requireThat(editionRoot.resolve("README.md"), "readme").isRegularFile();
				requireThat(editionRoot.resolve("release-manifest.json"), "manifest").isRegularFile();
				for (ReleaseManifest.ReleaseTarget target : ReleaseManifest.targets(edition))
					requireThat(editionRoot.resolve(target.repositoryDirectory()), "target").isDirectory();
			}
		}
	}

	/**
	 * Publishes both complete edition trees to local bare repositories and rejects a second version-tag publication.
	 *
	 * @throws IOException if fixture I/O or the publisher process fails
	 * @throws InterruptedException if the publisher process is interrupted
	 */
	@Test
	public void publishesImmutableVersionTags() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("release-tag-integrity-"));
			Path inputs = temporary.resolve("inputs");
			Files.createDirectories(inputs);
			Files.writeString(inputs.resolve("LICENSE.md"), "license");
			Files.writeString(inputs.resolve("README.md"), "readme");
			for (ReleaseManifest.ReleaseTarget target : ReleaseManifest.targets())
				createTarget(inputs.resolve(target.platform().id()).resolve(target.edition().externalValue()).resolve(
					target.repositoryDirectory()));
			Path release = temporary.resolve("release");
			ReleaseTreeAssembler.assemble("1.0", inputs, release);
			Path script = Path.of("../../release/test-release-tag.sh").toAbsolutePath().normalize();
			try (Process process = new ProcessBuilder(script.toString(), release.resolve("personal").toString(),
				release.resolve("commercial").toString(), "1.0").redirectErrorStream(true).start())
			{
				String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

				int exitCode = process.waitFor();
				if (exitCode != 0)
					throw new IOException("Release publisher exited " + exitCode + ": " + output);
				requireThat(output, "publisherOutput").doesNotContain("Publisher replaced immutable tag");
			}
		}
	}

	/**
	 * Creates the minimum valid end-user target input.
	 *
	 * @param root the target root
	 * @throws IOException if fixture I/O fails
	 */
	private static void createTarget(Path root) throws IOException
	{
		Files.createDirectories(root.resolve("client/bin"));
		Files.writeString(root.resolve("client/bin/prompt-file-check"), "launcher");
		Files.createDirectories(root.resolve("skills/prompt-builder"));
		Files.writeString(root.resolve("skills/prompt-builder/SKILL.md"), "skill");
		Files.writeString(root.resolve("LICENSE.txt"), "license");
		Files.writeString(root.resolve("NOTICE.txt"), "notice");
	}
}
