package com.holdmyspot.cat.common.git.replay;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates every prerequisite of an output plan and publishes its sealed decisions without recording an output.
 */
public final class OutputPlanSealing
{
	private final JsonMapper json;
	private final ManifestStore<BatchManifest> manifests;
	private final SourceReviewValidation sourceReviewValidation;
	private final OriginCandidates originCandidates;
	private final OriginReviewValidation originReviewValidation;
	private final OutputPlanValidation outputPlan;
	private final ReviewEvidence reviewEvidence;

	/**
	 * Creates an output-plan sealing boundary.
	 *
	 * @param json the mapper for reviewer-authored output plans
	 * @param manifests the owner of manifest publication
	 * @param sourceReviewValidation the owner of source-review coverage validation
	 * @param originCandidates the owner of exact-path predecessor discovery
	 * @param originReviewValidation the owner of origin-review validation
	 * @param outputPlan the owner of output-plan validation
	 * @param reviewEvidence the owner of manifest-local origin-review evidence
	 */
	public OutputPlanSealing(JsonMapper json, ManifestStore<BatchManifest> manifests,
		SourceReviewValidation sourceReviewValidation, OriginCandidates originCandidates,
		OriginReviewValidation originReviewValidation, OutputPlanValidation outputPlan, ReviewEvidence reviewEvidence)
	{
		this.json = json;
		this.manifests = manifests;
		this.sourceReviewValidation = sourceReviewValidation;
		this.originCandidates = originCandidates;
		this.originReviewValidation = originReviewValidation;
		this.outputPlan = outputPlan;
		this.reviewEvidence = reviewEvidence;
	}

	/**
	 * Validates and seals every retained-or-discarded output decision before replay writes an output witness.
	 *
	 * @param manifestFile the replay manifest that receives the sealed plan
	 * @param manifest the current unfinalized replay state
	 * @param planFile the reviewer-authored complete output plan
	 * @throws IOException if a prerequisite review or the output plan is incomplete or inconsistent
	 */
	void seal(Path manifestFile, BatchManifest manifest, Path planFile) throws IOException
	{
		if (manifest.outputPlan() != null)
			throw new IOException("Topic-replay output plan is already sealed");
		if (!manifest.batches().isEmpty() || !manifest.outputWitnesses().isEmpty() ||
			!discardedOutputs(manifest).isEmpty())
		{
			throw new IOException("Topic-replay must seal its output plan before recording or discarding an output");
		}
		Set<String> sources = manifest.sources().stream().map(SourceSnapshot::hash).collect(Collectors.toSet());
		List<SourceReview> sourceReviews = sourceReviews(manifest);
		sourceReviewValidation.validateInput(manifest, sourceReviews, true);
		OutputPlanRequest[] entries = json.readValue(planFile.toFile(), OutputPlanRequest[].class);
		ValidatedOutputPlan validated = outputPlan.validate(manifestFile, manifest, List.of(entries), sources);
		sealValidatedPlan(manifestFile, manifest, validated);
	}

	/**
	 * Publishes a validated plan only after its projected path changes satisfy every recorded origin decision.
	 *
	 * @param manifestFile the manifest that receives the sealed plan
	 * @param manifest the unsealed state used to validate the complete output plan
	 * @param validated the complete plan and tree witnesses produced by output-plan validation
	 * @throws IOException if origin decisions contradict the projected state or the manifest cannot be written;
	 *         an origin-validation failure leaves the stored manifest unchanged
	 */
	public void sealValidatedPlan(Path manifestFile, BatchManifest manifest, ValidatedOutputPlan validated)
		throws IOException
	{
		BatchManifest projected = manifest.withBatches(validated.batches()).withOutputWitnesses(validated.witnesses()).
			withDiscardedOutputs(validated.discardedOutputs()).withOutputPlan(validated.plan());
		originReviewValidation.validate(projected, originCandidates(projected),
			reviewEvidence.readOriginReviews(manifestFile), sourceReviews(manifest), true, sourceReviewValidation);
		manifests.write(manifestFile, manifest.withOutputPlan(validated.plan()));
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
	 * Returns exact-path predecessors for changed paths that require origin review.
	 *
	 * @param manifest the replay manifest
	 * @return origin candidates in source and inventory order
	 * @throws IOException if the fixed source prefix is invalid
	 */
	private List<OriginCandidate> originCandidates(BatchManifest manifest) throws IOException
	{
		Set<String> capturedSources = manifest.sources().stream().map(SourceSnapshot::hash).collect(Collectors.toSet());
		Set<String> sourcesToReview = sourceReviewValidation.sourcesRequiringReview(manifest, capturedSources);
		List<String> sources = manifest.sources().stream().map(SourceSnapshot::hash).toList();
		List<History.InventoryEntry> inventory = manifest.inventory().stream().map(item -> new History.InventoryEntry(
			item.id(), item.source(), item.path(), item.beforeMode(), item.beforeBlob(), item.afterMode(),
			item.afterBlob())).toList();
		return originCandidates.select(sources, inventory, sourcesToReview).stream().map(candidate -> new OriginCandidate(
			candidate.inventoryId(), candidate.path(), candidate.predecessors())).toList();
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
