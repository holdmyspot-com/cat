package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.git.Git;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.testng.annotations.Test;

/**
 * Tests isolated-index patch verification for topic replays.
 */
public final class PatchApplicationTest
{
	/**
	 * Creates the test instance required by TestNG.
	 */
	public PatchApplicationTest()
	{
	}

	/**
	 * Verifies that a source patch changed after a successful reconstruction is rejected when the same reconstruction is
	 * verified again.
	 *
	 * @throws IOException if the isolated repository or patch fixture cannot be created
	 */
	@Test
	public void rejectsChangedPatchAfterVerification() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ToolScope scope = new ToolScope())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-patch-"));
			TopicReplayRepository.initializeRepository(repository);
			Git git = new Git(repository);
			String source = TopicReplayRepository.commit(git, repository, "feature.txt", "feature\n", "feature");
			String beforeTree = git.run("rev-parse", source + "^^{tree}").trim();
			String afterTree = git.run("rev-parse", source + "^{tree}").trim();
			Path temporaryRoot = cleanup.add(Files.createTempDirectory("cat-work-temporary-root-"));
			Path manifestFile = temporaryRoot.resolve("replay/manifest.json");
			Path patchFile = manifestFile.resolveSibling("source.patch");
			Files.createDirectories(patchFile.getParent());
			Files.write(patchFile, git.bytes("diff", "--binary", beforeTree, afterTree));
			PatchArtifacts patches = new PatchArtifacts();
			PatchApplication patchApplication = new PatchApplication(git,
				new ManifestStore<>(temporaryRoot, scope.json(), BatchManifest.class), patches);
			SourceReconstruction reconstruction = new SourceReconstruction(source, null,
				patches.declare(manifestFile, patchFile), beforeTree, afterTree);
			patchApplication.verifySourceReconstruction(manifestFile, reconstruction);

			Files.writeString(patchFile, "\n", StandardOpenOption.APPEND);

			try
			{
				patchApplication.verifySourceReconstruction(manifestFile, reconstruction);
				throw new AssertionError("Expected the changed source patch to be rejected");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains(
					"source-patch hash does not match its declared SHA-256");
			}
		}
	}
}
