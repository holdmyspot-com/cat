package io.github.cowwoc.cat.common.git.replay;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Removes one unsealed output suffix and every review decision that depends on its placement.
 */
final class OutputSuffixInvalidation
{
	/**
	 * Returns the manifest state after invalidating one output and every later output.
	 *
	 * @param manifest the unsealed manifest whose decisions are being rebuilt
	 * @param outputId the first output whose placement is invalid
	 * @return the retained prefix with dependent batches, witnesses, discards, and source reviews removed
	 * @throws IOException if an output is open and therefore cannot be safely invalidated
	 */
	BatchManifest invalidate(BatchManifest manifest, String outputId) throws IOException
	{
		int firstInvalidated = outputIndex(manifest.outputs(), outputId);
		if (manifest.outputWitnesses().stream().anyMatch(witness -> witness.afterTree() == null))
			throw new IOException("Topic-replay cannot invalidate outputs while an output is open");
		Set<String> invalidatedSources = manifest.batches().stream().filter(batch -> outputIndex(manifest.outputs(),
			batch.destination()) >= firstInvalidated).map(Batch::source).collect(Collectors.toSet());
		List<Batch> batches = manifest.batches().stream().filter(batch -> outputIndex(manifest.outputs(),
			batch.destination()) < firstInvalidated).toList();
		List<OutputWitness> witnesses = manifest.outputWitnesses().stream().filter(witness -> outputIndex(
			manifest.outputs(), witness.outputId()) < firstInvalidated).toList();
		List<String> discarded = discardedOutputs(manifest).stream().filter(candidate -> outputIndex(manifest.outputs(),
			candidate) < firstInvalidated).toList();
		List<SourceReview> reviews = sourceReviews(manifest).stream().filter(review -> !invalidatedSources.contains(
			review.source())).toList();
		return manifest.withBatches(batches).withOutputWitnesses(witnesses).withDiscardedOutputs(discarded).
			withSourceReviews(reviews);
	}

	/**
	 * Returns the position of an output in final-history order.
	 *
	 * @param outputs declared outputs
	 * @param outputId the output identifier
	 * @return the zero-based output position
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

	/**
	 * Returns recorded source reviews, treating an omitted unfinished field as empty.
	 *
	 * @param manifest the manifest to inspect
	 * @return its explicit or implied source reviews
	 */
	private List<SourceReview> sourceReviews(BatchManifest manifest)
	{
		if (manifest.sourceReviews() == null)
			return List.of();
		return manifest.sourceReviews();
	}
}
