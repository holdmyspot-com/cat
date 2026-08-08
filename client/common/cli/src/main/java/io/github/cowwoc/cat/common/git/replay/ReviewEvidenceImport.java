package io.github.cowwoc.cat.common.git.replay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Imports compatible source, origin, and path-transition review evidence into a bootstrapped replacement manifest.
 */
final class ReviewEvidenceImport
{
	private final ManifestStore<BatchManifest> manifests;
	private final ReviewEvidence reviewEvidence;
	private final SourceReviewValidation sourceReviewValidation;
	private final OriginCandidates originCandidates;
	private final OriginReviewValidation originReviewValidation;
	private final PathTransitionFacts pathTransitionFacts;
	private final PathTransitionValidation pathTransitionValidation;

	/**
	 * Creates a review-evidence import boundary.
	 *
	 * @param manifests the owner of replacement-manifest publication
	 * @param reviewEvidence the owner of review evidence artifacts
	 * @param sourceReviewValidation the owner of source-review validation
	 * @param originCandidates the owner of origin-candidate discovery
	 * @param originReviewValidation the owner of origin-review validation
	 * @param pathTransitionFacts the owner of transition facts
	 * @param pathTransitionValidation the owner of transition-decision validation
	 */
	ReviewEvidenceImport(ManifestStore<BatchManifest> manifests, ReviewEvidence reviewEvidence,
		SourceReviewValidation sourceReviewValidation, OriginCandidates originCandidates,
		OriginReviewValidation originReviewValidation, PathTransitionFacts pathTransitionFacts,
		PathTransitionValidation pathTransitionValidation)
	{
		this.manifests = manifests;
		this.reviewEvidence = reviewEvidence;
		this.sourceReviewValidation = sourceReviewValidation;
		this.originCandidates = originCandidates;
		this.originReviewValidation = originReviewValidation;
		this.pathTransitionFacts = pathTransitionFacts;
		this.pathTransitionValidation = pathTransitionValidation;
	}

	/**
	 * Imports compatible prior review evidence, excluding every decision derived from reopened sources.
	 *
	 * @param previousManifestFile the manifest that owns prior evidence
	 * @param previous the prior replay state
	 * @param replacementManifestFile the bootstrapped replacement manifest
	 * @param replacement the replacement replay state
	 * @param excludedSources prior sources that require new review
	 * @throws IOException if source transitions or review evidence cannot be safely reused
	 */
	void importEvidence(Path previousManifestFile, BatchManifest previous, Path replacementManifestFile,
		BatchManifest replacement, Set<String> excludedSources) throws IOException
	{
		if (replacement.sources().size() < previous.sources().size())
			throw new IOException("Topic-replay replacement manifest has fewer sources than the prior review evidence");
		if (replacement.sourceReconstructions().size() != replacement.sources().size())
		{
			throw new IOException("Topic-replay review-evidence import requires bootstrap-preserved-output-batches to " +
				"prepare every replacement source");
		}
		if (!replacement.batches().isEmpty() || !replacement.outputWitnesses().isEmpty() ||
			!replacement.discardedOutputs().isEmpty() || !sourceReviews(replacement).isEmpty() ||
			Files.exists(reviewEvidence.originReviewsFile(replacementManifestFile)) ||
			Files.exists(reviewEvidence.pathTransitionDecisionsFile(replacementManifestFile)))
		{
			throw new IOException("Topic-replay review-evidence import requires a bootstrapped manifest without recorded " +
				"review decisions or sealed outputs");
		}
		Map<String, String> sources = ReviewEvidenceTransfer.sourceTransitionMap(previous.sources(),
			replacement.sources(), excludedSources);
		Set<String> previousSourceHashes = previous.sources().stream().map(SourceSnapshot::hash).collect(
			Collectors.toSet());
		if (!previousSourceHashes.containsAll(excludedSources))
		{
			throw new IOException("Topic-replay review-evidence exclusions must name captured sources in the prior " +
				"manifest: " + excludedSources);
		}
		List<SourceReview> sourceReviews = new ArrayList<>();
		for (SourceReview review : sourceReviews(previous))
		{
			if (!excludedSources.contains(review.source()))
			{
				sourceReviews.add(new SourceReview(ReviewEvidenceTransfer.requiredMappedSource(sources, review.source(),
					"source review"), review.disposition(), review.destinations(),
					ReviewEvidenceTransfer.mapUnitPlacements(sources, review.placements()), review.counterfactual(),
					review.evidence()));
			}
		}
		sourceReviewValidation.validateInput(replacement, sourceReviews, false);
		List<OriginReview> originReviews = mapOriginReviews(previousManifestFile, replacement, sources, excludedSources);
		List<PathTransitionDecision> decisions = mapPathTransitionDecisions(previousManifestFile, replacement, sources,
			excludedSources);
		manifests.write(replacementManifestFile, replacement.withSourceReviews(sourceReviews));
		if (!originReviews.isEmpty())
			reviewEvidence.writeOriginReviews(replacementManifestFile, originReviews);
		if (!decisions.isEmpty())
			reviewEvidence.writePathTransitionDecisions(replacementManifestFile, decisions);
		System.out.println("importedSourceReviews=" + sourceReviews.size());
		System.out.println("importedOriginReviews=" + originReviews.size());
		System.out.println("importedPathTransitionDecisions=" + decisions.size());
	}

	/**
	 * Maps and validates origin reviews that remain independent of every excluded source.
	 *
	 * @param previousManifestFile the manifest that owns the prior origin reviews
	 * @param replacement the replacement manifest that receives mapped reviews
	 * @param sources the verified old-to-new source map
	 * @param excludedSources prior sources whose dependent reviews are omitted
	 * @return validated replacement origin reviews
	 * @throws IOException if a mapped review contradicts replacement facts
	 */
	private List<OriginReview> mapOriginReviews(Path previousManifestFile, BatchManifest replacement,
		Map<String, String> sources, Set<String> excludedSources) throws IOException
	{
		List<OriginReview> previous = reviewEvidence.readOriginReviews(previousManifestFile);
		if (previous.isEmpty())
			return List.of();
		List<OriginReview> mapped = new ArrayList<>();
		for (OriginReview review : previous)
		{
			if (!ReviewEvidenceTransfer.isExcludedSourceScopedId(review.inventoryId(), excludedSources) &&
				!review.predecessors().stream().anyMatch(excludedSources::contains))
			{
				mapped.add(new OriginReview(ReviewEvidenceTransfer.mapSourceScopedId(sources, review.inventoryId(),
					"origin review"), review.predecessors(), review.classification(), review.affectedUnit(),
					review.owningResponsibility(), review.predecessorRelationship(), review.destination(),
					review.rejectedDestinations(), review.evidence()));
			}
		}
		List<OriginReview> hydrated = OriginReviewRecording.hydrate(originCandidates(replacement), mapped);
		originReviewValidation.validate(replacement, originCandidates(replacement), hydrated, sourceReviews(replacement),
			false, sourceReviewValidation);
		return hydrated;
	}

	/**
	 * Maps and validates path-transition decisions that remain independent of excluded sources.
	 *
	 * @param previousManifestFile the manifest that owns the prior transition decisions
	 * @param replacement the replacement manifest that receives mapped decisions
	 * @param sources the verified old-to-new source map
	 * @param excludedSources prior sources whose dependent decisions are omitted
	 * @return validated replacement transition decisions
	 * @throws IOException if a mapped decision contradicts replacement facts
	 */
	private List<PathTransitionDecision> mapPathTransitionDecisions(Path previousManifestFile,
		BatchManifest replacement, Map<String, String> sources, Set<String> excludedSources) throws IOException
	{
		List<PathTransitionDecision> previous = reviewEvidence.readPathTransitionDecisions(previousManifestFile);
		if (previous.isEmpty())
			return List.of();
		List<PathTransitionDecision> mapped = new ArrayList<>();
		for (PathTransitionDecision decision : previous)
		{
			if (ReviewEvidenceTransfer.isExcludedSourceScopedId(decision.id(), excludedSources) ||
				decision.relatedInventoryId() != null && ReviewEvidenceTransfer.isExcludedSourceScopedId(
					decision.relatedInventoryId(), excludedSources))
			{
				continue;
			}
			String related = null;
			if (decision.relatedInventoryId() != null)
			{
				related = ReviewEvidenceTransfer.mapSourceScopedId(sources, decision.relatedInventoryId(),
					"path-transition decision");
			}
			mapped.add(new PathTransitionDecision(ReviewEvidenceTransfer.mapSourceScopedId(sources, decision.id(),
				"path-transition decision"), decision.classification(), related, decision.evidence()));
		}
		pathTransitionValidation.validate(replacement, pathTransitionFacts.read(replacement), mapped, false,
			sourceReviewValidation);
		return mapped;
	}

	/**
	 * Derives exact-path predecessor candidates for a replacement manifest.
	 *
	 * @param manifest immutable replacement facts
	 * @return origin candidates in source and inventory order
	 * @throws IOException if the fixed source prefix is invalid
	 */
	private List<OriginCandidate> originCandidates(BatchManifest manifest) throws IOException
	{
		Set<String> capturedSources = manifest.sources().stream().map(SourceSnapshot::hash).collect(Collectors.toSet());
		Set<String> sourcesToReview = sourceReviewValidation.sourcesRequiringReview(manifest, capturedSources);
		List<String> sources = manifest.sources().stream().map(SourceSnapshot::hash).toList();
		List<History.InventoryEntry> inventory = manifest.inventory().stream().map(item -> new History.InventoryEntry(
			item.id(), item.source(), item.path(), item.beforeMode(), item.beforeBlob(), item.afterMode(), item.afterBlob())).
			toList();
		return originCandidates.select(sources, inventory, sourcesToReview).stream().map(candidate -> new OriginCandidate(
			candidate.inventoryId(), candidate.path(), candidate.predecessors())).toList();
	}

	/**
	 * Returns source reviews, treating an omitted unfinished-manifest field as empty.
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
