package com.holdmyspot.cat.common.git.replay.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.git.replay.ManifestStore;
import com.holdmyspot.cat.common.git.replay.ReplayJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.testng.annotations.Test;

/**
 * Tests the CAT-work persistence boundary for topic-replay manifests.
 */
public final class ManifestStoreTest
{
	/**
	 * Creates the test instance required by TestNG.
	 */
	public ManifestStoreTest()
	{
	}

	/**
	 * Verifies a manifest outside the invocation's CAT-work root cannot create a replay-owned temporary artifact.
	 *
	 * @throws IOException if a test fixture cannot be created
	 */
	@Test
	public void rejectsManifestOutsideTemporaryRoot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporaryRoot = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(),
				"cat-work-temporary-root-"));
			Path externalManifest = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(),
				"cat-topic-replay-")).resolve("manifest.json");
			ManifestStore<ManifestStore.Manifest> store =
				new ManifestStore<>(temporaryRoot, ReplayJson.newMapper(), ManifestStore.Manifest.class);

			try
			{
				store.createTemporaryFile(externalManifest, ".index");
				throw new AssertionError("Expected manifest outside the CAT work temp directory to be rejected");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("must be below the CAT work temp directory");
			}
		}
	}

	/**
	 * Verifies the store atomically publishes and then reads the same version-5 immutable source facts.
	 *
	 * @throws IOException if a test fixture cannot be written or read
	 */
	@Test
	public void roundTripsUnfinalizedManifest() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporaryRoot = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(),
				"cat-work-temporary-root-"));
			Path manifestFile = temporaryRoot.resolve("replay/manifest.json");
			ManifestStore<FixtureManifest> store = new ManifestStore<>(temporaryRoot,
				ReplayJson.newMapper(), FixtureManifest.class);
			FixtureManifest expected = new FixtureManifest(5, List.of("source"));

			store.write(manifestFile, expected);

			requireThat(store.readUnfinalized(manifestFile), "manifest").isEqualTo(expected);
		}
	}

	/**
	 * Minimal immutable source-fact model used to exercise the storage contract directly.
	 *
	 * @param version the stored format version
	 * @param sources immutable source snapshot identifiers
	 */
	private record FixtureManifest(int version, List<String> sources) implements ManifestStore.Manifest
	{
		/**
		 * Indicates whether immutable source snapshots are available to the replay workflow.
		 *
		 * @return {@code true} if at least one immutable source snapshot is available
		 */
		@Override
		public boolean hasSources()
		{
			return !sources.isEmpty();
		}
	}
}
