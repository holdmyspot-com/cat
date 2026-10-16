package io.github.cowwoc.cat.common.git.replay;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and validates reviewer-authored evidence artifacts before topic-replay planning or replay accepts them.
 */
public final class ReviewEvidence
{
	private final JsonMapper json;

	/**
	 * Creates a reader for reviewer-authored JSON evidence.
	 *
	 * @param json the JSON mapper used to read review artifacts
	 */
	public ReviewEvidence(JsonMapper json)
	{
		this.json = json;
	}

	/**
	 * Returns the sidecar file containing reviewer-selected origin destinations.
	 *
	 * @param manifestFile the topic-replay manifest
	 * @return the manifest-local origin-review file
	 */
	Path originReviewsFile(Path manifestFile)
	{
		return manifestFile.resolveSibling(manifestFile.getFileName() + ".origin-reviews.json");
	}

	/**
	 * Returns the immutable candidate inventory required before topic outputs can be declared.
	 *
	 * @param manifestFile the topic-replay manifest
	 * @return the manifest-local semantic-placement ledger
	 */
	public Path semanticPlacementLedgerFile(Path manifestFile)
	{
		return manifestFile.resolveSibling(manifestFile.getFileName() + ".semantic-placement-ledger.json");
	}

	/**
	 * Returns the independently reviewed candidate universe.
	 *
	 * @param manifestFile the topic-replay manifest
	 * @return the independent candidate-universe file
	 */
	Path independentCandidateUniverseFile(Path manifestFile)
	{
		return manifestFile.resolveSibling(manifestFile.getFileName() + ".independent-candidate-universe.json");
	}

	/**
	 * Returns the reviewer-authored observable-behavior matrix for one candidate universe.
	 *
	 * @param manifestFile the topic-replay manifest
	 * @param reviewer the candidate-universe owner
	 * @return the matrix file beside the manifest
	 */
	Path actionResultMatrixFile(Path manifestFile, String reviewer)
	{
		return manifestFile.resolveSibling(manifestFile.getFileName() + "." + reviewer +
			"-action-result-matrix.json");
	}

	/**
	 * Returns the reviewer-owned evidence that candidate discovery reached a fixed point.
	 *
	 * @param manifestFile the topic-replay manifest
	 * @param reviewer the candidate-universe owner
	 * @return the discovery-pass file beside the manifest
	 */
	Path candidateDiscoveryPassesFile(Path manifestFile, String reviewer)
	{
		return manifestFile.resolveSibling(manifestFile.getFileName() + "." + reviewer +
			"-candidate-discovery-passes.json");
	}

	/**
	 * Returns the reviewer attestation that binds authorized scope to captured replay facts.
	 *
	 * @param manifestFile the topic-replay manifest
	 * @return the scope-attestation file
	 */
	Path scopeAttestationFile(Path manifestFile)
	{
		return manifestFile.resolveSibling(manifestFile.getFileName() + ".scope-attestation.json");
	}

	/**
	 * Returns the independent-review handoff attestation.
	 *
	 * @param manifestFile the topic-replay manifest
	 * @return the independent-review attestation file
	 */
	Path independentReviewAttestationFile(Path manifestFile)
	{
		return manifestFile.resolveSibling(manifestFile.getFileName() + ".independent-review-attestation.json");
	}

	/**
	 * Returns the candidate plan checked immediately before replay.
	 *
	 * @param manifestFile the topic-replay manifest
	 * @return the manifest-local semantic plan
	 */
	public Path semanticPlanFile(Path manifestFile)
	{
		return manifestFile.resolveSibling(manifestFile.getFileName() + ".semantic-plan.json");
	}

	/**
	 * Returns the reviewer packet required before replay.
	 *
	 * @param manifestFile the topic-replay manifest
	 * @return the manifest-local review packet
	 */
	public Path reviewPacketFile(Path manifestFile)
	{
		return manifestFile.resolveSibling(manifestFile.getFileName() + ".review-packet.md");
	}

	/**
	 * Returns the resolved adversarial challenge ledger required before replay.
	 *
	 * @param manifestFile the topic-replay manifest
	 * @return the manifest-local challenge ledger
	 */
	public Path challengeLedgerFile(Path manifestFile)
	{
		return manifestFile.resolveSibling(manifestFile.getFileName() + ".adversarial-challenges.json");
	}

	/**
	 * Returns the reviewer classifications of raw path transitions.
	 *
	 * @param manifestFile the topic-replay manifest
	 * @return the manifest-local transition-decision file
	 */
	Path pathTransitionDecisionsFile(Path manifestFile)
	{
		return manifestFile.resolveSibling(manifestFile.getFileName() + ".path-transition-decisions.json");
	}

	/**
	 * Reads manifest-local origin reviews, treating an absent file as no reviews.
	 *
	 * @param manifestFile the topic-replay manifest
	 * @return the recorded reviews
	 * @throws IOException if the review file is invalid
	 */
	List<OriginReview> readOriginReviews(Path manifestFile) throws IOException
	{
		Path file = originReviewsFile(manifestFile);
		if (!Files.exists(file))
			return List.of();
		if (!Files.isRegularFile(file))
			throw new IOException("Topic-replay origin-review file is not a regular file: " + file);
		OriginReview[] reviews = json.readValue(file.toFile(), OriginReview[].class);
		return List.of(reviews);
	}

	/**
	 * Writes manifest-local origin reviews using the canonical JSON layout.
	 *
	 * @param manifestFile the topic-replay manifest
	 * @param reviews the validated reviews
	 * @throws IOException if the reviews cannot be written
	 */
	public void writeOriginReviews(Path manifestFile, List<OriginReview> reviews) throws IOException
	{
		json.writerWithDefaultPrettyPrinter().writeValue(originReviewsFile(manifestFile).toFile(), reviews);
	}

	/**
	 * Reads manifest-local path-transition decisions, treating an absent file as no decisions.
	 *
	 * @param manifestFile the topic-replay manifest
	 * @return the recorded decisions
	 * @throws IOException if the decision file is invalid
	 */
	List<PathTransitionDecision> readPathTransitionDecisions(Path manifestFile) throws IOException
	{
		Path file = pathTransitionDecisionsFile(manifestFile);
		if (!Files.exists(file))
			return List.of();
		if (!Files.isRegularFile(file))
			throw new IOException("Topic-replay path-transition decision file is not a regular file: " + file);
		return List.of(json.readValue(file.toFile(), PathTransitionDecision[].class));
	}

	/**
	 * Writes manifest-local path-transition decisions using the canonical JSON layout.
	 *
	 * @param manifestFile the topic-replay manifest
	 * @param decisions the validated decisions
	 * @throws IOException if the decisions cannot be written
	 */
	void writePathTransitionDecisions(Path manifestFile, List<PathTransitionDecision> decisions) throws IOException
	{
		json.writerWithDefaultPrettyPrinter().writeValue(pathTransitionDecisionsFile(manifestFile).toFile(), decisions);
	}

	/**
	 * Validates the reviewer packet and resolved adversarial challenges required before a replay can proceed.
	 *
	 * @param packet the manifest-local review packet
	 * @param ledgerFile the manifest-local adversarial challenge ledger
	 * @throws IOException if the packet is absent or blank, or the ledger is absent, malformed, unresolved, or requests
	 *                     an unsupported operation
	 */
	public void validateReplayReadiness(Path packet, Path ledgerFile) throws IOException
	{
		if (!Files.isRegularFile(packet) || isBlank(packet))
			throw new IOException("Topic-replay preflight requires a non-empty review packet: " + packet);
		if (!Files.isRegularFile(ledgerFile))
			throw new IOException("Topic-replay preflight requires an adversarial challenge ledger: " + ledgerFile);
		boolean containsChallenge = false;
		boolean containsOpenChallenge = false;
		try (JsonParser parser = json.createParser(ledgerFile))
		{
			if (parser.nextToken() != JsonToken.START_ARRAY)
				throw new IOException("Topic-replay adversarial challenge ledger must be a JSON array: " + ledgerFile);
			for (JsonToken token = parser.nextToken(); token != JsonToken.END_ARRAY; token = parser.nextToken())
			{
				if (token != JsonToken.START_OBJECT)
					throw new IOException("Topic-replay adversarial challenge ledger must be a JSON array: " + ledgerFile);
				containsChallenge = true;
				String operation = null;
				String evidence = null;
				for (token = parser.nextToken(); token != JsonToken.END_OBJECT; token = parser.nextToken())
				{
					if (token != JsonToken.PROPERTY_NAME)
						throw new IOException("Topic-replay adversarial challenge ledger must be a JSON array: " + ledgerFile);
					String name = parser.currentName();
					token = parser.nextToken();
					if (token == null)
						throw new IOException("Topic-replay adversarial challenge ledger must be a JSON array: " + ledgerFile);
					if (name.equals("operation") && token == JsonToken.VALUE_STRING)
						operation = parser.getString();
					else if (name.equals("evidence") && token == JsonToken.VALUE_STRING)
						evidence = parser.getString();
					else if (name.equals("status") && token == JsonToken.VALUE_STRING && parser.getString().equals("open"))
						containsOpenChallenge = true;
					else if (!name.equals("operation") && !name.equals("evidence") && !name.equals("status"))
						parser.skipChildren();
				}
				if (!("add".equals(operation) || "split".equals(operation)) || evidence == null || evidence.isBlank())
				{
					throw new IOException("Topic-replay planner challenges may only request an evidence-backed add or split: " +
						ledgerFile);
				}
			}
		}
		if (!containsChallenge || containsOpenChallenge)
			throw new IOException("Topic-replay adversarial challenge ledger must resolve every challenge: " + ledgerFile);
	}

	/**
	 * Verifies the reviewer-recorded whole-range or fixed-prefix authorization against immutable replay facts.
	 *
	 * @param manifestFile the topic-replay manifest
	 * @param manifest immutable replay facts
	 * @throws IOException if the attestation is absent, ambiguous, or disagrees with the captured range
	 */
	void validateScopeAttestation(Path manifestFile, BatchManifest manifest) throws IOException
	{
		Path file = scopeAttestationFile(manifestFile);
		if (!Files.isRegularFile(file))
			throw new IOException("Topic-replay requires a scope attestation before declaring or replaying outputs: " + file);
		ScopeAttestation attestation = json.readValue(file.toFile(), ScopeAttestation.class);
		if (attestation == null || attestation.mode() == null || attestation.base() == null ||
			attestation.tip() == null || attestation.governingRuleRevision() == null ||
			attestation.authorizationEvidence() == null || attestation.base().isBlank() || attestation.tip().isBlank() ||
			attestation.governingRuleRevision().isBlank() || attestation.authorizationEvidence().isBlank() ||
			!attestation.base().equals(manifest.base()) || !attestation.tip().equals(manifest.sources().getLast().hash()))
		{
			throw new IOException("Topic-replay scope attestation must name the captured base, tip, governing rule, and " +
				"authorization evidence: " + file);
		}
		if (attestation.mode().equals("whole_range") && manifest.fixedSources().isEmpty())
			return;
		if (attestation.mode().equals("fixed_prefix") && !manifest.fixedSources().isEmpty() &&
			attestation.fixedPrefixTip() != null && attestation.fixedPrefixTip().equals(manifest.fixedSources().getLast()) &&
			attestation.priorCompletionArtifact() != null && !attestation.priorCompletionArtifact().isBlank())
		{
			return;
		}
		throw new IOException("Topic-replay scope attestation must select whole-range coverage or name the verified " +
			"prefix and its prior completion artifact: " + file);
	}

	/**
	 * Verifies the independent-review handoff binds its reviewer and immutable artifacts to the captured range.
	 *
	 * @param manifestFile the topic-replay manifest
	 * @param manifest immutable replay facts
	 * @param independent the independently authored candidate universe
	 * @param patches manifest-local artifact digest access
	 * @throws IOException if the handoff is absent or invalid
	 */
	void validateIndependentReviewAttestation(Path manifestFile, BatchManifest manifest, CandidateUniverse independent,
		PatchArtifacts patches) throws IOException
	{
		Path file = independentReviewAttestationFile(manifestFile);
		if (!Files.isRegularFile(file))
			throw new IOException("Topic-replay requires an independent-review attestation: " + file);
		IndependentReviewAttestation attestation = json.readValue(file.toFile(), IndependentReviewAttestation.class);
		if (attestation == null || attestation.reviewer() == null || attestation.base() == null ||
			attestation.tip() == null || attestation.workflowRevision() == null || attestation.rawDiffHandoff() == null ||
			attestation.candidateUniverseSha256() == null || attestation.actionResultMatrixSha256() == null ||
			!attestation.reviewer().equals(independent.reviewer()) || !attestation.base().equals(manifest.base()) ||
			!attestation.tip().equals(manifest.sources().getLast().hash()) || attestation.workflowRevision().isBlank() ||
			attestation.rawDiffHandoff().isBlank() || !attestation.candidateUniverseSha256().matches("[0-9a-f]{64}") ||
			!attestation.candidateUniverseSha256().equals(patches.sha256(independentCandidateUniverseFile(manifestFile))) ||
			!attestation.actionResultMatrixSha256().matches("[0-9a-f]{64}") ||
			!attestation.actionResultMatrixSha256().equals(patches.sha256(actionResultMatrixFile(manifestFile,
				"independent"))) || !attestation.unprimed())
		{
			throw new IOException("Topic-replay independent-review attestation must bind the reviewer, captured range, " +
				"workflow revision, raw-diff handoff, immutable independent evidence, and unprimed review: " + file);
		}
	}

	/**
	 * Verifies the frozen review packet and resolved challenge ledger needed before replay.
	 *
	 * @param manifestFile the topic-replay manifest
	 * @throws IOException if either review artifact is absent, empty, or contains an unresolved challenge
	 */
	public void validateReplayReadiness(Path manifestFile) throws IOException
	{
		validateReplayReadiness(reviewPacketFile(manifestFile), challengeLedgerFile(manifestFile));
	}

	/**
	 * Indicates whether a text artifact contains a non-whitespace character without retaining its complete contents.
	 *
	 * @param file the artifact to inspect
	 * @return {@code true} if the file is blank
	 * @throws IOException if the artifact cannot be read
	 */
	private boolean isBlank(Path file) throws IOException
	{
		try (BufferedReader reader = Files.newBufferedReader(file, UTF_8))
		{
			char[] buffer = new char[8192];
			int read = reader.read(buffer);
			while (read != -1)
			{
				for (int index = 0; index < read; ++index)
				{
					if (!Character.isWhitespace(buffer[index]))
						return false;
				}
				read = reader.read(buffer);
			}
			return true;
		}
	}

	/**
	 * Reads one reviewer-authored semantic-candidate artifact.
	 *
	 * @param file the manifest-local artifact
	 * @param artifactName the name used in diagnostics
	 * @return its non-empty candidates
	 * @throws IOException if the artifact is absent or empty
	 */
	public List<SemanticCandidate> readSemanticCandidates(Path file, String artifactName) throws IOException
	{
		if (!Files.isRegularFile(file))
			throw new IOException("Topic-replay requires an auditable " + artifactName + ": " + file);
		SemanticCandidate[] parsed = json.readValue(file.toFile(), SemanticCandidate[].class);
		if (parsed == null || parsed.length == 0)
			throw new IOException("Topic-replay " + artifactName + " must contain at least one behavior candidate: " + file);
		return List.of(parsed);
	}

	/**
	 * Reads one independent reviewer candidate-universe artifact.
	 *
	 * @param file the candidate-universe file
	 * @param role the required reviewer role used in diagnostics
	 * @return the parsed reviewer inventory
	 * @throws IOException if the inventory is absent or malformed
	 */
	CandidateUniverse readCandidateUniverse(Path file, String role) throws IOException
	{
		if (!Files.isRegularFile(file))
			throw new IOException("Topic-replay requires a " + role + " candidate universe: " + file);
		CandidateUniverse universe = json.readValue(file.toFile(), CandidateUniverse.class);
		if (universe == null || universe.candidates() == null || universe.candidates().isEmpty())
			throw new IOException("Topic-replay " + role + " candidate universe must contain behavior candidates: " + file);
		return universe;
	}

	/**
	 * Verifies that each reviewer re-examined the complete candidate universe until a pass found no new candidate.
	 *
	 * @param file the reviewer-authored discovery-pass evidence
	 * @param candidateIds the reviewer's complete candidate universe
	 * @param reviewer the reviewer role used in diagnostics
	 * @throws IOException if a pass omits a candidate or the review lacks a terminal empty pass
	 */
	public void validateCandidateDiscoveryPasses(Path file, Set<String> candidateIds, String reviewer) throws IOException
	{
		if (!Files.isRegularFile(file))
			throw new IOException("Topic-replay requires " + reviewer + " candidate-discovery passes: " + file);
		CandidateDiscoveryPasses evidence = json.readValue(file.toFile(), CandidateDiscoveryPasses.class);
		if (evidence == null || evidence.passes() == null || evidence.passes().size() < 2)
		{
			throw new IOException("Topic-replay " + reviewer + " discovery evidence requires an initial and terminal pass: " +
				file);
		}
		for (int index = 0; index < evidence.passes().size(); ++index)
		{
			CandidateDiscoveryPass pass = evidence.passes().get(index);
			if (pass == null || pass.number() != index + 1 || pass.examinedCandidateIds() == null ||
				pass.newCandidateIds() == null || pass.examinedCandidateIds().stream().anyMatch(
					id -> id == null || id.isBlank()) || pass.newCandidateIds().stream().anyMatch(id ->
					id == null || id.isBlank()))
			{
				throw new IOException("Every " + reviewer + " candidate-discovery pass must have its number and non-blank " +
					"examined and newly found candidates: " + file);
			}
		}
		CandidateDiscoveryPass terminal = evidence.passes().getLast();
		if (!new HashSet<>(terminal.examinedCandidateIds()).equals(candidateIds) ||
			terminal.examinedCandidateIds().size() != candidateIds.size() || !terminal.newCandidateIds().isEmpty())
		{
			throw new IOException("Topic-replay " + reviewer + " discovery evidence must end with a complete pass that " +
				"finds no new candidate: " + file);
		}
	}

	/**
	 * Validates that a reviewer's candidate universe names one observable behavior per candidate and reaches an explicit
	 * pairwise conclusion for every possible combination.
	 *
	 * @param file the reviewer-authored matrix
	 * @param candidateIds the captured candidate identifiers
	 * @param reviewer the reviewer role used in diagnostics
	 * @return the validated matrix
	 * @throws IOException if the matrix omits a candidate or pairwise decision
	 */
	public ActionResultMatrix validateActionResultMatrix(Path file, Set<String> candidateIds, String reviewer)
		throws IOException
	{
		if (!Files.isRegularFile(file))
			throw new IOException("Topic-replay requires a " + reviewer + " action-result matrix: " + file);
		ActionResultMatrix matrix = json.readValue(file.toFile(), ActionResultMatrix.class);
		if (matrix == null || matrix.rows() == null || matrix.pairs() == null)
			throw new IOException("Topic-replay " + reviewer + " action-result matrix must contain rows and pairs: " + file);
		Set<String> rows = new HashSet<>();
		Set<String> behaviors = new HashSet<>();
		for (ActionResultRow row : matrix.rows())
		{
			if (row == null || row.candidateId() == null || !candidateIds.contains(row.candidateId()) ||
				row.observableBehavior() == null || row.observableBehavior().isBlank() ||
				row.observableResult() == null || row.observableResult().isBlank() ||
				row.acceptanceCheck() == null || row.acceptanceCheck().isBlank() ||
				row.requiredArtifacts() == null || row.requiredArtifacts().isEmpty() ||
				row.deferredArtifacts() == null || !rows.add(row.candidateId()) || !behaviors.add(row.observableBehavior()))
			{
				throw new IOException("Every " + reviewer + " action-result row requires one candidate, distinct observable " +
					"behavior, result, acceptance check, required artifacts, and deferred-artifact list: " + file);
			}
		}
		if (!rows.equals(candidateIds))
			throw new IOException("Topic-replay " + reviewer + " action-result matrix must cover every candidate: " + file);
		Set<String> pairs = new HashSet<>();
		for (ActionPairAssessment assessment : matrix.pairs())
		{
			if (assessment == null || assessment.firstCandidateId() == null || assessment.secondCandidateId() == null ||
				!candidateIds.contains(assessment.firstCandidateId()) ||
				!candidateIds.contains(assessment.secondCandidateId()) ||
				assessment.firstCandidateId().equals(assessment.secondCandidateId()) || assessment.disposition() == null ||
				(!assessment.disposition().equals("separate") && !assessment.disposition().equals("combined")) ||
				assessment.removalResult() == null || assessment.removalResult().isBlank() ||
				assessment.splitEnablingArtifacts() == null || assessment.splitEnablingArtifacts().isEmpty() ||
				(assessment.disposition().equals("combined") && (assessment.missingPrerequisite() == null ||
					assessment.missingPrerequisite().isBlank())) ||
				(assessment.disposition().equals("separate") && assessment.missingPrerequisite() != null) ||
				!pairs.add(candidatePairKey(assessment.firstCandidateId(), assessment.secondCandidateId())))
			{
				throw new IOException("Every " + reviewer + " action-result pair requires two distinct candidates, a " +
					"separate-or-combined decision, removal result, split-enabling artifacts, and a missing prerequisite only " +
					"when combined: " + file);
			}
		}
		int expectedPairs = candidateIds.size() * (candidateIds.size() - 1) / 2;
		if (pairs.size() != expectedPairs)
			throw new IOException("Topic-replay " + reviewer + " action-result matrix must assess every candidate pair: " +
				file);
		return matrix;
	}

	/**
	 * Validates one reviewer-owned inventory against immutable changed-artifact facts.
	 *
	 * @param requirements immutable facts the universe must cover
	 * @param universe the reviewer-owned inventory
	 * @param file its source file
	 * @return its stable candidate identifiers
	 * @throws IOException if the inventory omits or ambiguously identifies a behavior candidate
	 */
	public Set<String> validateCandidateUniverse(CandidateUniverseRequirements requirements, CandidateUniverse universe,
		Path file) throws IOException
	{
		Set<String> candidates = new HashSet<>();
		Set<String> coveredInventory = new HashSet<>();
		Map<String, String> entryPointOwners = new HashMap<>();
		for (CandidateUniverseEntry candidate : universe.candidates())
		{
			if (candidate == null || candidate.id() == null || candidate.id().isBlank() ||
				candidate.observableOutcome() == null || candidate.observableOutcome().isBlank() ||
				candidate.discoveryEvidence() == null || candidate.discoveryEvidence().isBlank() ||
				candidate.responsibility() == null || candidate.responsibility().isBlank() ||
				candidate.acceptanceEvidence() == null || candidate.acceptanceEvidence().isBlank() ||
				candidate.splitCounterfactual() == null || candidate.splitCounterfactual().isBlank() ||
				candidate.units() == null || candidate.units().isEmpty() || candidate.exposedEntryPoints() == null ||
				!candidates.add(candidate.id()) ||
				usesSourceBoundaryAsDiscoveryEvidence(candidate.discoveryEvidence(), requirements.sourceHashes()) ||
				candidate.units().stream().anyMatch(unit -> unit == null || unit.inventoryId() == null ||
					!requirements.inventoryIds().contains(unit.inventoryId()) || unit.affectedUnit() == null ||
					unit.affectedUnit().isBlank()))
			{
				throw new IOException("Every candidate-universe entry requires a unique id, observable outcome, " +
					"source-neutral discovery evidence, responsibility, acceptance evidence, split counterfactual, and " +
					"concrete captured units: " + file);
			}
			ReplayIdentifiers.requireSnakeCase("Candidate-universe", candidate.id(), file.toString());
			for (CandidateUniverseUnit unit : candidate.units())
				coveredInventory.add(unit.inventoryId());
			validateRootCandidateEvidence(requirements, candidate, file);
			for (String entryPoint : candidate.exposedEntryPoints())
			{
				if (entryPoint == null || !requirements.exposedEntryPointIds().contains(entryPoint) ||
					entryPointOwners.putIfAbsent(entryPoint, candidate.id()) != null)
				{
					throw new IOException("Every exposed entry point must map exactly once to a candidate: " + file);
				}
			}
		}
		if (!coveredInventory.equals(requirements.inventoryIds()))
			throw new IOException("Candidate universe must cover every captured artifact: " + file);
		if (!entryPointOwners.keySet().equals(requirements.exposedEntryPointIds()))
			throw new IOException("Candidate universe must map every exposed entry point: " + file);
		validateEntryPointFamilies(universe, entryPointOwners, file);
		return candidates;
	}

	/**
	 * Verifies that final semantic candidates preserve reviewer discovery and pairwise placement decisions.
	 *
	 * @param candidates final behavior candidates
	 * @param evidence validated reviewer discovery facts
	 * @param artifactFile the final candidate artifact used in diagnostics
	 * @throws IOException if final placement omits, invents, or collapses reviewer evidence
	 */
	public void validateCandidateUniverseLinks(List<SemanticCandidate> candidates, CandidateUniverseEvidence evidence,
		Path artifactFile) throws IOException
	{
		Set<String> citedReviewer = new HashSet<>();
		for (SemanticCandidate candidate : candidates)
		{
			Set<String> reviewerIds = new HashSet<>();
			for (String id : candidate.candidateUniverseIds())
			{
				if (id == null || id.isBlank())
					throw new IOException("Semantic candidates must cite non-blank reviewer candidate-universe ids: " +
						artifactFile);
				if (evidence.reviewerCandidates().contains(id))
					reviewerIds.add(id);
			}
			if (reviewerIds.isEmpty())
			{
				throw new IOException("Every semantic candidate must cite a source-neutral reviewer discovery record: " +
					artifactFile);
			}
			verifyCandidateGroupIsCombined(reviewerIds, evidence.reviewerMatrix(), "topic reviewer", artifactFile);
			citedReviewer.addAll(reviewerIds);
		}
		if (!citedReviewer.equals(evidence.reviewerCandidates()))
		{
			throw new IOException("Semantic candidates must account for every source-neutral reviewer discovery record: " +
				artifactFile);
		}
	}

	/**
	 * Requires every pair of final candidates sharing an output to record one reason it cannot split.
	 *
	 * @param candidatesById candidates indexed by stable identifier
	 * @param candidatesByOutput candidates indexed by retained output
	 * @param planFile reviewer-authored semantic plan
	 * @throws IOException if a blocker is malformed, duplicated, or missing
	 */
	void validateCombinedCandidateBlockers(Map<String, SemanticCandidate> candidatesById,
		Map<String, List<SemanticCandidate>> candidatesByOutput, Path planFile) throws IOException
	{
		Set<String> blockedPairs = new HashSet<>();
		for (SemanticCandidate candidate : candidatesById.values())
		{
			for (CombinedCandidateBlocker blocker : candidate.combinedCandidateBlockers())
			{
				if (blocker == null)
					throw new IOException("Every combined semantic candidate blocker must name another candidate in the same " +
						"output and its missing condition: " + planFile);
				SemanticCandidate other = candidatesById.get(blocker.candidateId());
				if (other == null || other.id().equals(candidate.id()) || !other.output().equals(candidate.output()) ||
					blocker.reason() == null || blocker.reason().isBlank())
					throw new IOException("Every combined semantic candidate blocker must name another candidate in the same " +
						"output and its missing condition: " + planFile);
				if (!blockedPairs.add(combinedCandidatePair(candidate.id(), other.id())))
					throw new IOException("Semantic candidates must record one combined-output blocker per candidate pair: " +
						planFile);
			}
		}
		for (List<SemanticCandidate> candidates : candidatesByOutput.values())
		{
			for (int first = 0; first < candidates.size(); ++first)
				for (int second = first + 1; second < candidates.size(); ++second)
					if (!blockedPairs.contains(combinedCandidatePair(candidates.get(first).id(), candidates.get(second).id())))
						throw new IOException("Semantic candidates sharing one output require a split blocker: " +
							candidates.get(first).id() + " and " + candidates.get(second).id());
		}
	}

	/**
	 * Creates an order-independent key for one pair of behavior candidates.
	 *
	 * @param firstId the first non-null candidate identifier
	 * @param secondId the second non-null candidate identifier
	 * @return a key that is identical regardless of candidate order
	 */
	private String combinedCandidatePair(String firstId, String secondId)
	{
		if (firstId.compareTo(secondId) < 0)
			return firstId + "\\n" + secondId;
		return secondId + "\\n" + firstId;
	}

	/**
	 * Verifies that each retained output combines only reviewer-approved behavior pairs.
	 *
	 * @param candidatesByOutput candidates indexed by retained output
	 * @param evidence validated reviewer discovery evidence
	 * @param artifactFile the final candidate artifact used in diagnostics
	 * @throws IOException if a combined output contradicts the reviewer pair assessment
	 */
	void validateActionResultPlacement(Map<String, List<SemanticCandidate>> candidatesByOutput,
		CandidateUniverseEvidence evidence, Path artifactFile) throws IOException
	{
		for (List<SemanticCandidate> outputCandidates : candidatesByOutput.values())
		{
			for (int first = 0; first < outputCandidates.size(); ++first)
			{
				for (int second = first + 1; second < outputCandidates.size(); ++second)
				{
					SemanticCandidate firstCandidate = outputCandidates.get(first);
					SemanticCandidate secondCandidate = outputCandidates.get(second);
					String reviewerFirst = reviewerCandidateId(firstCandidate, evidence.reviewerCandidates(),
						"topic reviewer", artifactFile);
					String reviewerSecond = reviewerCandidateId(secondCandidate, evidence.reviewerCandidates(),
						"topic reviewer", artifactFile);
					if (!actionPairDisposition(evidence.reviewerMatrix(), reviewerFirst, reviewerSecond).equals("combined"))
					{
						throw new IOException("Semantic candidates may share one output only when the topic-reviewer " +
							"matrix records the observable-behavior pair as combined: " + firstCandidate.id() + " and " +
							secondCandidate.id() + " in " + artifactFile);
					}
				}
			}
		}
	}

	/**
	 * Verifies a downstream candidate did not collapse a reviewer-approved separate pair.
	 *
	 * @param candidateIds reviewer behaviors retained by one downstream candidate
	 * @param matrix the reviewer's pairwise behavior decisions
	 * @param reviewer reviewer role used in diagnostics
	 * @param artifactFile downstream candidate artifact
	 * @throws IOException if the candidate combines a reviewer-approved separate pair
	 */
	private void verifyCandidateGroupIsCombined(Set<String> candidateIds, ActionResultMatrix matrix,
		String reviewer, Path artifactFile) throws IOException
	{
		List<String> ids = candidateIds.stream().sorted().toList();
		for (int first = 0; first < ids.size(); ++first)
		{
			for (int second = first + 1; second < ids.size(); ++second)
			{
				if (!actionPairDisposition(matrix, ids.get(first), ids.get(second)).equals("combined"))
				{
					throw new IOException("Topic-replay " + reviewer + " candidate decisions may not be collapsed by " +
						"reconciliation or a semantic candidate unless their pair is combined: " + ids.get(first) + " and " +
						ids.get(second) + " in " + artifactFile);
				}
			}
		}
	}

	/**
	 * Returns the one reviewer-owned discovery identifier cited by a final candidate.
	 *
	 * @param candidate final candidate
	 * @param reviewerCandidates candidate identifiers owned by one reviewer
	 * @param reviewer reviewer role used in diagnostics
	 * @param artifactFile final candidate artifact
	 * @return the cited reviewer candidate identifier
	 * @throws IOException if the final candidate cites anything other than one reviewer behavior
	 */
	private String reviewerCandidateId(SemanticCandidate candidate, Set<String> reviewerCandidates, String reviewer,
		Path artifactFile) throws IOException
	{
		List<String> ids = candidate.candidateUniverseIds().stream().filter(reviewerCandidates::contains).toList();
		if (ids.size() != 1)
		{
			throw new IOException("Each semantic candidate must cite exactly one " + reviewer +
				" observable-behavior row: " + artifactFile);
		}
		return ids.getFirst();
	}

	/**
	 * Returns one reviewer's recorded disposition for an observable-behavior pair.
	 *
	 * @param matrix validated reviewer matrix
	 * @param firstCandidateId the first candidate identifier
	 * @param secondCandidateId the second candidate identifier
	 * @return the validated pair disposition
	 */
	private String actionPairDisposition(ActionResultMatrix matrix, String firstCandidateId, String secondCandidateId)
	{
		String pair = candidatePairKey(firstCandidateId, secondCandidateId);
		return matrix.pairs().stream().filter(assessment -> candidatePairKey(assessment.firstCandidateId(),
			assessment.secondCandidateId()).equals(pair)).findFirst().orElseThrow().disposition();
	}

	/**
	 * Rejects a generic bootstrap label for a multi-artifact root candidate.
	 *
	 * @param requirements immutable facts that identify a root replay
	 * @param candidate one reviewer-owned behavior candidate
	 * @param file the reviewed candidate artifact
	 * @throws IOException if a multi-artifact root candidate uses generic bootstrap evidence
	 */
	private void validateRootCandidateEvidence(CandidateUniverseRequirements requirements,
		CandidateUniverseEntry candidate, Path file) throws IOException
	{
		if (!requirements.rootRange() || candidate.units().size() < 2)
			return;
		String outcome = candidate.observableOutcome().toLowerCase(java.util.Locale.ROOT);
		String responsibility = candidate.responsibility().toLowerCase(java.util.Locale.ROOT);
		String counterfactual = candidate.splitCounterfactual().toLowerCase(java.util.Locale.ROOT);
		boolean genericOutcome = outcome.contains("baseline") || outcome.contains("foundational") ||
			(outcome.contains("build") && outcome.contains("configuration"));
		boolean genericResponsibility = responsibility.startsWith("owns the observable contract");
		boolean genericCounterfactual = counterfactual.equals(
			"separate unless an exact prerequisite prevents independent activation.");
		if (genericOutcome || genericResponsibility || genericCounterfactual)
		{
			throw new IOException("A multi-artifact root candidate requires concrete activation and split evidence; " +
				"generic bootstrap labels cannot combine root responsibilities: " + file);
		}
	}

	/**
	 * Requires complete family evidence before one candidate combines selectable entry points.
	 *
	 * @param universe one reviewer's candidate universe
	 * @param entryPointOwners the candidate selected for every exposed entry point
	 * @param file the reviewed evidence file
	 * @throws IOException if a combined entry-point family lacks selection or acceptance evidence
	 */
	private void validateEntryPointFamilies(CandidateUniverse universe, Map<String, String> entryPointOwners,
		Path file) throws IOException
	{
		Map<String, Set<String>> entriesByCandidate = new HashMap<>();
		for (Map.Entry<String, String> entry : entryPointOwners.entrySet())
			entriesByCandidate.computeIfAbsent(entry.getValue(), ignored -> new HashSet<>()).add(entry.getKey());
		Map<String, EntryPointFamily> families = new HashMap<>();
		if (universe.entryPointFamilies() == null)
			throw new IOException("Candidate universe requires an entryPointFamilies list: " + file);
		for (EntryPointFamily family : universe.entryPointFamilies())
		{
			if (family == null || family.candidateId() == null || family.candidateId().isBlank() ||
				family.members() == null || family.members().size() < 2 || family.selectionSurface() == null ||
				family.selectionSurface().isBlank() || family.sharedPurpose() == null || family.sharedPurpose().isBlank() ||
				family.acceptanceCheck() == null || family.acceptanceCheck().isBlank() ||
				families.putIfAbsent(family.candidateId(), family) != null)
			{
				throw new IOException("Every entry-point family requires one candidate, two members, a selection surface, " +
					"shared purpose, and complete-family acceptance check: " + file);
			}
		}
		for (Map.Entry<String, Set<String>> entry : entriesByCandidate.entrySet())
		{
			if (entry.getValue().size() == 1)
				continue;
			EntryPointFamily family = families.remove(entry.getKey());
			if (family == null || new HashSet<>(family.members()).size() != family.members().size() ||
				!new HashSet<>(family.members()).equals(entry.getValue()))
			{
				throw new IOException("A candidate that combines exposed entry points requires complete family evidence: " +
					file);
			}
			Set<String> behaviorFamilies = family.members().stream().map(this::entryPointBehaviorFamily).
				collect(Collectors.toSet());
			if (behaviorFamilies.size() != 1)
				throw new IOException("An entry-point family must contain one selectable behavior family: " + file);
		}
		if (!families.isEmpty())
			throw new IOException("Entry-point family evidence may name only a combined candidate: " + file);
	}

	/**
	 * Returns the one selectable behavior family that an exposed entry point may join.
	 *
	 * @param entryPoint an immutable tool-derived entry-point identifier
	 * @return the identifier of the entry point's selectable behavior family
	 */
	private String entryPointBehaviorFamily(String entryPoint)
	{
		if (!entryPoint.startsWith("agent:"))
			return entryPoint;
		String role = entryPoint.substring("agent:".length());
		for (String tier : List.of("-high", "-medium", "-low"))
		{
			if (role.endsWith(tier))
				return "agent:" + role.substring(0, role.length() - tier.length());
		}
		return entryPoint;
	}

	/**
	 * Indicates whether discovery evidence identifies a source commit instead of a behavior.
	 *
	 * @param evidence reviewer-written discovery evidence
	 * @param sourceHashes immutable source identifiers
	 * @return {@code true} if the evidence is source-bound rather than behavior-first
	 */
	private boolean usesSourceBoundaryAsDiscoveryEvidence(String evidence, List<String> sourceHashes)
	{
		String normalized = evidence.toLowerCase(java.util.Locale.ROOT);
		if (normalized.contains("raw diff for source") || normalized.contains("source responsibility"))
			return true;
		return sourceHashes.stream().anyMatch(normalized::contains);
	}

	/**
	 * Returns an order-independent key for one pair of candidate identifiers.
	 *
	 * @param first the first identifier
	 * @param second the second identifier
	 * @return the stable pair key
	 */
	static String candidatePairKey(String first, String second)
	{
		if (first.compareTo(second) < 0)
			return first + "\u0000" + second;
		return second + "\u0000" + first;
	}
}
