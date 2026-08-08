package io.github.cowwoc.cat.common.git.replay;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Declares the ordered topic outputs that later review and replay operations populate.
 */
final class TopicOutputDeclaration
{
	private final JsonMapper json;
	private final ManifestStore<BatchManifest> manifests;
	private final ReviewEvidence reviewEvidence;
	private final SemanticEvidence semanticEvidence;

	/**
	 * Creates a topic-output declaration boundary.
	 *
	 * @param json the mapper for reviewer-authored output declarations
	 * @param manifests the owner of manifest publication
	 * @param reviewEvidence the owner of scope-attestation evidence
	 * @param semanticEvidence the owner of candidate-universe and placement-ledger validation
	 */
	TopicOutputDeclaration(JsonMapper json, ManifestStore<BatchManifest> manifests, ReviewEvidence reviewEvidence,
		SemanticEvidence semanticEvidence)
	{
		this.json = json;
		this.manifests = manifests;
		this.reviewEvidence = reviewEvidence;
		this.semanticEvidence = semanticEvidence;
	}

	/**
	 * Validates and publishes the initial ordered topic-output declarations.
	 *
	 * @param manifestFile the unreviewed manifest that receives the output declarations
	 * @param manifest the current replay state
	 * @param outputFile the reviewer-authored output declarations
	 * @throws IOException if replay state exists, evidence is incomplete, or a declaration is invalid
	 */
	void declare(Path manifestFile, BatchManifest manifest, Path outputFile) throws IOException
	{
		if (!manifest.batches().isEmpty() || !manifest.sourceReconstructions().isEmpty() || !manifest.outputWitnesses().
			isEmpty() || !discardedOutputs(manifest).isEmpty() || !sourceReviews(manifest).isEmpty() ||
			manifest.outputPlan() != null)
		{
			throw new IOException("Declare topic outputs before bootstrap or any replay review is recorded");
		}
		CandidateUniverseEvidence candidateUniverseEvidence = semanticEvidence.verifyCandidateUniverse(manifestFile,
			manifest);
		reviewEvidence.validateScopeAttestation(manifestFile, manifest);
		TopicOutputRequest[] requests = json.readValue(outputFile.toFile(), TopicOutputRequest[].class);
		if (requests.length == 0)
			throw new IOException("Topic-output declarations must contain at least one output");
		Set<String> ids = new HashSet<>();
		List<Output> outputs = new ArrayList<>(requests.length);
		for (TopicOutputRequest request : requests)
		{
			if (request == null || request.id() == null || request.id().isBlank() ||
				!isCommitSubject(request.subject()) || !ids.add(request.id()))
			{
				throw new IOException("Every topic-output declaration requires a unique non-blank id and a subject in " +
					"the format type: summary");
			}
			outputs.add(new Output(request.id(), null, request.subject()));
		}
		BatchManifest declared = new BatchManifest(manifest.version(), manifest.base(), manifest.tipTree(),
			manifest.sources(), outputs, manifest.inventory(), manifest.batches(), manifest.sourceReconstructions(),
			manifest.outputWitnesses(), manifest.discardedOutputs(), manifest.sourceReviews(), manifest.fixedSources(),
			manifest.outputPlan());
		semanticEvidence.validatePlacementLedger(manifestFile, declared, outputs.stream().map(Output::id).collect(
			Collectors.toSet()), candidateUniverseEvidence);
		manifests.write(manifestFile, declared);
	}

	/**
	 * Returns whether a commit subject states one supported change type and a non-blank summary.
	 *
	 * @param subject the proposed commit subject
	 * @return {@code true} if the subject uses the required {@code type: summary} format
	 */
	static boolean isCommitSubject(String subject)
	{
		return subject != null && subject.matches(
			"(?:feature|bugfix|test|refactor|performance|docs|style|config|planning): \\S.*");
	}

	/**
	 * Returns the manifest's discarded outputs, treating an omitted field as empty.
	 *
	 * @param manifest the replay manifest
	 * @return the declared or implied discarded outputs
	 */
	private List<String> discardedOutputs(BatchManifest manifest)
	{
		if (manifest.discardedOutputs() == null)
			return List.of();
		return manifest.discardedOutputs();
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
