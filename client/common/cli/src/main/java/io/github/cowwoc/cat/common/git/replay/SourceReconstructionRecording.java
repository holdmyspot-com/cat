package io.github.cowwoc.cat.common.git.replay;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Verifies and records one source's batch-sequence or raw-patch reconstruction.
 */
final class SourceReconstructionRecording
{
	private final PatchArtifacts patches;
	private final PatchApplication patchApplication;
	private final ManifestStore<BatchManifest> manifests;

	/**
	 * Creates a source-reconstruction recording boundary.
	 *
	 * @param patches the owner of manifest-local patch declarations
	 * @param patchApplication the owner of reconstruction verification
	 * @param manifests the owner of manifest publication
	 */
	SourceReconstructionRecording(PatchArtifacts patches, PatchApplication patchApplication,
		ManifestStore<BatchManifest> manifests)
	{
		this.patches = patches;
		this.patchApplication = patchApplication;
		this.manifests = manifests;
	}

	/**
	 * Records the reviewed ordered batch sequence that reconstructs one source commit.
	 *
	 * @param manifestFile the manifest that receives the reconstruction
	 * @param manifest the current replay state
	 * @param source the source commit to reconstruct
	 * @param batchIds the ordered reviewed batch identifiers
	 * @throws IOException if the source is unknown, already reconstructed, or the batches do not reproduce it
	 */
	void recordBatches(Path manifestFile, BatchManifest manifest, String source, List<String> batchIds) throws IOException
	{
		SourceSnapshot snapshot = sourceSnapshot(manifest, source);
		Map<String, Batch> batches = manifest.batches().stream().collect(Collectors.toMap(Batch::id, batch -> batch));
		SourceReconstruction reconstruction = new SourceReconstruction(source, List.copyOf(batchIds), null,
			snapshot.parentTree(), snapshot.tree());
		patchApplication.verifyBatchReconstruction(manifestFile, reconstruction, batches);
		publish(manifestFile, manifest, reconstruction);
	}

	/**
	 * Records one raw source patch that reconstructs a source commit independently of output placement.
	 *
	 * @param manifestFile the manifest that receives the reconstruction
	 * @param manifest the current replay state
	 * @param source the source commit to reconstruct
	 * @param patchFile the manifest-local raw patch
	 * @throws IOException if the source is unknown, already reconstructed, or the patch cannot reproduce it
	 */
	void recordPatch(Path manifestFile, BatchManifest manifest, String source, Path patchFile) throws IOException
	{
		SourceSnapshot snapshot = sourceSnapshot(manifest, source);
		PatchArtifact patch = patches.declare(manifestFile, patchFile);
		SourceReconstruction reconstruction = new SourceReconstruction(source, null, patch, snapshot.parentTree(),
			snapshot.tree());
		patchApplication.verifySourceReconstruction(manifestFile, reconstruction);
		publish(manifestFile, manifest, reconstruction);
	}

	/**
	 * Returns the unreconstructed captured snapshot for one source.
	 *
	 * @param manifest the replay state
	 * @param source the selected source commit
	 * @return the captured source snapshot
	 * @throws IOException if the source is unknown or already reconstructed
	 */
	private SourceSnapshot sourceSnapshot(BatchManifest manifest, String source) throws IOException
	{
		if (manifest.sourceReconstructions().stream().anyMatch(candidate -> candidate.source().equals(source)))
			throw new IOException("Topic-replay manifest already reconstructs source commit: " + source);
		return manifest.sources().stream().filter(candidate -> candidate.hash().equals(source)).findFirst().
			orElseThrow(() -> new IOException("Topic-replay manifest does not contain source commit: " + source));
	}

	/**
	 * Publishes one verified reconstruction without changing any unrelated replay state.
	 *
	 * @param manifestFile the manifest that receives the reconstruction
	 * @param manifest the current replay state
	 * @param reconstruction the verified reconstruction to append
	 * @throws IOException if the replacement manifest cannot be written
	 */
	private void publish(Path manifestFile, BatchManifest manifest, SourceReconstruction reconstruction)
		throws IOException
	{
		List<SourceReconstruction> reconstructions = new ArrayList<>(manifest.sourceReconstructions());
		reconstructions.add(reconstruction);
		manifests.write(manifestFile, manifest.withSourceReconstructions(reconstructions));
	}
}
