package com.holdmyspot.cat.common.git.replay;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Runs the declared acceptance command for each selected sealed replay output.
 */
public final class DeclaredOutputAcceptance
{
	private final CompletedBatchManifest completedManifests;
	private final ReviewEvidence reviewEvidence;
	private final SemanticEvidence semanticEvidence;
	private final OutputGates outputGates;

	/**
	 * Creates a sealed declared-output acceptance boundary.
	 *
	 * @param completedManifests the owner of completed-manifest validation
	 * @param reviewEvidence the owner of reviewer-authored semantic-plan artifacts
	 * @param semanticEvidence the owner of semantic-plan validation
	 * @param outputGates the owner of retained-output command execution
	 */
	DeclaredOutputAcceptance(CompletedBatchManifest completedManifests, ReviewEvidence reviewEvidence,
		SemanticEvidence semanticEvidence, OutputGates outputGates)
	{
		this.completedManifests = completedManifests;
		this.reviewEvidence = reviewEvidence;
		this.semanticEvidence = semanticEvidence;
		this.outputGates = outputGates;
	}

	/**
	 * Runs each selected output's reviewer-declared behavior acceptance command in its sealed output worktree.
	 *
	 * @param manifestFile the replay manifest that owns sealed output witnesses and gate logs
	 * @param outputId the selected output, or {@code null} to verify every declared output
	 * @throws IOException if the manifest, semantic evidence, selected output, or acceptance command is invalid
	 */
	void run(Path manifestFile, String outputId) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		if (outputId != null)
			requireThat(outputId, "outputId").isNotBlank();

		BatchManifest manifest = completedManifests.read(manifestFile);
		CandidateUniverseEvidence candidateUniverseEvidence = semanticEvidence.verifyCandidateUniverse(manifestFile,
			manifest);
		List<SemanticCandidate> candidates = reviewEvidence.readSemanticCandidates(
			reviewEvidence.semanticPlanFile(manifestFile), "semantic plan");
		semanticEvidence.validateCandidates(manifest, candidates, manifest.outputs().stream().map(Output::id).collect(
			Collectors.toSet()), true, reviewEvidence.semanticPlanFile(manifestFile), candidateUniverseEvidence);
		Map<String, OutputWitness> witnessesByOutput = manifest.outputWitnesses().stream().collect(Collectors.toMap(
			OutputWitness::outputId, witness -> witness));
		for (SemanticCandidate candidate : selectCandidates(candidates, outputId))
			outputGates.run(manifestFile, "per-output-acceptance", List.of(target(candidate, witnessesByOutput)));
	}

	/**
	 * Selects declared behavior checks while retaining their reviewer-authored order.
	 *
	 * @param candidates the declared behavior checks
	 * @param outputId one selected output, or {@code null} for every declaration
	 * @return selected checks; an unknown selection remains empty
	 */
	public static List<SemanticCandidate> selectCandidates(List<SemanticCandidate> candidates, String outputId)
	{
		return candidates.stream().filter(candidate -> outputId == null || candidate.output().equals(outputId)).toList();
	}

	/**
	 * Resolves one declared behavior check to its sealed output tree.
	 *
	 * @param candidate the reviewer-declared behavior and command
	 * @param witnesses the retained transitions indexed by output
	 * @return the command and its witnessed result tree
	 * @throws IOException if the behavior names an output without a retained witness
	 */
	public static OutputGates.Target target(SemanticCandidate candidate, Map<String, OutputWitness> witnesses)
		throws IOException
	{
		OutputWitness witness = witnesses.get(candidate.output());
		if (witness == null)
		{
			throw new IOException("Topic-replay output gate cannot find retained output " + candidate.output() +
				" in the sealed manifest");
		}
		return new OutputGates.Target(candidate.output(), witness.afterTree(), candidate.acceptanceCommand());
	}
}
