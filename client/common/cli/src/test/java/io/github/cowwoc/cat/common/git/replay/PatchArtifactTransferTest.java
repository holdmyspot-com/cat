package io.github.cowwoc.cat.common.git.replay;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;

/**
 * Tests immutable patch-artifact transfer behavior.
 */
public final class PatchArtifactTransferTest
{
	/**
	 * Creates the test fixture.
	 */
	public PatchArtifactTransferTest()
	{
	}

	/**
	 * Verifies a copied batch retains its digest and becomes local to the receiving manifest.
	 *
	 * @throws IOException if the isolated artifacts cannot be created or copied
	 */
	@Test
	public void copiesBatchAsReceivingManifestArtifact() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("topic-replay-patch-transfer-"));
			Path previousDirectory = Files.createDirectories(root.resolve("previous"));
			Path replacementDirectory = Files.createDirectories(root.resolve("replacement"));
			Path previousManifest = Files.writeString(previousDirectory.resolve("manifest.json"), "{}\n");
			Path replacementManifest = Files.writeString(replacementDirectory.resolve("manifest.json"), "{}\n");
			Path patchFile = Files.writeString(previousDirectory.resolve("source.patch"), "patch bytes\n");
			PatchArtifacts patches = new PatchArtifacts();
			PatchArtifact patch = patches.declare(previousManifest, patchFile);
			Batch batch = new Batch("batch", "source", "output", patch, null);
			BatchManifest previous = new BatchManifest(5, "base", "tree", List.of(), List.of(), List.of(), List.of(batch),
				List.of(), List.of(), List.of(), List.of(), List.of(), null);

			Map<String, PatchArtifact> copied = new PatchArtifactTransfer(patches).copy(previousManifest,
				replacementManifest, previous, List.of(batch));

			PatchArtifact receivingPatch = copied.get(patch.path());
			requireThat(receivingPatch.sha256(), "receivingPatchDigest").isEqualTo(patch.sha256());
			requireThat(patches.resolve(replacementManifest, receivingPatch), "receivingPatch").isRegularFile();
		}
	}
}
