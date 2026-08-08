package io.github.cowwoc.cat.common.git.replay;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import tools.jackson.databind.json.JsonMapper;

/**
 * Writes reader-facing origin-review reports from immutable facts and recorded decisions.
 */
final class OriginReviewReports
{
	private final JsonMapper json;
	private final OriginReviewFacts originReviewFacts;
	private final SourceReviewValidation sourceReviewValidation;
	private final ReviewEvidence reviewEvidence;

	/**
	 * Creates an origin-review report writer.
	 *
	 * @param json the JSON output mapper
	 * @param originReviewFacts the origin-review fact owner
	 * @param sourceReviewValidation the source-review validator
	 * @param reviewEvidence the recorded origin-review evidence reader
	 */
	OriginReviewReports(JsonMapper json, OriginReviewFacts originReviewFacts,
		SourceReviewValidation sourceReviewValidation, ReviewEvidence reviewEvidence)
	{
		this.json = json;
		this.originReviewFacts = originReviewFacts;
		this.sourceReviewValidation = sourceReviewValidation;
		this.reviewEvidence = reviewEvidence;
	}

	/**
	 * Writes immutable predecessor facts for origin-placement decisions.
	 *
	 * @param manifest the replay manifest
	 * @param output the destination for the JSON candidate list
	 * @throws IOException if the candidates cannot be derived or written
	 */
	void writeCandidates(BatchManifest manifest, OutputStream output) throws IOException
	{
		json.writerWithDefaultPrettyPrinter().writeValue(output, originReviewFacts.candidates(manifest));
	}

	/**
	 * Writes predecessor facts with each predecessor's unambiguous logical output, when one exists.
	 *
	 * @param manifest the replay manifest
	 * @param output the destination for the JSON scaffold
	 * @throws IOException if the scaffold cannot be derived or written
	 */
	void writeScaffold(BatchManifest manifest, OutputStream output) throws IOException
	{
		List<OriginReviewScaffold> scaffold = originReviewFacts.candidates(manifest).stream().map(candidate ->
			new OriginReviewScaffold(candidate.inventoryId(), candidate.path(), candidate.predecessors().stream().map(
				predecessor -> new OriginReviewCandidate(predecessor, sourceReviewValidation.sourceOutput(manifest,
					predecessor))).toList())).toList();
		json.writerWithDefaultPrettyPrinter().writeValue(output, scaffold);
	}

	/**
	 * Writes every recorded origin decision beside the immutable fact it resolves.
	 *
	 * @param manifestFile the manifest that owns recorded origin reviews
	 * @param manifest the replay manifest
	 * @param output the destination for the JSON audit
	 * @throws IOException if a review is invalid or the audit cannot be written
	 */
	void writeAudit(Path manifestFile, BatchManifest manifest, OutputStream output) throws IOException
	{
		List<OriginReview> reviews = reviewEvidence.readOriginReviews(manifestFile);
		originReviewFacts.validate(manifest, reviews, false);
		Map<String, OriginReview> reviewsByItem = reviews.stream().collect(Collectors.toMap(OriginReview::inventoryId,
			review -> review));
		List<OriginReviewAudit> audit = originReviewFacts.candidates(manifest).stream().map(candidate ->
		{
			OriginReview review = reviewsByItem.get(candidate.inventoryId());
			if (review == null)
				return new OriginReviewAudit(candidate.inventoryId(), candidate.path(), "unreviewed", null, null);
			return new OriginReviewAudit(candidate.inventoryId(), candidate.path(), "reviewed", review.classification(),
				review.destination());
		}).toList();
		json.writerWithDefaultPrettyPrinter().writeValue(output, audit);
	}
}
