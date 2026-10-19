package com.holdmyspot.cat.common.git.replay;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates a proposed output plan and runs each candidate's acceptance command against its draft output tree.
 */
public final class DraftOutputAcceptance
{
	private final JsonMapper json;
	private final OutputPlanValidation outputPlan;
	private final SourceReviewValidation sourceReviewValidation;
	private final ReviewEvidence reviewEvidence;
	private final SemanticEvidence semanticEvidence;
	private final OutputGates outputGates;

	/**
	 * Creates a draft-output acceptance boundary.
	 *
	 * @param json the mapper for reviewer-authored output plans
	 * @param outputPlan the owner of draft-plan validation
	 * @param sourceReviewValidation the owner of source-review coverage validation
	 * @param reviewEvidence the owner of semantic-candidate artifacts
	 * @param semanticEvidence the owner of candidate-universe and placement validation
	 * @param outputGates the owner of draft-tree command execution
	 */
	DraftOutputAcceptance(JsonMapper json, OutputPlanValidation outputPlan,
		SourceReviewValidation sourceReviewValidation, ReviewEvidence reviewEvidence, SemanticEvidence semanticEvidence,
		OutputGates outputGates)
	{
		this.json = json;
		this.outputPlan = outputPlan;
		this.sourceReviewValidation = sourceReviewValidation;
		this.reviewEvidence = reviewEvidence;
		this.semanticEvidence = semanticEvidence;
		this.outputGates = outputGates;
	}

	/**
	 * Runs every declared topic acceptance command against the output tree proposed by an unsealed plan.
	 *
	 * @param manifestFile the manifest that owns the proposed plan and gate logs
	 * @param manifest immutable replay facts and completed reviews
	 * @param planFile the complete reviewer-authored output plan
	 * @throws IOException if the plan, semantic evidence, or an acceptance command is invalid
	 */
	void validate(Path manifestFile, BatchManifest manifest, Path planFile) throws IOException
	{
		Set<String> sources = manifest.sources().stream().map(SourceSnapshot::hash).collect(Collectors.toSet());
		sourceReviewValidation.validateInput(manifest, sourceReviews(manifest), true);
		OutputPlanRequest[] entries = json.readValue(planFile.toFile(), OutputPlanRequest[].class);
		ValidatedOutputPlan validated = outputPlan.validate(manifestFile, manifest, List.of(entries), sources);
		CandidateUniverseEvidence candidateUniverseEvidence = semanticEvidence.verifyCandidateUniverse(manifestFile,
			manifest);
		Path semanticPlan = reviewEvidence.semanticPlanFile(manifestFile);
		List<SemanticCandidate> candidates = reviewEvidence.readSemanticCandidates(semanticPlan, "semantic plan");
		semanticEvidence.validateCandidates(manifest, candidates, manifest.outputs().stream().map(Output::id).collect(
			Collectors.toSet()), true, semanticPlan, candidateUniverseEvidence);
		Map<String, String> treesByOutput = validated.witnesses().stream().collect(Collectors.toMap(
			OutputWitness::outputId, OutputWitness::afterTree));
		for (SemanticCandidate candidate : candidates)
			outputGates.runDraft(manifestFile, "draft-per-output-acceptance", target(candidate, treesByOutput));
	}

	/**
	 * Resolves one declared behavior check to its proposed retained tree.
	 *
	 * @param candidate the reviewer-declared behavior and command
	 * @param trees the proposed retained trees indexed by output
	 * @return the command and its proposed result tree
	 * @throws IOException if the behavior has no proposed retained tree
	 */
	public static OutputGates.Target target(SemanticCandidate candidate, Map<String, String> trees) throws IOException
	{
		String tree = trees.get(candidate.output());
		if (tree == null)
		{
			throw new IOException("Topic-replay draft acceptance cannot find a retained tree for " +
				candidate.output());
		}
		return new OutputGates.Target(candidate.output(), tree, candidate.acceptanceCommand());
	}

	/**
	 * Returns the manifest's source reviews, treating an omitted field as empty.
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
