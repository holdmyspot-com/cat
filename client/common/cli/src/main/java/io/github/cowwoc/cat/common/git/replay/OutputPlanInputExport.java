package io.github.cowwoc.cat.common.git.replay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import tools.jackson.databind.json.JsonMapper;

/**
 * Exports decisions from a completed review as editable output-plan input for a compatible replacement manifest.
 */
public final class OutputPlanInputExport
{
	private final JsonMapper json;
	private final PatchArtifactTransfer patchArtifactTransfer;

	/**
	 * Creates the plan-input export owner.
	 *
	 * @param json the mapper that writes reviewer plan input
	 * @param patchArtifactTransfer the owner of imported patch transfer
	 */
	public OutputPlanInputExport(JsonMapper json, PatchArtifactTransfer patchArtifactTransfer)
	{
		this.json = json;
		this.patchArtifactTransfer = patchArtifactTransfer;
	}

	/**
	 * Copies reviewed patch artifacts and derives editable plan input without adding replay decisions to the replacement
	 * manifest.
	 *
	 * @param previousManifestFile the manifest that owns reviewed decisions and patch artifacts
	 * @param previous the completed reviewed manifest state
	 * @param manifestFile the replacement manifest that will own imported patches
	 * @param replacement the replacement state that receives plan input
	 * @param planFile the new replacement-manifest-local plan-input file
	 * @throws IOException if manifest states are incompatible, a patch cannot be copied, or the target is unsafe
	 */
	public void export(Path previousManifestFile, BatchManifest previous, Path manifestFile, BatchManifest replacement,
		Path planFile) throws IOException
	{
		if (previous.batches().isEmpty() || previous.sourceReviews().isEmpty())
			throw new IOException("Topic-replay source manifest must contain reviewed batches and source reviews");
		if (replacement.outputPlan() != null || !replacement.batches().isEmpty() ||
			!replacement.outputWitnesses().isEmpty() || !discardedOutputs(replacement).isEmpty())
		{
			throw new IOException("Topic-replay replacement manifest must not contain replay decisions before exporting " +
				"plan input");
		}
		if (replacement.sources().size() < previous.sources().size())
			throw new IOException("Topic-replay replacement manifest has fewer sources than the reviewed manifest");
		ReviewEvidenceTransfer.sourceTransitionMap(previous.sources(), replacement.sources(), Set.of());
		Path target = planFile.toAbsolutePath().normalize();
		Path directory = manifestFile.toAbsolutePath().normalize().getParent();
		if (!target.startsWith(directory) || Files.exists(target))
		{
			throw new IOException("Topic-replay plan input target must be new and below the replacement manifest: " +
				planFile);
		}
		Map<String, PatchArtifact> copied = patchArtifactTransfer.copy(previousManifestFile, manifestFile, previous,
			previous.batches());
		List<OutputPlanRequest> plan = new ArrayList<>(replacement.outputs().size());
		Set<String> retained = previous.outputWitnesses().stream().map(OutputWitness::outputId).collect(Collectors.toSet());
		Set<String> discarded = new HashSet<>(discardedOutputs(previous));
		for (int index = 0; index < replacement.outputs().size(); ++index)
		{
			Output output = replacement.outputs().get(index);
			if (index >= previous.outputs().size() || discarded.contains(output.id()))
			{
				plan.add(new OutputPlanRequest(output.id(), "discarded", List.of()));
				continue;
			}
			if (!retained.contains(output.id()))
				throw new IOException("Topic-replay reviewed manifest has no decision for output: " + output.id());
			List<BatchRequest> batches = previous.batches().stream().filter(batch -> batch.destination().equals(output.id())).
				map(batch -> new BatchRequest(batch.id(), batch.source(), copied.get(batch.patch().path()).path())).toList();
			plan.add(new OutputPlanRequest(output.id(), "retained", batches));
		}
		Files.createDirectories(target.getParent());
		json.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), plan);
	}

	/**
	 * Returns declared discarded outputs, treating an omitted unfinished field as empty.
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
