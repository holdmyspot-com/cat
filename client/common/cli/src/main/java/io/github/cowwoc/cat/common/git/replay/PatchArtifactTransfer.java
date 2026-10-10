package io.github.cowwoc.cat.common.git.replay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Copies immutable patch artifacts between compatible replay manifests.
 */
public final class PatchArtifactTransfer
{
	private final PatchArtifacts patches;

	/**
	 * Creates the patch-transfer owner.
	 *
	 * @param patches the owner of manifest-local patch identity and path validation
	 */
	public PatchArtifactTransfer(PatchArtifacts patches)
	{
		this.patches = patches;
	}

	/**
	 * Copies each batch and reconstruction patch below the receiving manifest and returns rewritten declarations.
	 *
	 * @param previousManifestFile the manifest that owns the reviewed patch artifacts
	 * @param newManifestFile the manifest that will own copied artifacts
	 * @param previous the manifest that references the artifacts
	 * @param importedBatches the batches whose placement is imported
	 * @return the old-path-to-new-artifact map
	 * @throws IOException if an artifact cannot be verified or copied safely
	 */
	public Map<String, PatchArtifact> copy(Path previousManifestFile, Path newManifestFile, BatchManifest previous,
		List<Batch> importedBatches) throws IOException
	{
		Map<String, PatchArtifact> result = new HashMap<>();
		List<PatchArtifact> artifacts = new ArrayList<>();
		importedBatches.forEach(batch -> artifacts.add(batch.patch()));
		previous.sourceReconstructions().stream().map(SourceReconstruction::sourcePatch).filter(patch -> patch != null).
			forEach(artifacts::add);
		Path directory = newManifestFile.toAbsolutePath().normalize().getParent().resolve("imported-patches");
		for (PatchArtifact artifact : artifacts)
		{
			if (result.containsKey(artifact.path()))
				continue;
			Path source = patches.resolve(previousManifestFile, artifact);
			Path target = directory.resolve(artifact.sha256() + ".patch");
			Files.createDirectories(target.getParent());
			if (!Files.exists(target))
				Files.copy(source, target);
			PatchArtifact copied = patches.declare(newManifestFile, target);
			if (!copied.sha256().equals(artifact.sha256()))
				throw new IOException("Topic-replay imported patch changed while copying: " + source);
			result.put(artifact.path(), copied);
		}
		return result;
	}
}
