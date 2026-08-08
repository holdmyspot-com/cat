package io.github.cowwoc.cat.common.git.replay;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Verifies that a frozen replay can reproduce its sources, semantic evidence, transitions, and final tree.
 */
final class ReplayPreflight
{
	private final History history;
	private final PatchApplication patchApplication;
	private final ReviewEvidence reviewEvidence;
	private final SemanticEvidence semanticEvidence;
	private final PathTransitionFacts pathTransitionFacts;
	private final PathTransitionValidation pathTransitionValidation;
	private final SourceReviewValidation sourceReviewValidation;
	private final JsonMapper json;

	/**
	 * Creates a replay-preflight boundary.
	 *
	 * @param history the owner of planning repository state
	 * @param patchApplication the owner of reconstruction verification
	 * @param reviewEvidence the owner of replay-readiness evidence
	 * @param semanticEvidence the owner of candidate and placement validation
	 * @param pathTransitionFacts the owner of recorded transition facts
	 * @param pathTransitionValidation the owner of transition-decision validation
	 * @param sourceReviewValidation the owner of source-review policy
	 * @param json the mapper for recorded transition decisions
	 */
	ReplayPreflight(History history, PatchApplication patchApplication, ReviewEvidence reviewEvidence,
		SemanticEvidence semanticEvidence, PathTransitionFacts pathTransitionFacts,
		PathTransitionValidation pathTransitionValidation, SourceReviewValidation sourceReviewValidation,
		JsonMapper json)
	{
		this.history = history;
		this.patchApplication = patchApplication;
		this.reviewEvidence = reviewEvidence;
		this.semanticEvidence = semanticEvidence;
		this.pathTransitionFacts = pathTransitionFacts;
		this.pathTransitionValidation = pathTransitionValidation;
		this.sourceReviewValidation = sourceReviewValidation;
		this.json = json;
	}

	/**
	 * Verifies each recorded source reconstruction without modifying the caller's Git index.
	 *
	 * @param manifestFile the manifest that owns reconstruction patch artifacts
	 * @param manifest the unfinalized replay state
	 * @throws IOException if required reconstructions are missing or cannot reproduce their source tree
	 */
	void verifySourceReconstructions(Path manifestFile, BatchManifest manifest) throws IOException
	{
		if (manifest.version() != ManifestStore.BATCH_MANIFEST_VERSION || manifest.sources() == null ||
			manifest.sources().isEmpty() || manifest.sourceReconstructions() == null ||
			manifest.sourceReconstructions().isEmpty())
		{
			throw new IOException("Topic-replay manifest must declare sources and source reconstructions");
		}
		Map<String, Batch> batches = manifest.batches().stream().collect(Collectors.toMap(Batch::id, batch -> batch));
		for (SourceReconstruction reconstruction : manifest.sourceReconstructions())
		{
			if (reconstruction.sourcePatch() == null)
				patchApplication.verifyBatchReconstruction(manifestFile, reconstruction, batches);
			else
				patchApplication.verifySourceReconstruction(manifestFile, reconstruction);
		}
	}

	/**
	 * Verifies a complete frozen replay plan before it is allowed to rewrite Git history.
	 *
	 * @param manifestFile the manifest that owns replay evidence
	 * @param manifest the sealed replay state
	 * @throws IOException if sources, evidence, transitions, or the final tree are inconsistent
	 */
	void verify(Path manifestFile, BatchManifest manifest) throws IOException
	{
		history.verifyPlanningRepositoryState(manifest.sources().getLast().hash());
		verifySourceReconstructions(manifestFile, manifest);
		reviewEvidence.validateReplayReadiness(manifestFile);
		CandidateUniverseEvidence candidateUniverseEvidence = semanticEvidence.verifyCandidateUniverse(manifestFile,
			manifest);
		reviewEvidence.validateScopeAttestation(manifestFile, manifest);
		semanticEvidence.validatePlan(manifestFile, manifest, candidateUniverseEvidence);
		Path decisions = reviewEvidence.pathTransitionDecisionsFile(manifestFile);
		if (Files.exists(decisions))
		{
			PathTransitionDecision[] transitions = json.readValue(decisions.toFile(), PathTransitionDecision[].class);
			pathTransitionValidation.validate(manifest, pathTransitionFacts.read(manifest), List.of(transitions), true,
				sourceReviewValidation);
		}
		String finalTree = manifest.outputWitnesses().getLast().afterTree();
		if (!manifest.tipTree().equals(finalTree))
			throw new IOException("Topic-replay sealed outputs do not produce the captured tip tree");
	}
}
