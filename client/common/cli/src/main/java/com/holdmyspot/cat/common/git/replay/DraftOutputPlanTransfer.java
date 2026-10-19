package com.holdmyspot.cat.common.git.replay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import tools.jackson.databind.json.JsonMapper;

/**
 * Transfers an unsealed reviewer output plan and its verified patch artifacts between compatible manifests.
 */
public final class DraftOutputPlanTransfer
{
	private final JsonMapper json;
	private final PatchArtifacts patches;

	/**
	 * Creates the transfer owner.
	 *
	 * @param json the mapper that reads and writes reviewer plan input
	 * @param patches the owner of manifest-local patch identity
	 */
	public DraftOutputPlanTransfer(JsonMapper json, PatchArtifacts patches)
	{
		this.json = json;
		this.patches = patches;
	}

	/**
	 * Copies an unsealed plan and its digest-verified patch artifacts without adding replay decisions to the replacement
	 * manifest.
	 *
	 * @param previousManifestFile the manifest that owns the source plan and patch artifacts
	 * @param previousPlanFile the named unsealed source plan
	 * @param previous the immutable source manifest state
	 * @param manifestFile the replacement manifest that will own copied patches
	 * @param replacement the immutable replacement manifest state
	 * @param planFile the new replacement-manifest-local draft plan
	 * @throws IOException if either manifest state, a plan path, or a patch artifact is unsafe or incompatible
	 */
	public void transfer(Path previousManifestFile, Path previousPlanFile, BatchManifest previous, Path manifestFile,
		BatchManifest replacement, Path planFile) throws IOException
	{
		if (previous.sourceReviews().isEmpty())
		{
			throw new IOException("Topic-replay source manifest must contain source reviews before transferring a " +
				"draft plan");
		}
		if (replacement.outputPlan() != null || !replacement.batches().isEmpty() ||
			!replacement.outputWitnesses().isEmpty() || !discardedOutputs(replacement).isEmpty())
		{
			throw new IOException("Topic-replay replacement manifest must not contain replay decisions before transferring " +
				"a draft plan");
		}
		if (replacement.sources().size() < previous.sources().size())
			throw new IOException("Topic-replay replacement manifest has fewer sources than the draft manifest");
		ReviewEvidenceTransfer.sourceTransitionMap(previous.sources(), replacement.sources(), Set.of());
		Path previousDirectory = previousManifestFile.toAbsolutePath().normalize().getParent();
		Path sourcePlan = previousPlanFile.toAbsolutePath().normalize();
		if (!sourcePlan.startsWith(previousDirectory))
			throw new IOException("Topic-replay draft plan must be below its manifest: " + previousPlanFile);
		Path directory = manifestFile.toAbsolutePath().normalize().getParent();
		Path target = planFile.toAbsolutePath().normalize();
		if (!target.startsWith(directory) || Files.exists(target))
		{
			throw new IOException("Topic-replay draft-plan target must be new and below the replacement manifest: " +
				planFile);
		}
		OutputPlanRequest[] requests = json.readValue(sourcePlan.toFile(), OutputPlanRequest[].class);
		if (requests == null || requests.length == 0)
			throw new IOException("Topic-replay draft plan must contain output entries: " + previousPlanFile);
		List<OutputPlanRequest> copiedPlan = new ArrayList<>(requests.length);
		for (OutputPlanRequest request : requests)
			copiedPlan.add(copyRequest(previousManifestFile, manifestFile, previousDirectory, directory, request));
		Files.createDirectories(target.getParent());
		json.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), copiedPlan);
	}

	/**
	 * Copies one output-plan request and every patch it declares.
	 *
	 * @param previousManifestFile the source patch owner's manifest
	 * @param manifestFile the copied patch owner's manifest
	 * @param previousDirectory the source manifest directory
	 * @param directory the replacement manifest directory
	 * @param request the reviewer request to copy
	 * @return the request with replacement-manifest-local patch declarations
	 * @throws IOException if a declared patch cannot be safely copied and verified
	 */
	private OutputPlanRequest copyRequest(Path previousManifestFile, Path manifestFile, Path previousDirectory,
		Path directory, OutputPlanRequest request) throws IOException
	{
		List<BatchRequest> copiedBatches = new ArrayList<>();
		if (request.batches() != null)
		{
			for (BatchRequest batch : request.batches())
				copiedBatches.add(copyBatch(previousManifestFile, manifestFile, previousDirectory, directory, batch));
		}
		return new OutputPlanRequest(request.outputId(), request.disposition(), copiedBatches);
	}

	/**
	 * Copies one declared source patch and verifies its immutable digest after publication.
	 *
	 * @param previousManifestFile the source patch owner's manifest
	 * @param manifestFile the copied patch owner's manifest
	 * @param previousDirectory the source manifest directory
	 * @param directory the replacement manifest directory
	 * @param batch the batch declaration that names the patch
	 * @return the batch with the copied patch declaration
	 * @throws IOException if the source patch is unsafe, absent, or changes while it is copied
	 */
	private BatchRequest copyBatch(Path previousManifestFile, Path manifestFile, Path previousDirectory, Path directory,
		BatchRequest batch) throws IOException
	{
		Path source = previousDirectory.resolve(batch.patch()).normalize();
		if (!source.startsWith(previousDirectory) || !Files.isRegularFile(source))
		{
			throw new IOException("Topic-replay draft plan references a missing manifest-local patch: " + batch.patch());
		}
		PatchArtifact artifact = patches.declare(previousManifestFile, source);
		Path copied = directory.resolve("patches").resolve(artifact.sha256() + ".patch");
		Files.createDirectories(copied.getParent());
		if (!Files.exists(copied))
			Files.copy(source, copied);
		PatchArtifact copiedArtifact = patches.declare(manifestFile, copied);
		if (!copiedArtifact.sha256().equals(artifact.sha256()))
			throw new IOException("Topic-replay draft patch changed while copying: " + source);
		return new BatchRequest(batch.id(), batch.source(), copiedArtifact.path());
	}

	/**
	 * Returns the manifest's declared discarded outputs, treating an omitted unfinished field as empty.
	 *
	 * @param manifest the manifest to inspect
	 * @return its explicit or implied discarded outputs
	 */
	private List<String> discardedOutputs(BatchManifest manifest)
	{
		if (manifest.discardedOutputs() == null)
			return List.of();
		return manifest.discardedOutputs();
	}
}
