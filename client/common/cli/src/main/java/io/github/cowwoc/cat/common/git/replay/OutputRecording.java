package io.github.cowwoc.cat.common.git.replay;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Applies one sealed retained output's batch declarations and returns its updated immutable replay state.
 */
final class OutputRecording
{
	private final History history;
	private final PatchArtifacts patches;
	private final PatchApplication patchApplication;

	/**
	 * Creates an output-recording boundary.
	 *
	 * @param history the owner of replay base-tree facts
	 * @param patches the owner of manifest-local patch artifacts
	 * @param patchApplication the isolated patch-application boundary
	 */
	OutputRecording(History history, PatchArtifacts patches,
		PatchApplication patchApplication)
	{
		this.history = history;
		this.patches = patches;
		this.patchApplication = patchApplication;
	}

	/**
	 * Applies one output's sealed batch declarations and returns the replacement manifest state.
	 *
	 * @param manifestFile the manifest that owns the batches' patch artifacts
	 * @param manifest the current replay state
	 * @param outputId the retained output to record
	 * @param batchFile the file that supplied the reviewer declarations
	 * @param requests the reviewer declarations in sealed order
	 * @param plannedOutput the matching sealed output declaration
	 * @return the state containing the recorded batches and retained-output witness
	 * @throws IOException if a batch declaration is unsafe, duplicates a batch, or no longer matches the sealed plan
	 */
	BatchManifest record(Path manifestFile, BatchManifest manifest, String outputId, Path batchFile,
		BatchRequest[] requests, PlannedOutput plannedOutput) throws IOException
	{
		Set<String> batchIds = new HashSet<>();
		for (Batch batch : manifest.batches())
			batchIds.add(batch.id());
		Set<String> sources = new HashSet<>();
		for (SourceSnapshot source : manifest.sources())
			sources.add(source.hash());
		List<Batch> batches = new ArrayList<>(manifest.batches());
		String beforeTree;
		if (manifest.outputWitnesses().isEmpty())
			beforeTree = history.baseTree(manifest.base());
		else
			beforeTree = manifest.outputWitnesses().getLast().afterTree();
		String currentTree = beforeTree;
		for (int index = 0; index < requests.length; ++index)
		{
			BatchRequest request = requests[index];
			if (request == null || request.id() == null || request.id().isBlank() || request.source() == null ||
				request.source().isBlank() || request.patch() == null || request.patch().isBlank())
			{
				throw new IOException("Topic-replay output batch declarations require non-blank id, source, and patch " +
					"values: " + batchFile);
			}
			if (!batchIds.add(request.id()))
				throw new IOException("Topic-replay manifest already contains batch: " + request.id());
			if (!sources.contains(request.source()))
				throw new IOException("Topic-replay manifest does not contain source commit: " + request.source());
			Path patchFile = patches.resolve(manifestFile, request.patch());
			PatchArtifact patch = patches.declare(manifestFile, patchFile);
			if (!patch.equals(plannedOutput.batches().get(index).patch()))
			{
				throw new IOException("Topic-replay output batch patch no longer matches the sealed output plan: " +
					request.id());
			}
			String afterTree = patchApplication.apply(manifestFile, patches.resolve(manifestFile, patch), currentTree);
			batches.add(new Batch(request.id(), request.source(), outputId, patch,
				new DestinationWitness(currentTree, afterTree)));
			currentTree = afterTree;
		}
		List<OutputWitness> witnesses = new ArrayList<>(manifest.outputWitnesses());
		witnesses.add(new OutputWitness(outputId, beforeTree, currentTree));
		return manifest.withBatches(batches).withOutputWitnesses(witnesses);
	}
}
