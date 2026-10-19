package com.holdmyspot.cat.common.git.replay;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates the contiguous replay prefix that may be imported into an extended review.
 */
public final class SealedPrefixValidation
{
	/**
	 * Returns the number of output positions in a complete mechanically verified prefix.
	 *
	 * @param manifest the manifest whose prefix is being imported
	 * @return the number of sealed output positions
	 * @throws IOException if the prefix is missing, gapped, or mechanically inconsistent
	 */
	public int length(BatchManifest manifest) throws IOException
	{
		if (manifest.outputs() == null || manifest.outputs().isEmpty() || manifest.batches() == null ||
			manifest.sourceReconstructions() == null || manifest.outputWitnesses() == null)
		{
			throw new IOException("Topic-replay reviewed prefix requires outputs, batches, reconstructions, and witnesses");
		}
		Map<String, Output> outputs = outputsById(manifest.outputs());
		Set<String> sources = sourceHashes(manifest);
		if (outputs.values().stream().anyMatch(output -> output.source() != null && !sources.contains(output.source())))
			throw new IOException("A source-backed topic-replay output must refer to a captured source commit");
		Set<String> discarded = discardedOutputs(manifest, outputs);
		Set<String> witnessed = witnessedOutputs(manifest, outputs);
		Set<String> decided = new HashSet<>(witnessed);
		decided.addAll(discarded);
		int length = 0;
		while (length < manifest.outputs().size() && decided.contains(manifest.outputs().get(length).id()))
			++length;
		if (length == 0)
			throw new IOException("Topic-replay import requires at least one sealed output");
		for (int index = length; index < manifest.outputs().size(); ++index)
		{
			if (decided.contains(manifest.outputs().get(index).id()))
				throw new IOException("Topic-replay reviewed outputs must form one contiguous sealed prefix");
		}
		validateBatches(manifest, outputs, sources, witnessed, length);
		validateSourceReconstructions(manifest, sources);
		return length;
	}

	/**
	 * Indexes declared outputs by their stable identifiers.
	 *
	 * @param outputs outputs declared by the manifest
	 * @return outputs indexed by identifier
	 * @throws IOException if an identifier is missing or duplicated
	 */
	private Map<String, Output> outputsById(List<Output> outputs) throws IOException
	{
		Map<String, Output> result = new HashMap<>();
		for (Output output : outputs)
		{
			if (output == null || output.id() == null || output.id().isBlank() || result.put(output.id(), output) != null)
				throw new IOException("Topic-replay outputs require unique non-blank identifiers");
		}
		return result;
	}

	/**
	 * Validates and collects immutable source hashes.
	 *
	 * @param manifest the replay manifest
	 * @return unique captured source hashes
	 * @throws IOException if a source snapshot is incomplete or duplicated
	 */
	private Set<String> sourceHashes(BatchManifest manifest) throws IOException
	{
		Set<String> sources = new HashSet<>();
		for (SourceSnapshot source : manifest.sources())
		{
			if (source == null || !isFullHash(source.hash()) || !isFullHash(source.parentTree()) ||
				!isFullHash(source.tree()) || !sources.add(source.hash()))
			{
				throw new IOException("Every source snapshot requires a unique full commit hash and parent/source tree hashes");
			}
		}
		return sources;
	}

	/**
	 * Validates and collects declared discarded outputs.
	 *
	 * @param manifest the replay manifest
	 * @param outputs outputs indexed by identifier
	 * @return unique declared discarded output identifiers
	 * @throws IOException if a discarded output is unknown or repeated
	 */
	private Set<String> discardedOutputs(BatchManifest manifest, Map<String, Output> outputs) throws IOException
	{
		Set<String> discarded = new HashSet<>();
		List<String> declared = manifest.discardedOutputs();
		if (declared == null)
			return discarded;
		for (String outputId : declared)
		{
			if (outputId == null || !outputs.containsKey(outputId) || !discarded.add(outputId))
				throw new IOException("Every discarded output must be a unique declared output identifier");
		}
		return discarded;
	}

	/**
	 * Validates and collects retained output witnesses in chronological order.
	 *
	 * @param manifest the replay manifest
	 * @param outputs outputs indexed by identifier
	 * @return unique witnessed output identifiers
	 * @throws IOException if a witness is incomplete, repeated, or disconnected from its predecessor
	 */
	private Set<String> witnessedOutputs(BatchManifest manifest, Map<String, Output> outputs) throws IOException
	{
		Set<String> witnessed = new HashSet<>();
		String expectedBeforeTree = manifest.sources().getFirst().parentTree();
		int previousIndex = -1;
		for (OutputWitness witness : manifest.outputWitnesses())
		{
			if (witness == null || witness.outputId() == null || !outputs.containsKey(witness.outputId()) ||
				!witnessed.add(witness.outputId()) || !isFullHash(witness.beforeTree()) ||
				!isFullHash(witness.afterTree()))
			{
				throw new IOException(
					"Every retained prefix witness requires a unique output and full before/after tree hashes");
			}
			int index = outputIndex(manifest.outputs(), witness.outputId());
			if (index <= previousIndex || !witness.beforeTree().equals(expectedBeforeTree))
				throw new IOException("Retained prefix witnesses must form one chronological tree chain");
			expectedBeforeTree = witness.afterTree();
			previousIndex = index;
		}
		return witnessed;
	}

	/**
	 * Validates batches that establish outputs in the importable prefix.
	 *
	 * @param manifest the replay manifest
	 * @param outputs outputs indexed by identifier
	 * @param sources captured source hashes
	 * @param witnessed retained output identifiers
	 * @param sealedPrefixLength number of sealed output positions
	 * @throws IOException if a batch fails to prove the sealed prefix
	 */
	private void validateBatches(BatchManifest manifest, Map<String, Output> outputs, Set<String> sources,
		Set<String> witnessed, int sealedPrefixLength) throws IOException
	{
		Set<String> batchIds = new HashSet<>();
		Set<String> destinations = new HashSet<>();
		for (Batch batch : manifest.batches())
		{
			if (batch.id() == null || batch.id().isBlank() || !batchIds.add(batch.id()) || !isFullHash(batch.source()) ||
				!sources.contains(batch.source()) || !outputs.containsKey(batch.destination()) || batch.patch() == null ||
				batch.destinationWitness() == null || outputIndex(manifest.outputs(), batch.destination()) >=
					sealedPrefixLength ||
				!isFullHash(batch.destinationWitness().beforeTree()) || !isFullHash(batch.destinationWitness().afterTree()))
			{
				throw new IOException("Every imported batch must belong to the sealed prefix and have complete witnesses");
			}
			destinations.add(batch.destination());
		}
		if (!witnessed.equals(destinations))
			throw new IOException("Retained prefix witnesses must account for every imported batch destination exactly once");
		for (String outputId : witnessed)
			validateOutputBatches(manifest, outputId);
	}

	/**
	 * Validates tree continuity among one retained output's batches.
	 *
	 * @param manifest the replay manifest
	 * @param outputId the retained output identifier
	 * @throws IOException if a batch or final tree differs from the retained witness
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
				throw new IOException("Topic-replay batch " + batch.id() + " does not continue its retained output");
			previousTree = batch.destinationWitness().afterTree();
		}
		if (!previousTree.equals(witness.afterTree()))
			throw new IOException("Retained prefix output " + outputId + " does not match its last batch");
	}

	/**
	 * Validates source reconstruction declarations against captured sources and prefix batches.
	 *
	 * @param manifest the replay manifest
	 * @param sources captured source hashes
	 * @throws IOException if a reconstruction is missing or inconsistent with captured source facts
	 */
	private void validateSourceReconstructions(BatchManifest manifest, Set<String> sources) throws IOException
	{
		Set<String> reconstructed = new HashSet<>();
		Set<String> batchIds = new HashSet<>();
		for (Batch batch : manifest.batches())
			batchIds.add(batch.id());
		for (SourceReconstruction reconstruction : manifest.sourceReconstructions())
		{
			if (reconstruction == null || reconstruction.source() == null || !sources.contains(reconstruction.source()) ||
				!reconstructed.add(reconstruction.source()) || !isFullHash(reconstruction.beforeTree()) ||
				!isFullHash(reconstruction.afterTree()))
			{
				throw new IOException("Every source reconstruction requires one captured source and full tree hashes");
			}
			SourceSnapshot source = manifest.sources().stream().filter(candidate -> candidate.hash().equals(
				reconstruction.source())).findFirst().orElseThrow();
			if (!source.parentTree().equals(reconstruction.beforeTree()) || !source.tree().equals(reconstruction.afterTree()))
			{
				throw new IOException("Source reconstruction tree endpoints do not match captured source facts: " +
					reconstruction.source());
			}
			boolean batchSequence = reconstruction.batchIds() != null && !reconstruction.batchIds().isEmpty();
			if (batchSequence == (reconstruction.sourcePatch() != null))
				throw new IOException("Every source reconstruction requires exactly one batch sequence or source patch");
			if (batchSequence)
			{
				for (String batchId : reconstruction.batchIds())
				{
					if (!batchIds.contains(batchId))
						throw new IOException("Source reconstruction references an unknown batch");
					Batch batch = manifest.batches().stream().filter(candidate -> candidate.id().equals(batchId)).findFirst().
						orElseThrow();
					if (!batch.source().equals(reconstruction.source()))
						throw new IOException("Source reconstruction assigns batch " + batchId + " to the wrong source");
				}
			}
		}
		if (!reconstructed.equals(sources))
			throw new IOException("Source reconstructions must account for every captured source exactly once");
	}

	/**
	 * Finds one closed retained-output witness.
	 *
	 * @param manifest the replay manifest
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
	 * Finds an output's chronological position.
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
