package io.github.cowwoc.cat.common.git.replay;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates that retained-output witnesses describe one complete chronological replay state.
 */
final class OutputWitnessValidation
{
	/**
	 * Validates retained witnesses, discarded outputs, and batch tree continuity.
	 *
	 * @param manifest the batch manifest
	 * @param outputs declared outputs indexed by identifier
	 * @throws IOException if a witness is missing, unordered, open, or disconnected from its predecessor
	 */
	void validate(BatchManifest manifest, Map<String, Output> outputs) throws IOException
	{
		if (manifest.outputWitnesses() == null || manifest.outputWitnesses().isEmpty())
			throw new IOException("Topic-replay batch manifest must declare retained output witnesses");
		Set<String> destinations = manifest.batches().stream().map(Batch::destination).collect(Collectors.toSet());
		Set<String> discardedOutputs = new HashSet<>();
		for (String outputId : discardedOutputs(manifest))
		{
			if (outputId == null || !outputs.containsKey(outputId) || !discardedOutputs.add(outputId))
				throw new IOException("Every discarded output must be a unique declared output identifier");
		}
		Set<String> witnessedOutputs = new HashSet<>();
		String expectedBeforeTree = manifest.sources().getFirst().parentTree();
		int previousIndex = -1;
		for (OutputWitness witness : manifest.outputWitnesses())
		{
			if (witness == null || witness.outputId() == null || !outputs.containsKey(witness.outputId()) ||
				!witnessedOutputs.add(witness.outputId()) || !isFullHash(witness.beforeTree()) ||
				!isFullHash(witness.afterTree()))
			{
				throw new IOException(
					"Every retained output witness requires a unique output and full before/after tree hashes");
			}
			int index = outputIndex(manifest.outputs(), witness.outputId());
			if (index <= previousIndex)
				throw new IOException("Retained output witnesses must be in manifest output order");
			if (!witness.beforeTree().equals(expectedBeforeTree))
			{
				throw new IOException("Retained output " + witness.outputId() + " must start from the preceding retained " +
					"output's final tree");
			}
			expectedBeforeTree = witness.afterTree();
			previousIndex = index;
		}
		if (!witnessedOutputs.equals(destinations))
			throw new IOException("Retained output witnesses must account for every batch destination exactly once");
		Set<String> decidedOutputs = new HashSet<>(witnessedOutputs);
		decidedOutputs.addAll(discardedOutputs);
		if (!Set.copyOf(manifest.outputs().stream().map(Output::id).toList()).equals(decidedOutputs))
			throw new IOException("Every logical output must be retained or explicitly discarded");
		if (witnessedOutputs.stream().anyMatch(discardedOutputs::contains))
			throw new IOException("A discarded output must not also be retained");
		for (String outputId : witnessedOutputs)
			validateOutputBatches(manifest, outputId);
	}

	/**
	 * Verifies that one retained output's batches form its witnessed tree sequence.
	 *
	 * @param manifest the batch manifest
	 * @param outputId the retained output identifier
	 * @throws IOException if a batch begins from the wrong tree or the final tree differs from the witness
	 */
	private void validateOutputBatches(BatchManifest manifest, String outputId) throws IOException
	{
		OutputWitness witness = outputWitness(manifest, outputId);
		String previousTree = witness.beforeTree();
		for (Batch batch : manifest.batches())
		{
			if (!batch.destination().equals(outputId))
				continue;
			if (!batch.destinationWitness().beforeTree().equals(previousTree))
			{
				throw new IOException("Topic-replay batch " + batch.id() + " does not continue output " + outputId +
					" from its preceding tree");
			}
			previousTree = batch.destinationWitness().afterTree();
		}
		if (!previousTree.equals(witness.afterTree()))
			throw new IOException("Retained output " + outputId + " final tree does not match its last batch");
	}

	/**
	 * Returns discarded output identifiers, treating an omitted unfinished-manifest field as empty.
	 *
	 * @param manifest the replay manifest
	 * @return the declared or implied discarded output identifiers
	 */
	private List<String> discardedOutputs(BatchManifest manifest)
	{
		if (manifest.discardedOutputs() == null)
			return List.of();
		return manifest.discardedOutputs();
	}

	/**
	 * Finds a retained output's closed witness.
	 *
	 * @param manifest the batch manifest
	 * @param outputId the retained output identifier
	 * @return the retained output witness
	 * @throws IOException if the output has no retained witness
	 */
	private OutputWitness outputWitness(BatchManifest manifest, String outputId) throws IOException
	{
		return manifest.outputWitnesses().stream().filter(witness -> witness.outputId().equals(outputId)).findFirst().
			orElseThrow(() -> new IOException("Topic-replay manifest does not contain retained output: " + outputId));
	}

	/**
	 * Finds an output's chronological position in the rewritten history.
	 *
	 * @param outputs declared outputs in chronological order
	 * @param outputId the output identifier
	 * @return the zero-based output position
	 * @throws AssertionError if {@code outputId} is not declared
	 */
	private int outputIndex(List<Output> outputs, String outputId)
	{
		for (int index = 0; index < outputs.size(); ++index)
		{
			if (outputs.get(index).id().equals(outputId))
				return index;
		}
		throw new AssertionError("Unknown topic-replay output: " + outputId);
	}

	/**
	 * Indicates whether a value is one complete lowercase Git object hash.
	 *
	 * @param value the candidate object hash
	 * @return {@code true} if the value is a full lowercase SHA-1 or SHA-256 hash
	 */
	private boolean isFullHash(String value)
	{
		return value != null && value.matches("[0-9a-f]{40}|[0-9a-f]{64}");
	}
}
