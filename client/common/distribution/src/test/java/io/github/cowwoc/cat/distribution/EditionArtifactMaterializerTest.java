package io.github.cowwoc.cat.distribution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.Platform;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests edition-specific release-tree construction.
 */
public final class EditionArtifactMaterializerTest
{
	/**
	 * Creates the test fixture.
	 */
	public EditionArtifactMaterializerTest()
	{
	}

	/**
	 * Verifies Personal retains shared behavior and omits every commercial-only supported entry point.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void omitsCommercialEntryPointsFromPersonal() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-personal-tree-"));
			Path assembled = createAssembledArtifact(temporary.resolve("assembled"));
			ReleaseManifest.ReleaseTarget target = new ReleaseManifest.ReleaseTarget(ReleaseManifest.Edition.PERSONAL,
				ReleaseManifest.Harness.CODEX, Platform.LINUX_X64);

			Path materialized = new EditionArtifactMaterializer("1.0").materialize(target, assembled,
				temporary.resolve("release"));

			requireThat(materialized.resolve("client/bin/prompt-file-check"), "sharedLauncher").isRegularFile();
			requireThat(materialized.resolve("skills/prompt-builder/SKILL.md"), "sharedSkill").isRegularFile();
			for (String launcher : EditionArtifactMaterializer.COMMERCIAL_LAUNCHERS)
				requireThat(Files.exists(materialized.resolve("client/bin").resolve(launcher)), launcher).isFalse();
			requireThat(Files.exists(materialized.resolve("skills/prompt-optimizer")), "optimizerSkill").isFalse();
			requireThat(Files.exists(materialized.resolve("skills/feedback")), "feedbackSkill").isFalse();
			requireThat(Files.exists(materialized.resolve("CAT-RELEASE.json")), "obsoleteTargetManifest").isFalse();
		}
	}

	/**
	 * Verifies Commercial retains every commercial-only supported entry point.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void retainsCommercialEntryPointsInCommercial() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-commercial-tree-"));
			Path assembled = createAssembledArtifact(temporary.resolve("assembled"));
			ReleaseManifest.ReleaseTarget target = new ReleaseManifest.ReleaseTarget(ReleaseManifest.Edition.COMMERCIAL,
				ReleaseManifest.Harness.CLAUDE, Platform.MACOS_ARM64);

			Path materialized = new EditionArtifactMaterializer("1.0").materialize(target, assembled,
				temporary.resolve("release"));

			for (String launcher : EditionArtifactMaterializer.COMMERCIAL_LAUNCHERS)
				requireThat(materialized.resolve("client/bin").resolve(launcher), launcher).isRegularFile();
			requireThat(materialized.resolve("skills/prompt-optimizer/SKILL.md"), "optimizerSkill").isRegularFile();
			requireThat(materialized.resolve("skills/feedback/SKILL.md"), "feedbackSkill").isRegularFile();
		}
	}

	/**
	 * Verifies the repository-level manifest lists each target file.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void writesRepositoryManifest() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-signed-release-"));
			Path assembled = createAssembledArtifact(temporary.resolve("assembled"));
			Path editionRoot = temporary.resolve("release/commercial");
			Files.createDirectories(editionRoot);
			Files.writeString(editionRoot.resolve("LICENSE.md"), "license");
			Files.writeString(editionRoot.resolve("README.md"), "readme");
			EditionArtifactMaterializer materializer = new EditionArtifactMaterializer("1.0");
			Path codex = materializer.materialize(new ReleaseManifest.ReleaseTarget(
				ReleaseManifest.Edition.COMMERCIAL, ReleaseManifest.Harness.CODEX, Platform.LINUX_X64), assembled,
				editionRoot);
			Path claude = materializer.materialize(new ReleaseManifest.ReleaseTarget(
				ReleaseManifest.Edition.COMMERCIAL, ReleaseManifest.Harness.CLAUDE, Platform.LINUX_X64), assembled,
				editionRoot);
			Path manifest = materializer.writeReleaseManifest(List.of(codex, claude), editionRoot);

			requireThat(manifest, "manifest").isRegularFile();
			JsonNode document = new JsonMapper().readTree(Files.readString(manifest));
			requireThat(document.path("files").size(), "fileCount").isGreaterThan(0);
			requireThat(document.path("files").get(0).path("path").stringValue(), "firstFile").isEqualTo("LICENSE.md");
			requireThat(document.path("files").get(0).path("target").stringValue(), "firstTarget").isEqualTo("root");
		}
	}

	/**
	 * Verifies an end-user release cannot contain source or build inputs.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Release input contains forbidden build or source material:.*Build.java.*")
	public void rejectsSourceMaterial() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-source-tree-"));
			Path assembled = createAssembledArtifact(temporary.resolve("assembled"));
			Files.writeString(assembled.resolve("Build.java"), "class Build {}");
			ReleaseManifest.ReleaseTarget target = new ReleaseManifest.ReleaseTarget(ReleaseManifest.Edition.COMMERCIAL,
				ReleaseManifest.Harness.CODEX, Platform.LINUX_ARM64);

			new EditionArtifactMaterializer("1.0").materialize(target, assembled, temporary.resolve("release"));
		}
	}

	/**
	 * Verifies a release cannot contain any symbolic link.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Release input contains a symbolic link:.*escape.*")
	public void rejectsSymbolicLinks() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-link-tree-"));
			Path assembled = createAssembledArtifact(temporary.resolve("assembled"));
			Files.createSymbolicLink(assembled.resolve("escape"), Path.of(".."));
			ReleaseManifest.ReleaseTarget target = new ReleaseManifest.ReleaseTarget(ReleaseManifest.Edition.COMMERCIAL,
				ReleaseManifest.Harness.CODEX, Platform.LINUX_X64);

			new EditionArtifactMaterializer("1.0").materialize(target, assembled, temporary.resolve("release"));
		}
	}

	/**
	 * Rejects a symbolic link that remains inside the assembled artifact.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Release input contains a symbolic link:.*internal.*")
	public void rejectsInternalSymbolicLinks() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-internal-link-tree-"));
			Path assembled = createAssembledArtifact(temporary.resolve("assembled"));
			Files.createSymbolicLink(assembled.resolve("internal"), Path.of("LICENSE.txt"));
			ReleaseManifest.ReleaseTarget target = new ReleaseManifest.ReleaseTarget(ReleaseManifest.Edition.COMMERCIAL,
				ReleaseManifest.Harness.CODEX, Platform.LINUX_X64);

			new EditionArtifactMaterializer("1.0").materialize(target, assembled, temporary.resolve("release"));
		}
	}

	/**
	 * Creates the minimum representative assembled plugin tree.
	 *
	 * @param root the artifact root
	 * @return the artifact root
	 * @throws IOException if fixture creation fails
	 */
	private static Path createAssembledArtifact(Path root) throws IOException
	{
		Path launchers = Files.createDirectories(root.resolve("client/bin"));
		Files.writeString(launchers.resolve("prompt-file-check"), "shared");
		for (String launcher : EditionArtifactMaterializer.COMMERCIAL_LAUNCHERS)
			Files.writeString(launchers.resolve(launcher), "commercial");
		Files.createDirectories(root.resolve("skills/prompt-builder"));
		Files.writeString(root.resolve("skills/prompt-builder/SKILL.md"), "shared");
		Files.createDirectories(root.resolve("skills/prompt-optimizer"));
		Files.writeString(root.resolve("skills/prompt-optimizer/SKILL.md"), "commercial");
		Files.createDirectories(root.resolve("skills/feedback"));
		Files.writeString(root.resolve("skills/feedback/SKILL.md"), "commercial");
		Files.writeString(root.resolve("LICENSE.txt"), "license");
		Files.writeString(root.resolve("NOTICE.txt"), "notice");
		return root;
	}
}
