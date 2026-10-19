package com.holdmyspot.cat.common.git.replay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates immutable raw source-patch reconstructions for an otherwise unreviewed replay manifest.
 */
final class SourceBootstrap
{
	private final SourcePatches sourcePatches;
	private final PatchArtifacts patches;
	private final PatchApplication patchApplication;
	private final ManifestStore<BatchManifest> manifests;

	/**
	 * Creates a raw-source bootstrap boundary.
	 *
	 * @param sourcePatches the owner of immutable source-diff capture
	 * @param patches the owner of manifest-local patch artifacts
	 * @param patchApplication the owner of source reconstruction verification
	 * @param manifests the owner of manifest publication
	 */
	SourceBootstrap(SourcePatches sourcePatches, PatchArtifacts patches, PatchApplication patchApplication,
		ManifestStore<BatchManifest> manifests)
	{
		this.sourcePatches = sourcePatches;
		this.patches = patches;
		this.patchApplication = patchApplication;
		this.manifests = manifests;
	}

	/**
	 * Captures and verifies one raw whole-source patch for every manifest source.
	 *
	 * @param manifestFile the unreviewed manifest that receives reconstruction facts
	 * @param manifest the current replay state
	 * @throws IOException if replay state or its patches directory already exists, or a source cannot be reconstructed
	 */
	void bootstrap(Path manifestFile, BatchManifest manifest) throws IOException
	{
		if (!manifest.batches().isEmpty() || !manifest.sourceReconstructions().isEmpty() || !manifest.outputWitnesses().
			isEmpty() || !discardedOutputs(manifest).isEmpty() || !sourceReviews(manifest).isEmpty())
		{
			throw new IOException("Topic-replay bootstrap requires a manifest without recorded replay state");
		}
		Path directory = manifestFile.toAbsolutePath().normalize().getParent();
		Path patchDirectory = directory.resolve("patches");
		if (Files.exists(patchDirectory))
			throw new IOException("Topic-replay bootstrap requires an unused patches directory");
		Files.createDirectory(patchDirectory);
		BatchManifest updated = manifest;
		for (SourceSnapshot source : manifest.sources())
		{
			Path patchFile = patchDirectory.resolve(source.hash() + ".raw.patch");
			sourcePatches.capture(manifestFile, updated, source.hash(), patchFile, List.of());
			PatchArtifact patch = patches.declare(manifestFile, patchFile);
			SourceReconstruction reconstruction = new SourceReconstruction(source.hash(), null, patch,
				source.parentTree(), source.tree());
			patchApplication.verifySourceReconstruction(manifestFile, reconstruction);
			List<SourceReconstruction> reconstructions = new ArrayList<>(updated.sourceReconstructions());
			reconstructions.add(reconstruction);
			updated = updated.withSourceReconstructions(reconstructions);
			manifests.write(manifestFile, updated);
		}
		System.out.println("patchDirectory=" + patchDirectory);
		System.out.println("sources=" + manifest.sources().size());
	}

	/**
	 * Returns discarded outputs, treating an omitted field as empty.
	 *
	 * @param manifest the replay manifest
	 * @return the declared or implied discarded outputs
	 */
	private List<String> discardedOutputs(BatchManifest manifest)
	{
		if (manifest.discardedOutputs() == null)
			return List.of();
		return manifest.discardedOutputs();
	}

	/**
	 * Returns source reviews, treating an omitted field as empty.
	 *
	 * @param manifest the replay manifest
	 * @return the declared or implied source reviews
	 */
	private List<SourceReview> sourceReviews(BatchManifest manifest)
	{
		if (manifest.sourceReviews() == null)
			return List.of();
		return manifest.sourceReviews();
	}
}
