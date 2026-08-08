package io.github.cowwoc.cat.common.git.replay;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates the candidate-universe and semantic-placement evidence that justifies replay outputs.
 */
final class SemanticEvidence
{
	private final History history;
	private final PatchArtifacts patches;
	private final ReviewEvidence reviewEvidence;
	private final SemanticPlanValidation semanticPlanValidation;

	/**
	 * Creates a semantic-evidence validation boundary.
	 *
	 * @param history the owner of immutable Git inventory facts
	 * @param patches the owner of manifest-local patch artifacts
	 * @param reviewEvidence the owner of reviewer-authored evidence artifacts
	 * @param semanticPlanValidation the owner of candidate placement validation
	 */
	SemanticEvidence(History history, PatchArtifacts patches, ReviewEvidence reviewEvidence,
		SemanticPlanValidation semanticPlanValidation)
	{
		this.history = history;
		this.patches = patches;
		this.reviewEvidence = reviewEvidence;
		this.semanticPlanValidation = semanticPlanValidation;
	}

	/**
	 * Validates the semantic plan and requires it to reproduce the declared placement ledger.
	 *
	 * @param manifestFile the manifest that owns semantic evidence
	 * @param manifest the sealed or projected replay state
	 * @param candidateUniverseEvidence validated discovery facts that the final plan must retain
	 * @throws IOException if the plan is absent, incomplete, or disagrees with the ledger
	 */
	void validatePlan(Path manifestFile, BatchManifest manifest, CandidateUniverseEvidence candidateUniverseEvidence)
		throws IOException
	{
		Path planFile = reviewEvidence.semanticPlanFile(manifestFile);
		List<SemanticCandidate> candidates = reviewEvidence.readSemanticCandidates(planFile, "semantic plan");
		Set<String> retainedOutputs = manifest.outputWitnesses().stream().map(OutputWitness::outputId).collect(
			Collectors.toSet());
		validateCandidates(manifest, candidates, retainedOutputs, true, planFile, candidateUniverseEvidence);
		List<SemanticCandidate> ledger = validatePlacementLedger(manifestFile, manifest, retainedOutputs,
			candidateUniverseEvidence);
		if (!candidatesById(candidates).equals(candidatesById(ledger)))
		{
			throw new IOException("Topic-replay semantic plan must exactly reproduce the declared " +
				"semantic-placement ledger: " + planFile);
		}
	}

	/**
	 * Validates the manifest-local candidate inventory that must exist before outputs are declared.
	 *
	 * @param manifestFile the manifest that owns the ledger
	 * @param manifest the replay state whose inventory and output order govern the ledger
	 * @param requiredOutputs every output that must be justified by a candidate
	 * @param candidateUniverseEvidence validated discovery facts that the ledger must retain
	 * @return the validated ledger candidates
	 * @throws IOException if the ledger is absent, incomplete, or does not cover the captured inventory
	 */
	List<SemanticCandidate> validatePlacementLedger(Path manifestFile, BatchManifest manifest,
		Set<String> requiredOutputs, CandidateUniverseEvidence candidateUniverseEvidence) throws IOException
	{
		Path ledgerFile = reviewEvidence.semanticPlacementLedgerFile(manifestFile);
		List<SemanticCandidate> candidates = reviewEvidence.readSemanticCandidates(ledgerFile,
			"semantic-placement ledger");
		validateCandidates(manifest, candidates, requiredOutputs, false, ledgerFile, candidateUniverseEvidence);
		return candidates;
	}

	/**
	 * Validates candidate structure, coverage, and reviewed placement agreement.
	 *
	 * @param manifest immutable replay facts
	 * @param candidates reviewer-authored behavior candidates
	 * @param requiredOutputs outputs every candidate set must cover
	 * @param requireReviewedPlacement whether candidate placement must match completed source reviews
	 * @param artifactFile candidate artifact used in diagnostics
	 * @param candidateUniverseEvidence validated discovery facts that the candidates must retain
	 * @throws IOException if the candidates are incomplete or inconsistent
	 */
	void validateCandidates(BatchManifest manifest, List<SemanticCandidate> candidates, Set<String> requiredOutputs,
		boolean requireReviewedPlacement, Path artifactFile, CandidateUniverseEvidence candidateUniverseEvidence)
		throws IOException
	{
		semanticPlanValidation.validate(manifest, candidates, requiredOutputs, requireReviewedPlacement, artifactFile,
			candidateUniverseEvidence);
	}

	/**
	 * Requires one attested, source-neutral reviewer candidate universe.
	 *
	 * @param manifestFile the manifest that owns the candidate evidence
	 * @param manifest immutable replay facts
	 * @return the discovery identifiers the final plan must retain
	 * @throws IOException if reviewer evidence is absent, incomplete, or invalid
	 */
	CandidateUniverseEvidence verifyCandidateUniverse(Path manifestFile, BatchManifest manifest) throws IOException
	{
		CandidateUniverse reviewer = reviewEvidence.readCandidateUniverse(
			reviewEvidence.independentCandidateUniverseFile(manifestFile), "topic reviewer");
		reviewEvidence.validateIndependentReviewAttestation(manifestFile, manifest, reviewer, patches);
		if (reviewer.reviewer() == null || reviewer.reviewer().isBlank())
			throw new IOException("Topic-replay topic-reviewer candidate universe requires a non-blank reviewer identity");
		Set<String> exposedEntryPoints = exposedEntryPoints(manifest).stream().map(ExposedEntryPoint::id).collect(
			Collectors.toSet());
		CandidateUniverseRequirements requirements = new CandidateUniverseRequirements(
			manifest.inventory().stream().map(InventoryItem::id).collect(Collectors.toSet()),
			manifest.sources().stream().map(SourceSnapshot::hash).toList(), exposedEntryPoints,
			manifest.base().equals(History.ROOT_BASE));
		Set<String> reviewerCandidates = reviewEvidence.validateCandidateUniverse(requirements, reviewer,
			reviewEvidence.independentCandidateUniverseFile(manifestFile));
		ActionResultMatrix reviewerMatrix = reviewEvidence.validateActionResultMatrix(
			reviewEvidence.actionResultMatrixFile(manifestFile, "independent"), reviewerCandidates, "topic reviewer");
		Path discoveryPasses = reviewEvidence.candidateDiscoveryPassesFile(manifestFile, "independent");
		reviewEvidence.validateCandidateDiscoveryPasses(discoveryPasses, reviewerCandidates, "topic reviewer");
		return new CandidateUniverseEvidence(reviewerCandidates, reviewerMatrix);
	}

	/**
	 * Derives the selectable product entry points changed within the captured range.
	 *
	 * @param manifest immutable replay facts
	 * @return one harness-neutral entry point for each affected product surface
	 * @throws IOException if a captured blob cannot be read
	 */
	List<ExposedEntryPoint> exposedEntryPoints(BatchManifest manifest) throws IOException
	{
		List<History.InventoryEntry> inventory = manifest.inventory().stream().map(item -> new History.InventoryEntry(
			item.id(), item.source(), item.path(), item.beforeMode(), item.beforeBlob(), item.afterMode(), item.afterBlob())).
			toList();
		return history.exposedEntryPoints(inventory);
	}

	/**
	 * Indexes candidates by their stable identifiers for exact sidecar comparison.
	 *
	 * @param candidates candidates that have already passed uniqueness validation
	 * @return the candidates indexed by identifier
	 */
	private Map<String, SemanticCandidate> candidatesById(List<SemanticCandidate> candidates)
	{
		Map<String, SemanticCandidate> result = new HashMap<>();
		for (SemanticCandidate candidate : candidates)
			result.put(candidate.id(), candidate);
		return result;
	}
}
