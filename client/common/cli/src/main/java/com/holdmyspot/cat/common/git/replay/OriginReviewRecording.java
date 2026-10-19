package com.holdmyspot.cat.common.git.replay;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import tools.jackson.databind.json.JsonMapper;

/**
 * Records reviewer-authored origin decisions against immutable candidates supplied by replay planning.
 */
final class OriginReviewRecording
{
	private final JsonMapper json;
	private final ReviewEvidence evidence;
	private final OriginReviewValidation validation;
	private final SourceReviewValidation sourceReviewValidation;

	/**
	 * Creates the origin-review recorder.
	 *
	 * @param json the process JSON mapper
	 * @param evidence the owner of origin-review evidence files
	 * @param validation the origin-review validator
	 * @param sourceReviewValidation the source-review validator used by origin validation
	 */
	OriginReviewRecording(JsonMapper json, ReviewEvidence evidence, OriginReviewValidation validation,
		SourceReviewValidation sourceReviewValidation)
	{
		requireThat(json, "json").isNotNull();
		requireThat(evidence, "evidence").isNotNull();
		requireThat(validation, "validation").isNotNull();
		requireThat(sourceReviewValidation, "sourceReviewValidation").isNotNull();
		this.json = json;
		this.evidence = evidence;
		this.validation = validation;
		this.sourceReviewValidation = sourceReviewValidation;
	}

	/**
	 * Atomically merges reviewer files after replacing their mutable predecessor facts with generated candidates.
	 *
	 * @param manifestFile the manifest that owns the review evidence
	 * @param manifest the unfinalized manifest
	 * @param candidates the generated immutable origin candidates
	 * @param sourceReviews the recorded source reviews that constrain destinations
	 * @param reviewFiles the non-empty manifest-local reviewer files
	 * @throws IOException if a file, decision, or combined evidence is invalid
	 */
	void record(Path manifestFile, BatchManifest manifest, List<OriginCandidate> candidates,
		List<SourceReview> sourceReviews, List<Path> reviewFiles) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(manifest, "manifest").isNotNull();
		requireThat(candidates, "candidates").isNotNull();
		requireThat(sourceReviews, "sourceReviews").isNotNull();
		requireThat(reviewFiles, "reviewFiles").isNotEmpty();
		Path directory = manifestFile.toAbsolutePath().normalize().getParent();
		List<OriginReview> submitted = new ArrayList<>();
		for (Path reviewFile : reviewFiles)
		{
			requireThat(reviewFile, "reviewFile").isRegularFile();
			if (!reviewFile.toAbsolutePath().normalize().startsWith(directory))
				throw new IOException("Topic-replay origin-review file must be below the manifest directory: " + reviewFile);
			OriginReview[] reviews = json.readValue(reviewFile.toFile(), OriginReview[].class);
			submitted.addAll(List.of(reviews));
		}
		List<OriginReview> combined = new ArrayList<>(evidence.readOriginReviews(manifestFile));
		combined.addAll(hydrate(candidates, submitted));
		validation.validate(manifest, candidates, combined, sourceReviews, false, sourceReviewValidation);
		evidence.writeOriginReviews(manifestFile, combined);
	}

	/**
	 * Replaces reviewer-supplied predecessor facts with the immutable facts captured in origin candidates.
	 *
	 * @param candidates the immutable candidates
	 * @param reviews the reviewer decisions
	 * @return decisions with their generated predecessor facts
	 * @throws IOException if a decision does not name a generated candidate
	 */
	static List<OriginReview> hydrate(List<OriginCandidate> candidates, List<OriginReview> reviews) throws IOException
	{
		Map<String, OriginCandidate> candidatesByInventoryId = candidates.stream().collect(Collectors.toMap(
			OriginCandidate::inventoryId, candidate -> candidate));
		List<OriginReview> result = new ArrayList<>(reviews.size());
		for (OriginReview review : reviews)
		{
			if (review == null || review.inventoryId() == null)
				throw new IOException("Every origin review must name one generated inventory item");
			OriginCandidate candidate = candidatesByInventoryId.get(review.inventoryId());
			if (candidate == null)
				throw new IOException("Origin review does not name a generated candidate: " + review.inventoryId());
			String classification = review.classification();
			if (classification == null)
				classification = "same_unit";
			result.add(new OriginReview(review.inventoryId(), candidate.predecessors(), classification,
				review.affectedUnit(), review.owningResponsibility(), review.predecessorRelationship(), review.destination(),
				review.rejectedDestinations(), review.evidence()));
		}
		return result;
	}
}
