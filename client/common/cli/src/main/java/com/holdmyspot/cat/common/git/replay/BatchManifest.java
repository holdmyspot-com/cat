package com.holdmyspot.cat.common.git.replay;

import java.util.List;

/**
 * The version-5 subset needed to execute immutable reviewer-authored patch batches.
 *
 * @param version the manifest format version
 * @param base the commit immediately before the replay range
 * @param tipTree the original range's required final tree
 * @param sources the immutable source commit and tree snapshots
 * @param outputs the ordered logical output commits
 * @param inventory the immutable path inventory generated from source commits
 * @param batches the declared patch batches
 * @param sourceReconstructions the complete ordered batch sequence for each source commit
 * @param outputWitnesses the ordered retained outputs and their sealed tree transitions
 * @param discardedOutputs the ordered logical outputs absorbed into retained outputs
 * @param sourceReviews the completed reviewer atomicity claims for source commits
 * @param fixedSources the mechanically preserved sources that a known-complete prefix owns
 * @param outputPlan the complete validated output plan, before individual output decisions are recorded
 */
public record BatchManifest(int version, String base, String tipTree, List<SourceSnapshot> sources,
	List<Output> outputs, List<InventoryItem> inventory, List<Batch> batches,
	List<SourceReconstruction> sourceReconstructions, List<OutputWitness> outputWitnesses,
	List<String> discardedOutputs, List<SourceReview> sourceReviews, List<String> fixedSources, OutputPlan outputPlan)
	implements ManifestStore.Manifest
{
	/**
	 * Indicates whether this stored manifest has immutable source snapshots for review and replay planning.
	 *
	 * @return {@code true} if immutable source snapshots are present
	 */
	@Override
	public boolean hasSources()
	{
		return sources != null && !sources.isEmpty();
	}

	/**
	 * Returns this manifest with replacement batches.
	 *
	 * @param replacement the replacement batches
	 * @return the updated manifest
	 */
	public BatchManifest withBatches(List<Batch> replacement)
	{
		return new BatchManifest(version, base, tipTree, sources, outputs, inventory, replacement,
			sourceReconstructions, outputWitnesses, discardedOutputs, sourceReviews, fixedSources, outputPlan);
	}

	/**
	 * Returns this manifest with replacement source reconstructions.
	 *
	 * @param replacement the replacement reconstructions
	 * @return the updated manifest
	 */
	BatchManifest withSourceReconstructions(List<SourceReconstruction> replacement)
	{
		return new BatchManifest(version, base, tipTree, sources, outputs, inventory, batches, replacement,
			outputWitnesses, discardedOutputs, sourceReviews, fixedSources, outputPlan);
	}

	/**
	 * Returns this manifest with replacement retained-output witnesses.
	 *
	 * @param replacement the replacement witnesses
	 * @return the updated manifest
	 */
	public BatchManifest withOutputWitnesses(List<OutputWitness> replacement)
	{
		return new BatchManifest(version, base, tipTree, sources, outputs, inventory, batches,
			sourceReconstructions, replacement, discardedOutputs, sourceReviews, fixedSources, outputPlan);
	}

	/**
	 * Returns this manifest with replacement discarded outputs.
	 *
	 * @param replacement the replacement absorbed-output identifiers
	 * @return the updated manifest
	 */
	public BatchManifest withDiscardedOutputs(List<String> replacement)
	{
		return new BatchManifest(version, base, tipTree, sources, outputs, inventory, batches,
			sourceReconstructions, outputWitnesses, replacement, sourceReviews, fixedSources, outputPlan);
	}

	/**
	 * Returns this manifest with replacement source reviews.
	 *
	 * @param replacement the replacement reviews
	 * @return the updated manifest
	 */
	BatchManifest withSourceReviews(List<SourceReview> replacement)
	{
		return new BatchManifest(version, base, tipTree, sources, outputs, inventory, batches,
			sourceReconstructions, outputWitnesses, discardedOutputs, replacement, fixedSources, outputPlan);
	}

	/**
	 * Returns this manifest with the mechanically fixed source prefix.
	 *
	 * @param replacement the fixed source hashes
	 * @return the updated manifest
	 */
	BatchManifest withFixedSources(List<String> replacement)
	{
		return new BatchManifest(version, base, tipTree, sources, outputs, inventory, batches,
			sourceReconstructions, outputWitnesses, discardedOutputs, sourceReviews, replacement, outputPlan);
	}

	/**
	 * Returns this manifest with a complete validated output plan.
	 *
	 * @param replacement the replacement output plan
	 * @return the updated manifest
	 */
	public BatchManifest withOutputPlan(OutputPlan replacement)
	{
		return new BatchManifest(version, base, tipTree, sources, outputs, inventory, batches,
			sourceReconstructions, outputWitnesses, discardedOutputs, sourceReviews, fixedSources, replacement);
	}
}
