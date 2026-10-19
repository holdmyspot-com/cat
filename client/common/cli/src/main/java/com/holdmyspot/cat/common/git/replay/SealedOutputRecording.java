package com.holdmyspot.cat.common.git.replay;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Records the next retained output from its sealed plan and publishes its replacement manifest state.
 */
final class SealedOutputRecording
{
	private final JsonMapper json;
	private final ManifestStore<BatchManifest> manifests;
	private final OutputPlanValidation outputPlan;
	private final OutputRecording outputRecording;

	/**
	 * Creates a sealed-output recording boundary.
	 *
	 * @param json the mapper for reviewer-authored batch declarations
	 * @param manifests the owner of manifest publication
	 * @param outputPlan the owner of sealed-plan validation
	 * @param outputRecording the owner of patch application and witness creation
	 */
	SealedOutputRecording(JsonMapper json, ManifestStore<BatchManifest> manifests, OutputPlanValidation outputPlan,
		OutputRecording outputRecording)
	{
		this.json = json;
		this.manifests = manifests;
		this.outputPlan = outputPlan;
		this.outputRecording = outputRecording;
	}

	/**
	 * Records the next retained output from reviewer-authored batch declarations.
	 *
	 * @param manifestFile the manifest that owns the output and batch file
	 * @param manifest the current unfinalized replay state
	 * @param outputId the retained output to record next
	 * @param batchFile the reviewer-authored batch declarations
	 * @throws IOException if the output cannot be recorded from the sealed plan
	 */
	void record(Path manifestFile, BatchManifest manifest, String outputId, Path batchFile) throws IOException
	{
		BatchRequest[] requests = json.readValue(batchFile.toFile(), BatchRequest[].class);
		record(manifestFile, manifest, outputId, batchFile, requests);
	}

	/**
	 * Records the next retained output from the sealed batch declarations without retaining an intermediate batch file.
	 *
	 * @param manifestFile the manifest that owns the output
	 * @param manifest the current unfinalized replay state
	 * @param outputId the retained output to record next
	 * @throws IOException if the output cannot be recorded from the sealed plan
	 */
	void recordPlanned(Path manifestFile, BatchManifest manifest, String outputId) throws IOException
	{
		PlannedOutput planned = outputPlan.sealedOutput(manifest, outputId);
		if (!planned.disposition().equals("retained"))
		{
			throw new IOException("Topic-replay output plan requires " + outputId + " to be discarded, not retained");
		}
		Path directory = manifestFile.toAbsolutePath().normalize().getParent();
		Path temporary = Files.createTempFile(directory, "record-planned-output-", ".json");
		try
		{
			BatchRequest[] requests = planned.batches().stream().map(batch -> new BatchRequest(batch.id(), batch.source(),
				batch.patch().path())).toArray(BatchRequest[]::new);
			json.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), requests);
			record(manifestFile, manifest, outputId, temporary, requests);
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Validates one retained-output decision, applies its sealed batches, and publishes the resulting manifest.
	 *
	 * @param manifestFile the manifest that receives the decision
	 * @param manifest the current unfinalized replay state
	 * @param outputId the output to record
	 * @param batchFile the declaration source named in validation failures
	 * @param requests the batch declarations to validate against the sealed plan
	 * @throws IOException if the output decision or batch declarations are invalid
	 */
	private void record(Path manifestFile, BatchManifest manifest, String outputId, Path batchFile,
		BatchRequest[] requests) throws IOException
	{
		if (manifest.outputs().stream().noneMatch(output -> output.id().equals(outputId)))
			throw new IOException("Topic-replay manifest does not contain output: " + outputId);
		if (!manifest.outputWitnesses().isEmpty() && manifest.outputWitnesses().getLast().afterTree() == null)
		{
			throw new IOException("Topic-replay output " + manifest.outputWitnesses().getLast().outputId() +
				" must be completed before recording " + outputId);
		}
		String expectedOutput = nextUndecidedOutput(manifest);
		if (!outputId.equals(expectedOutput))
			throw new IOException("Topic-replay must record output " + expectedOutput + " before " + outputId);
		PlannedOutput plannedOutput = outputPlan.sealedOutput(manifest, outputId);
		if (!plannedOutput.disposition().equals("retained"))
		{
			throw new IOException("Topic-replay output plan requires " + outputId + " to be discarded, not retained");
		}
		if (!outputPlan.hasSameBatches(List.of(requests), plannedOutput.batches()))
		{
			throw new IOException("Topic-replay output batch declarations must match the sealed output plan: " + outputId);
		}
		if (requests.length == 0)
			throw new IOException("Topic-replay output batch file must declare at least one batch: " + batchFile);
		BatchManifest recorded = outputRecording.record(manifestFile, manifest, outputId, batchFile, requests,
			plannedOutput);
		manifests.write(manifestFile, recorded);
	}

	/**
	 * Returns the next output without a retained or discarded decision.
	 *
	 * @param manifest the replay state that supplies output decisions
	 * @return the next output identifier in chronological order
	 * @throws IOException if every output already has a decision
	 */
	private String nextUndecidedOutput(BatchManifest manifest) throws IOException
	{
		Set<String> decidedOutputs = new HashSet<>();
		if (manifest.discardedOutputs() != null)
			decidedOutputs.addAll(manifest.discardedOutputs());
		manifest.outputWitnesses().forEach(witness -> decidedOutputs.add(witness.outputId()));
		return manifest.outputs().stream().map(Output::id).filter(output -> !decidedOutputs.contains(output)).findFirst().
			orElseThrow(() -> new IOException("Topic-replay has already decided every output"));
	}
}
