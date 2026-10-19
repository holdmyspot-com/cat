package com.holdmyspot.cat.common.git.replay;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates the reviewer decisions that assign captured source changes to replay outputs.
 */
public final class SourceReviewValidation
{
	/**
	 * Verifies every source has an explicit review of its atomicity claim.
	 *
	 * @param manifest the batch manifest
	 * @param sources the captured source hashes
	 * @throws IOException if a review is missing, malformed, or contradicts the source's batch destinations
	 */
	public void validateRecordedReviews(BatchManifest manifest, Set<String> sources) throws IOException
	{
		sources = sourcesRequiringReview(manifest, sources);
		if (manifest.sourceReviews() == null || manifest.sourceReviews().size() != sources.size())
		{
			throw new IOException("Topic-replay batch manifest must record one completed whole-or-split review for every " +
				"source commit");
		}
		Set<String> reviewedSources = new HashSet<>();
		for (SourceReview review : manifest.sourceReviews())
		{
			validateReview(manifest, review, sources, reviewedSources);
			Set<String> destinations = manifest.batches().stream().filter(batch -> batch.source().equals(review.source())).
				map(Batch::destination).collect(Collectors.toSet());
			if (review.disposition().equals("absorbed") && isDiscardedSourceOutput(manifest, review.source()) &&
				(destinations.isEmpty() || new HashSet<>(review.destinations()).equals(destinations)))
			{
				continue;
			}
			if (new HashSet<>(review.destinations()).equals(destinations) && review.disposition().equals("whole") &&
				destinations.size() == 1)
			{
				continue;
			}
			if (new HashSet<>(review.destinations()).equals(destinations) && review.disposition().equals("split") &&
				destinations.size() > 1)
			{
				continue;
			}
			throw new IOException("Source review " + review.source() + " must match its declared batch destinations");
		}
		if (!reviewedSources.equals(sources))
			throw new IOException("Topic-replay source reviews must account for every captured source exactly once");
	}

	/**
	 * Validates reviewer-authored source-review data before it changes a replay manifest.
	 *
	 * @param manifest the captured replay manifest
	 * @param reviews every submitted source review
	 * @param requireCompleteCoverage whether the submitted reviews must cover the complete source set
	 * @throws IOException if a review is malformed, duplicated, incomplete, or names an unknown destination
	 */
	public void validateInput(BatchManifest manifest, List<SourceReview> reviews, boolean requireCompleteCoverage)
		throws IOException
	{
		Set<String> sources = sourcesRequiringReview(manifest, manifest.sources().stream().map(SourceSnapshot::hash).
			collect(Collectors.toSet()));
		if (reviews.size() > sources.size() || requireCompleteCoverage && reviews.size() != sources.size())
		{
			throw new IOException("Topic-replay source-review file must contain one review for each captured source; " +
				"expected " + sources.size() + " but found " + reviews.size());
		}
		Set<String> reviewedSources = new HashSet<>();
		for (SourceReview review : reviews)
			validateReview(manifest, review, sources, reviewedSources);
		if (requireCompleteCoverage && !reviewedSources.equals(sources))
			throw new IOException("Topic-replay source-review file must account for every captured source exactly once");
	}

	/**
	 * Returns the captured sources that still require semantic review.
	 *
	 * @param manifest the replay manifest
	 * @param sources all captured source hashes
	 * @return the sources outside the optional fixed prefix
	 * @throws IOException if the declared fixed prefix is not a strict captured-source prefix
	 */
	Set<String> sourcesRequiringReview(BatchManifest manifest, Set<String> sources) throws IOException
	{
		List<String> fixed = manifest.fixedSources();
		if (fixed == null || fixed.isEmpty())
			return sources;
		if (fixed.size() >= manifest.sources().size())
			throw new IOException("Topic-replay fixed prefix must leave at least one source to review");
		for (int index = 0; index < fixed.size(); ++index)
		{
			if (!fixed.get(index).equals(manifest.sources().get(index).hash()))
			{
				throw new IOException("Topic-replay fixed sources must be one contiguous prefix of captured sources");
			}
		}
		Set<String> result = new HashSet<>(sources);
		result.removeAll(fixed);
		return result;
	}

	/**
	 * Indicates whether a source's logical output was discarded after all of its changes were placed earlier.
	 *
	 * @param manifest the reviewed replay manifest
	 * @param source the source commit
	 * @return {@code true} if the source owns a discarded logical output
	 */
	boolean isDiscardedSourceOutput(BatchManifest manifest, String source)
	{
		return manifest.outputs().stream().filter(output -> source.equals(output.source())).map(Output::id).anyMatch(
			discardedOutputs(manifest)::contains);
	}

	/**
	 * Returns the one reviewed output that represents a source, when that association is unambiguous.
	 *
	 * @param manifest the replay manifest
	 * @param source the source commit
	 * @return the output ID, or {@code null} when the source is split or has no default output
	 */
	String sourceOutput(BatchManifest manifest, String source)
	{
		for (SourceReview review : sourceReviews(manifest))
		{
			if (review.source().equals(source) && review.destinations().size() == 1)
				return review.destinations().getFirst();
		}
		return manifest.outputs().stream().filter(output -> source.equals(output.source())).map(Output::id).findFirst().
			orElse(null);
	}

	/**
	 * Validates one source review's identifiers and evidence.
	 *
	 * @param manifest the captured replay manifest
	 * @param review the reviewer-authored source review
	 * @param sources all captured source hashes
	 * @param reviewedSources the sources already seen in this input
	 * @throws IOException if the review is malformed or duplicates a source
	 */
	private void validateReview(BatchManifest manifest, SourceReview review, Set<String> sources,
		Set<String> reviewedSources) throws IOException
	{
		if (review == null || review.source() == null || !sources.contains(review.source()) ||
			!reviewedSources.add(review.source()) || review.counterfactual() == null || review.counterfactual().isBlank() ||
			review.evidence() == null || review.evidence().isBlank() || review.disposition() == null ||
			review.destinations() == null || review.destinations().isEmpty())
		{
			throw new IOException("Every source review requires one captured source, disposition, destination, " +
				"counterfactual, and evidence");
		}
		if (!review.disposition().equals("whole") && !review.disposition().equals("split") && !review.disposition().equals(
			"absorbed"))
		{
			throw new IOException("Topic-replay source review must use whole, split, or absorbed: " + review.disposition());
		}
		Set<String> outputs = manifest.outputs().stream().map(Output::id).collect(Collectors.toSet());
		if (review.destinations().stream().anyMatch(destination -> destination == null || !outputs.contains(destination)) ||
			new HashSet<>(review.destinations()).size() != review.destinations().size())
		{
			throw new IOException("Topic-replay source review destinations must be distinct retained output IDs: " +
				review.source());
		}
		if (review.disposition().equals("whole") && review.destinations().size() != 1)
			throw new IOException("A whole source review must declare exactly one destination: " + review.source());
		if (review.disposition().equals("split") && review.destinations().size() < 2)
			throw new IOException("A split source review must declare at least two destinations: " + review.source());
		if (review.disposition().equals("split"))
		{
			if (review.placements() == null || review.placements().isEmpty())
			{
				throw new IOException("A split source review must explicitly place every changed path; do not assign an " +
					"unreviewed remainder: " + review.source());
			}
			validateUnitPlacements(manifest, review);
		}
	}

	/**
	 * Validates that a source review explicitly assigns each changed path to one reviewed responsibility.
	 *
	 * @param manifest the captured replay manifest
	 * @param review one reviewer-authored source review
	 * @throws IOException if a changed path is unreviewed or a placement is malformed
	 */
	private void validateUnitPlacements(BatchManifest manifest, SourceReview review) throws IOException
	{
		Set<String> sourceInventory = manifest.inventory().stream().filter(item -> item.source().equals(review.source())).
			map(InventoryItem::id).collect(Collectors.toSet());
		Set<String> placedInventory = new HashSet<>();
		Set<String> placementKeys = new HashSet<>();
		Set<String> placementDestinations = new HashSet<>();
		for (UnitPlacement placement : review.placements())
		{
			if (placement == null || placement.inventoryId() == null || !sourceInventory.contains(placement.inventoryId()) ||
				placement.affectedUnit() == null || placement.affectedUnit().isBlank() ||
				placement.owningResponsibility() == null || placement.owningResponsibility().isBlank() ||
				placement.destination() == null || !review.destinations().contains(placement.destination()) ||
				placement.evidence() == null || placement.evidence().isBlank())
			{
				throw new IOException("Every source-review placement must name one changed path, affected unit, " +
					"owning responsibility, reviewed destination, and evidence: " + review.source());
			}
			String key = placement.inventoryId() + "\u0000" + placement.affectedUnit() + "\u0000" + placement.destination();
			if (!placementKeys.add(key))
			{
				throw new IOException("A source-review placement must not duplicate an artifact, unit, and destination: " +
					review.source());
			}
			placedInventory.add(placement.inventoryId());
			placementDestinations.add(placement.destination());
		}
		if (!placedInventory.equals(sourceInventory))
		{
			throw new IOException("Source review must explicitly place every changed path before replay; do not assign " +
				"an unreviewed remainder: " + review.source());
		}
		if (!placementDestinations.equals(new HashSet<>(review.destinations())))
		{
			throw new IOException("Source-review placements must account for every declared destination: " + review.source());
		}
	}

	/**
	 * Returns the manifest's discarded outputs, treating an omitted field in an older manifest as empty.
	 *
	 * @param manifest the replay manifest
	 * @return the declared or implied discarded outputs
	 */
	private List<String> discardedOutputs(BatchManifest manifest)
	{
		if (manifest.discardedOutputs() == null)
			return List.of();
		return manifest.discardedOutputs();
	}

	/**
	 * Returns the manifest's source reviews, treating an omitted field in an unfinished manifest as empty.
	 *
	 * @param manifest the replay manifest
	 * @return the declared or implied source reviews
	 */
	private List<SourceReview> sourceReviews(BatchManifest manifest)
	{
		if (manifest.sourceReviews() == null)
			return List.of();
		return manifest.sourceReviews();
	}
}
