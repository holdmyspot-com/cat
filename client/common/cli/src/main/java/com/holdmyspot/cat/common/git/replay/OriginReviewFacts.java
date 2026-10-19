package com.holdmyspot.cat.common.git.replay;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Derives and validates the immutable facts that scope origin-review decisions.
 */
final class OriginReviewFacts
{
	private final OriginCandidates originCandidates;
	private final OriginReviewValidation originReviewValidation;
	private final SourceReviewValidation sourceReviewValidation;

	/**
	 * Creates an origin-review fact owner.
	 *
	 * @param originCandidates the exact-path predecessor selector
	 * @param originReviewValidation the origin-review validator
	 * @param sourceReviewValidation the source-review validator
	 */
	OriginReviewFacts(OriginCandidates originCandidates, OriginReviewValidation originReviewValidation,
		SourceReviewValidation sourceReviewValidation)
	{
		this.originCandidates = originCandidates;
		this.originReviewValidation = originReviewValidation;
		this.sourceReviewValidation = sourceReviewValidation;
	}

	/**
	 * Returns exact-path predecessors for changed paths that existed earlier in the selected replay range.
	 *
	 * @param manifest the replay manifest
	 * @return origin candidates in source and inventory order
	 * @throws IOException if the manifest's deterministic origin facts cannot be selected
	 */
	List<OriginCandidate> candidates(BatchManifest manifest) throws IOException
	{
		Set<String> capturedSourceHashes = manifest.sources().stream().map(SourceSnapshot::hash).
			collect(Collectors.toSet());
		Set<String> sourcesToReview = sourceReviewValidation.sourcesRequiringReview(manifest, capturedSourceHashes);
		List<String> sources = manifest.sources().stream().map(SourceSnapshot::hash).toList();
		List<History.InventoryEntry> inventory = manifest.inventory().stream().map(item ->
			new History.InventoryEntry(item.id(), item.source(), item.path(), item.beforeMode(),
				item.beforeBlob(), item.afterMode(), item.afterBlob())).toList();
		return originCandidates.select(sources, inventory, sourcesToReview).stream().map(candidate ->
			new OriginCandidate(candidate.inventoryId(), candidate.path(), candidate.predecessors())).toList();
	}

	/**
	 * Verifies required origin reviews without inferring semantic equivalence or destination placement.
	 *
	 * @param manifest the replay manifest
	 * @param reviews the reviewer-authored origin reviews
	 * @param requireRecordedReviews whether missing required reviews fail validation
	 * @throws IOException if a required review is missing or contradicts deterministic Git facts
	 */
	void validate(BatchManifest manifest, List<OriginReview> reviews, boolean requireRecordedReviews)
		throws IOException
	{
		originReviewValidation.validate(manifest, candidates(manifest), reviews, sourceReviews(manifest),
			requireRecordedReviews, sourceReviewValidation);
	}

	/**
	 * Returns the manifest's source reviews, treating an omitted field in an unfinished manifest as empty.
	 *
	 * @param manifest the replay manifest
	 * @return the declared or implied source reviews
	 */
	List<SourceReview> sourceReviews(BatchManifest manifest)
	{
		if (manifest.sourceReviews() == null)
			return List.of();
		return manifest.sourceReviews();
	}
}
