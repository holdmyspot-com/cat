package io.github.cowwoc.cat.common.git.replay;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Reads and validates completed version-5 replay manifests before a caller can rely on their review decisions.
 */
final class CompletedBatchManifest
{
	private final ManifestStore<BatchManifest> manifests;
	private final OutputPlanValidation outputPlan;
	private final OutputWitnessValidation outputWitnessValidation;
	private final SourceReviewValidation sourceReviewValidation;
	private final OriginReviewFacts originReviewFacts;
	private final ReviewEvidence reviewEvidence;

	/**
	 * Creates a completed-manifest validator.
	 *
	 * @param manifests the manifest persistence boundary
	 * @param outputPlan the sealed-plan validator
	 * @param outputWitnessValidation the output-witness validator
	 * @param sourceReviewValidation the source-review validator
	 * @param originReviewFacts the origin-review fact owner
	 * @param reviewEvidence the origin-review evidence reader
	 */
	CompletedBatchManifest(ManifestStore<BatchManifest> manifests, OutputPlanValidation outputPlan,
		OutputWitnessValidation outputWitnessValidation, SourceReviewValidation sourceReviewValidation,
		OriginReviewFacts originReviewFacts, ReviewEvidence reviewEvidence)
	{
		this.manifests = manifests;
		this.outputPlan = outputPlan;
		this.outputWitnessValidation = outputWitnessValidation;
		this.sourceReviewValidation = sourceReviewValidation;
		this.originReviewFacts = originReviewFacts;
		this.reviewEvidence = reviewEvidence;
	}

	/**
	 * Reads a completed manifest that requires all reviewer judgments.
	 *
	 * @param manifestFile the batch manifest
	 * @return the parsed and validated manifest
	 * @throws IOException if the manifest is incomplete or invalid
	 */
	BatchManifest read(Path manifestFile) throws IOException
	{
		return read(manifestFile, true);
	}

	/**
	 * Reads and validates a completed version-5 batch manifest.
	 *
	 * @param manifestFile the batch manifest
	 * @param requireReviewerJudgments whether source and origin reviews must be complete
	 * @return the parsed and validated manifest
	 * @throws IOException if the manifest is invalid
	 */
	BatchManifest read(Path manifestFile, boolean requireReviewerJudgments) throws IOException
	{
		BatchManifest manifest = manifests.readUnfinalized(manifestFile);
		if (manifest == null || manifest.version() != ManifestStore.BATCH_MANIFEST_VERSION || manifest.sources() == null ||
			manifest.sources().isEmpty() || manifest.batches() == null || manifest.batches().isEmpty())
		{
			throw new IOException("Topic-replay batch manifest must be a non-empty version-5 manifest");
		}
		outputPlan.validateSealedPlan(manifest);
		Set<String> batchIds = new HashSet<>();
		Map<String, Output> outputs = outputsById(manifest.outputs());
		Set<String> sources = new HashSet<>();
		for (SourceSnapshot source : manifest.sources())
		{
			if (source == null || !isFullHash(source.hash()) || !isFullHash(source.parentTree()) ||
				!isFullHash(source.tree()) || !sources.add(source.hash()))
			{
				throw new IOException("Every source snapshot requires a unique full commit hash and parent/source tree hashes");
			}
		}
		if (outputs.values().stream().anyMatch(output -> output.source() != null && !sources.contains(output.source())))
			throw new IOException("A source-backed topic-replay output must refer to a captured source commit");
		outputWitnessValidation.validate(manifest, outputs);
		for (Batch batch : manifest.batches())
		{
			if (batch.id() == null || batch.id().isBlank() || !batchIds.add(batch.id()) || !isFullHash(batch.source()) ||
				!sources.contains(batch.source()) || !outputs.containsKey(batch.destination()) || batch.patch() == null ||
				batch.destinationWitness() == null)
			{
				throw new IOException("Every topic-replay batch requires a unique ID, source, destination, patch, and witness");
			}
			if (!isFullHash(batch.destinationWitness().beforeTree()) || !isFullHash(batch.destinationWitness().afterTree()))
				throw new IOException("Topic-replay batch " + batch.id() + " requires full destination tree hashes");
		}
		if (manifest.sourceReconstructions() == null || manifest.sourceReconstructions().isEmpty())
			throw new IOException("Topic-replay batch manifest must declare source reconstructions");
		Set<String> reconstructedSources = new HashSet<>();
		for (SourceReconstruction reconstruction : manifest.sourceReconstructions())
		{
			if (reconstruction.source() == null || !isFullHash(reconstruction.source()) ||
				!reconstructedSources.add(reconstruction.source()) || !isFullHash(reconstruction.beforeTree()) ||
				!isFullHash(reconstruction.afterTree()))
			{
				throw new IOException(
					"Every source reconstruction requires a unique source, ordered batches, and full tree hashes");
			}
			SourceSnapshot source = manifest.sources().stream().filter(candidate -> candidate.hash().equals(
				reconstruction.source())).findFirst().orElseThrow();
			if (!source.parentTree().equals(reconstruction.beforeTree()) || !source.tree().equals(reconstruction.afterTree()))
			{
				throw new IOException("Source reconstruction tree endpoints do not match captured source facts: " +
					reconstruction.source());
			}
			boolean batchSequence = reconstruction.batchIds() != null && !reconstruction.batchIds().isEmpty();
			if (batchSequence == (reconstruction.sourcePatch() != null))
				throw new IOException("Every source reconstruction requires exactly one batch sequence or source patch");
			if (reconstruction.sourcePatch() != null)
				continue;
			for (String batchId : reconstruction.batchIds())
			{
				if (!batchIds.contains(batchId))
					throw new IOException("Source reconstruction references an unknown batch: " + batchId);
				Batch batch = manifest.batches().stream().filter(candidate -> candidate.id().equals(batchId)).findFirst().
					orElseThrow();
				if (!batch.source().equals(reconstruction.source()))
					throw new IOException("Source reconstruction assigns batch " + batchId + " to the wrong source");
			}
		}
		if (!reconstructedSources.equals(sources))
			throw new IOException("Source reconstructions must account for every captured source exactly once");
		if (requireReviewerJudgments)
		{
			sourceReviewValidation.validateRecordedReviews(manifest, sources);
			originReviewFacts.validate(manifest, reviewEvidence.readOriginReviews(manifestFile), true);
		}
		return manifest;
	}

	/**
	 * Indexes outputs after validating their identifiers and supported subjects.
	 *
	 * @param outputs the manifest outputs
	 * @return outputs by identifier
	 * @throws IOException if an output is invalid or duplicated
	 */
	private static Map<String, Output> outputsById(java.util.List<Output> outputs) throws IOException
	{
		Map<String, Output> result = new java.util.HashMap<>();
		for (Output output : outputs)
		{
			if (output.id() == null || output.id().isBlank() || output.subject() == null ||
				output.source() == null && !TopicOutputDeclaration.isCommitSubject(output.subject()) ||
				output.source() != null && !isFullHash(output.source()) || result.putIfAbsent(output.id(), output) != null)
			{
				throw new IOException("Every output must have a unique identifier, a subject in the format type: summary, " +
					"and an optional source");
			}
		}
		return result;
	}

	/**
	 * Indicates whether a value is a full Git object hash.
	 *
	 * @param value the candidate hash
	 * @return {@code true} if the value is a lowercase 40-digit hexadecimal hash
	 */
	private static boolean isFullHash(String value)
	{
		return value.matches("[0-9a-f]{40}");
	}
}
