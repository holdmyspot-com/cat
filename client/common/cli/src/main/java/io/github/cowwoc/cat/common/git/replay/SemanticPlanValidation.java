package io.github.cowwoc.cat.common.git.replay;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Validates reviewer-authored behavior candidates that justify replay outputs.
 */
final class SemanticPlanValidation
{
	private final ReviewEvidence reviewEvidence;

	/**
	 * Creates semantic-plan validation using the owner of candidate-evidence checks.
	 *
	 * @param reviewEvidence the reviewer-evidence validation boundary
	 */
	SemanticPlanValidation(ReviewEvidence reviewEvidence)
	{
		this.reviewEvidence = reviewEvidence;
	}

	/**
	 * Validates candidate structure, inventory coverage, output coverage, and reviewed placement agreement.
	 *
	 * @param manifest immutable replay facts
	 * @param candidates reviewer-authored behavior candidates
	 * @param requiredOutputs outputs every candidate set must cover
	 * @param requireReviewedPlacement whether candidate placement must match completed source reviews
	 * @param artifactFile candidate artifact used in diagnostics
	 * @param candidateUniverseEvidence validated discovery facts that the candidates must retain
	 * @throws IOException if the candidates are incomplete or inconsistent
	 */
	void validate(BatchManifest manifest, List<SemanticCandidate> candidates, Set<String> requiredOutputs,
		boolean requireReviewedPlacement, Path artifactFile, CandidateUniverseEvidence candidateUniverseEvidence)
		throws IOException
	{
		Map<String, Output> outputs = outputsById(manifest.outputs());
		Set<String> inventory = manifest.inventory().stream().map(InventoryItem::id).collect(Collectors.toSet());
		Map<String, SourceReview> reviews = sourceReviews(manifest).stream().collect(Collectors.toMap(
			SourceReview::source, Function.identity()));
		Set<String> candidateIds = new HashSet<>();
		Set<String> evidence = new HashSet<>();
		Set<String> assignedInventory = new HashSet<>();
		Set<String> assignedUnits = new HashSet<>();
		Set<String> candidateOutputs = new HashSet<>();
		Map<String, SemanticCandidate> candidatesById = new HashMap<>();
		Map<String, List<SemanticCandidate>> candidatesByOutput = new HashMap<>();
		for (SemanticCandidate candidate : candidates)
		{
			validateCandidateShape(candidate, requiredOutputs, candidateIds, evidence, artifactFile);
			candidateOutputs.add(candidate.output());
			candidatesById.put(candidate.id(), candidate);
			candidatesByOutput.computeIfAbsent(candidate.output(), _ -> new ArrayList<>()).add(candidate);
			validateCandidateUnits(manifest, candidate, inventory, reviews, requireReviewedPlacement, assignedInventory,
				assignedUnits, artifactFile);
			validateRejectedOutputs(manifest, candidate, outputs, requiredOutputs, artifactFile);
		}
		reviewEvidence.validateCombinedCandidateBlockers(candidatesById, candidatesByOutput, artifactFile);
		reviewEvidence.validateCandidateUniverseLinks(candidates, candidateUniverseEvidence, artifactFile);
		reviewEvidence.validateActionResultPlacement(candidatesByOutput, candidateUniverseEvidence, artifactFile);
		if (!assignedInventory.equals(inventory) || !candidateOutputs.equals(requiredOutputs))
		{
			throw new IOException("Semantic candidates must cover every captured artifact and required output exactly " +
				"once: " + artifactFile);
		}
	}

	/**
	 * Validates required fields that make one candidate an executable reviewer decision.
	 *
	 * @param candidate the reviewer-authored candidate
	 * @param requiredOutputs outputs the candidate may justify
	 * @param candidateIds identifiers used by earlier candidates
	 * @param evidence evidence values used by earlier candidates
	 * @param artifactFile candidate artifact used in diagnostics
	 * @throws IOException if the candidate is incomplete or duplicates an identifier or evidence value
	 */
	private void validateCandidateShape(SemanticCandidate candidate, Set<String> requiredOutputs,
		Set<String> candidateIds, Set<String> evidence, Path artifactFile) throws IOException
	{
		if (candidate == null || candidate.id() == null || candidate.id().isBlank() ||
			candidate.observableOutcome() == null || candidate.observableOutcome().isBlank() ||
			candidate.candidateDiscovery() == null || candidate.candidateDiscovery().isBlank() ||
			candidate.splitCounterfactual() == null || candidate.splitCounterfactual().isBlank() ||
			candidate.candidateUniverseIds() == null || candidate.candidateUniverseIds().isEmpty() ||
			candidate.output() == null || !requiredOutputs.contains(candidate.output()) ||
			candidate.units() == null || candidate.units().isEmpty() || candidate.prerequisites() == null ||
			candidate.rejectedEarlierOutputs() == null || candidate.combinedCandidateBlockers() == null ||
			candidate.acceptanceCommand() == null || candidate.acceptanceCommand().isEmpty() ||
			candidate.acceptanceCommand().stream().anyMatch(value -> value == null || value.isBlank()) ||
			OutputGates.isTrivial(candidate.acceptanceCommand()) || candidate.evidence() == null ||
			candidate.evidence().isBlank() ||
			!candidateIds.add(candidate.id()) || !evidence.add(candidate.evidence()))
		{
			throw new IOException("Every semantic candidate requires a unique id and evidence, observable outcome, " +
				"behavior-first discovery basis, split counterfactual, required output, changed artifacts, " +
				"runnable acceptance command, prerequisites, rejected-earlier-output decisions, and combined candidate " +
				"blockers: " + artifactFile);
		}
		ReplayIdentifiers.requireSnakeCase("Semantic candidate", candidate.id(), artifactFile.toString());
	}

	/**
	 * Validates candidate semantic units and their placement against completed source review when required.
	 *
	 * @param manifest immutable replay facts
	 * @param candidate the reviewer-authored candidate
	 * @param inventory captured inventory identifiers
	 * @param reviews source reviews indexed by source commit
	 * @param requireReviewedPlacement whether placement must match source review
	 * @param assignedInventory inventory items assigned by prior candidates
	 * @param assignedUnits semantic units assigned by prior candidates
	 * @param artifactFile candidate artifact used in diagnostics
	 * @throws IOException if a unit is invalid, duplicated, or placed contrary to completed review
	 */
	private void validateCandidateUnits(BatchManifest manifest, SemanticCandidate candidate, Set<String> inventory,
		Map<String, SourceReview> reviews, boolean requireReviewedPlacement, Set<String> assignedInventory,
		Set<String> assignedUnits, Path artifactFile) throws IOException
	{
		for (SemanticUnitReference unit : candidate.units())
		{
			if (unit == null || unit.inventoryId() == null || !inventory.contains(unit.inventoryId()) ||
				unit.affectedUnit() == null || unit.affectedUnit().isBlank() || !assignedUnits.add(semanticUnitKey(
				unit.inventoryId(), unit.affectedUnit())))
			{
				throw new IOException("Semantic candidates must assign every declared semantic unit exactly once: " +
					artifactFile);
			}
			assignedInventory.add(unit.inventoryId());
			InventoryItem item = manifest.inventory().stream().filter(value -> value.id().equals(unit.inventoryId())).
				findFirst().orElseThrow();
			SourceReview review = reviews.get(item.source());
			if (requireReviewedPlacement && (review == null || !reviewContainsUnitAtOutput(review, unit,
				candidate.output())))
			{
				throw new IOException("Semantic candidate " + candidate.id() + " assigns " + unit.inventoryId() + "/" +
					unit.affectedUnit() + " to " + candidate.output() +
					" without a matching reviewed hunk placement");
			}
		}
	}

	/**
	 * Requires a candidate to explicitly reject every earlier output that could otherwise contain it.
	 *
	 * @param manifest immutable replay facts
	 * @param candidate the reviewer-authored candidate
	 * @param outputs outputs indexed by identifier
	 * @param requiredOutputs outputs every candidate set must cover
	 * @param artifactFile candidate artifact used in diagnostics
	 * @throws IOException if a rejection is invalid or an earlier output is unreviewed
	 */
	private void validateRejectedOutputs(BatchManifest manifest, SemanticCandidate candidate,
		Map<String, Output> outputs, Set<String> requiredOutputs, Path artifactFile) throws IOException
	{
		Set<String> rejectedOutputs = new HashSet<>();
		for (RejectedEarlierOutput rejection : candidate.rejectedEarlierOutputs())
		{
			if (rejection == null || rejection.output() == null || !outputs.containsKey(rejection.output()) ||
				outputIndex(manifest.outputs(), rejection.output()) >= outputIndex(manifest.outputs(), candidate.output()) ||
				rejection.reason() == null || rejection.reason().isBlank() || !rejectedOutputs.add(rejection.output()))
			{
				throw new IOException("Every rejected semantic-plan output must be earlier and name its missing condition: " +
					artifactFile);
			}
		}
		for (Output output : manifest.outputs())
		{
			if (requiredOutputs.contains(output.id()) && outputIndex(manifest.outputs(), output.id()) <
				outputIndex(manifest.outputs(), candidate.output()) && !rejectedOutputs.contains(output.id()))
			{
				throw new IOException("Semantic candidate " + candidate.id() +
					" must reject every earlier retained output: " + output.id());
			}
		}
	}

	/**
	 * Indexes declared outputs by their stable identifiers.
	 *
	 * @param outputs declared outputs
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

	/**
	 * Creates a stable key for one semantic unit in one immutable changed path.
	 *
	 * @param inventoryId the immutable changed-path identifier
	 * @param affectedUnit the reviewer-named semantic unit
	 * @return a manifest-local semantic-unit key
	 */
	private String semanticUnitKey(String inventoryId, String affectedUnit)
	{
		return inventoryId + "\u0000" + affectedUnit;
	}

	/**
	 * Indicates whether a completed source review places one semantic unit at an output.
	 *
	 * @param review the source review
	 * @param unit the reviewer-declared unit
	 * @param output the candidate output
	 * @return {@code true} if the review places the unit at the output
	 */
	private boolean reviewContainsUnitAtOutput(SourceReview review, SemanticUnitReference unit, String output)
	{
		if (review.disposition().equals("whole"))
			return review.destinations().getFirst().equals(output);
		if (review.disposition().equals("absorbed"))
			return review.destinations().contains(output);
		return review.placements().stream().anyMatch(placement -> placement.inventoryId().equals(unit.inventoryId()) &&
			placement.affectedUnit().equals(unit.affectedUnit()) && placement.destination().equals(output));
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
}
