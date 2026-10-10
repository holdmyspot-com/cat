package io.github.cowwoc.cat.common.git.replay;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Validates reviewer decisions for raw deletions and Git-reported path renames.
 */
public final class PathTransitionValidation
{
	/**
	 * Validates complete or incremental path-transition decisions against the captured raw Git facts.
	 *
	 * @param manifest the replay manifest
	 * @param facts raw Git transition facts for every captured source
	 * @param decisions reviewer decisions for deletions and renames
	 * @param requireCompleteCoverage whether the decisions must cover every reviewable transition fact
	 * @param sourceReviewValidation source-review policy used to exclude the fixed prefix
	 * @throws IOException if a decision is invalid or the requested coverage is incomplete
	 */
	public void validate(BatchManifest manifest, List<PathTransitionFact> facts, List<PathTransitionDecision> decisions,
		boolean requireCompleteCoverage, SourceReviewValidation sourceReviewValidation) throws IOException
	{
		Set<String> sources = manifest.sources().stream().map(SourceSnapshot::hash).collect(Collectors.toSet());
		Set<String> sourcesToReview = sourceReviewValidation.sourcesRequiringReview(manifest, sources);
		Map<String, PathTransitionFact> factsById = facts.stream().filter(fact ->
			sourcesToReview.contains(fact.source()) && (fact.status().equals("D") || fact.status().startsWith("R"))).
			collect(Collectors.toMap(PathTransitionFact::id, Function.identity()));
		if (decisions.size() > factsById.size() || requireCompleteCoverage && decisions.size() != factsById.size())
		{
			throw new IOException("Path-transition decisions must cover every raw deletion and Git-reported rename");
		}
		Set<String> reviewed = new HashSet<>();
		Set<String> inventoryIds = manifest.inventory().stream().map(InventoryItem::id).collect(Collectors.toSet());
		for (PathTransitionDecision decision : decisions)
		{
			if (decision == null || decision.id() == null || !factsById.containsKey(decision.id()) ||
				!reviewed.add(decision.id()) || decision.classification() == null || decision.evidence() == null ||
				decision.evidence().isBlank())
			{
				throw new IOException("Every path-transition decision requires one fact, classification, and evidence");
			}
			if (decision.classification().equals("independent_removal") && decision.relatedInventoryId() == null)
				continue;
			if (decision.classification().equals("related_transition") && decision.relatedInventoryId() != null &&
				inventoryIds.contains(decision.relatedInventoryId()))
			{
				continue;
			}
			throw new IOException("A path-transition decision must be an independent removal or link a related inventory " +
				"item");
		}
	}
}
