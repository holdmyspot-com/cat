package com.holdmyspot.cat.common.git.replay.test;

import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.git.Git;
import com.holdmyspot.cat.common.git.replay.BatchManifest;
import com.holdmyspot.cat.common.git.replay.ManifestStore;
import com.holdmyspot.cat.common.git.replay.PatchApplication;
import com.holdmyspot.cat.common.git.replay.PatchArtifacts;
import com.holdmyspot.cat.common.git.replay.ReplayJson;
import com.holdmyspot.cat.common.git.replay.SourceReconstruction;

import com.holdmyspot.cat.common.TemporaryDirectories;
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
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*source-patch hash does not match its declared SHA-256.*")
	public void rejectsChangedPatchAfterVerification() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporaryRoot = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "patch-application-"));
			Path manifestFile = temporaryRoot.resolve("replay/manifest.json");
			Path patchFile = manifestFile.resolveSibling("source.patch");
			Files.createDirectories(patchFile.getParent());
			Files.writeString(patchFile, "immutable source patch\n");
			PatchArtifacts patches = new PatchArtifacts();
			PatchApplication patchApplication = new PatchApplication(new Git(temporaryRoot),
				new ManifestStore<>(temporaryRoot, ReplayJson.newMapper(), Yielding.fileReplacer(), BatchManifest.class),
				patches);
			SourceReconstruction reconstruction = new SourceReconstruction("source", null,
				patches.declare(manifestFile, patchFile), "before-tree", "after-tree");
			patchApplication.recordVerifiedSourceTree(reconstruction, "after-tree");

			Files.writeString(patchFile, "\n", StandardOpenOption.APPEND);

			patchApplication.verifySourceReconstruction(manifestFile, reconstruction);
		}
	}
}
