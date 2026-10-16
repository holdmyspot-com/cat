package io.github.cowwoc.cat.common.git.replay;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import tools.jackson.databind.json.JsonMapper;

/**
 * Imports one mechanically sealed topic-replay prefix into an extended unreviewed manifest.
 */
final class ReviewedPrefixImport
{
	private final JsonMapper json;
	private final ManifestStore<BatchManifest> manifests;
	private final SealedPrefixValidation sealedPrefixValidation;
	private final PatchArtifactTransfer patchArtifactTransfer;
	private final PatchArtifacts patches;
	private final PatchWorkflow patchWorkflow;
	private final ReviewEvidence reviewEvidence;

	/**
	 * Creates the reviewed-prefix import workflow.
	 *
	 * @param json the manifest JSON mapper
	 * @param manifests the manifest persistence owner
	 * @param sealedPrefixValidation the sealed-prefix validation owner
	 * @param patchArtifactTransfer the immutable patch transfer owner
	 * @param patches the patch-artifact declaration owner
	 * @param patchWorkflow the source-patch capture workflow
	 * @param reviewEvidence the origin-review evidence owner
	 */
	ReviewedPrefixImport(JsonMapper json, ManifestStore<BatchManifest> manifests,
		SealedPrefixValidation sealedPrefixValidation, PatchArtifactTransfer patchArtifactTransfer,
		PatchArtifacts patches, PatchWorkflow patchWorkflow, ReviewEvidence reviewEvidence)
	{
		this.json = json;
		this.manifests = manifests;
		this.sealedPrefixValidation = sealedPrefixValidation;
		this.patchArtifactTransfer = patchArtifactTransfer;
		this.patches = patches;
		this.patchWorkflow = patchWorkflow;
		this.reviewEvidence = reviewEvidence;
	}

	/**
	 * Imports a sealed manifest prefix into a new manifest with the same source tree transitions.
	 *
	 * <p>
	 * A commit hash may change when an earlier history rewrite recreates an otherwise identical transition. This command
	 * matches sources only by their ordered parent-tree and resulting-tree pairs, copies the immutable patch artifacts,
	 * and rewrites the affected source references. It does not infer, alter, or extend any topic-placement decision.
	 *
	 * @param previousManifestFile the manifest whose sealed output prefix is reused
	 * @param newManifestFile the unreviewed manifest whose source prefix has the same tree transitions
	 * @throws IOException if either manifest is invalid, the source transitions differ, or imported artifacts are unsafe
	 */
	void importPrefix(Path previousManifestFile, Path newManifestFile) throws IOException
	{
		requireThat(previousManifestFile, "previousManifestFile").isRegularFile();
		requireThat(newManifestFile, "newManifestFile").isRegularFile();
		BatchManifest previous = readPreparedManifest(previousManifestFile);
		BatchManifest replacement = manifests.readUnfinalized(newManifestFile);
		if (replacement.sources().size() < previous.sources().size())
			throw new IOException("Topic-replay replacement manifest has fewer sources than the reviewed prefix");
		if (!replacement.batches().isEmpty() || !replacement.sourceReconstructions().isEmpty() ||
			!replacement.outputWitnesses().isEmpty() || !replacement.discardedOutputs().isEmpty() ||
			!replacement.sourceReviews().isEmpty())
		{
			throw new IOException("Topic-replay replacement manifest must not contain replay state before importing a " +
				"reviewed prefix");
		}

		Map<String, String> sources = ReviewEvidenceTransfer.sourceTransitionMap(previous.sources(),
			replacement.sources(), Set.of());
		int sealedPrefixLength = sealedPrefixValidation.length(previous);
		List<Batch> sealedBatches = previous.batches().stream().filter(batch ->
			OutputManifestFacts.index(previous.outputs(), batch.destination()) < sealedPrefixLength).toList();
		Set<String> sealedSources = sourcesWithOnlySealedBatches(previous, sealedBatches);
		Set<String> crossBoundaryReviews = previous.sourceReviews().stream().filter(review -> review.destinations().
			stream().anyMatch(destination -> OutputManifestFacts.index(previous.outputs(), destination) >=
				sealedPrefixLength)).map(SourceReview::source).collect(Collectors.toSet());
		crossBoundaryReviews.retainAll(sealedSources);
		if (!crossBoundaryReviews.isEmpty())
		{
			throw new IOException("Topic-replay reviewed-prefix import cannot preserve a sealed source while its review " +
				"places work in the unresolved suffix: " + crossBoundaryReviews + ". Prepare a wholly new review so that " +
				"source can be split and reconstructed consistently.");
		}

		Map<String, PatchArtifact> copiedPatches = patchArtifactTransfer.copy(previousManifestFile, newManifestFile,
			previous, sealedBatches);
		List<Output> outputs = copyOutputs(previous, replacement, sources);
		List<Batch> batches = sealedBatches.stream().map(batch -> new Batch(batch.id(), sources.get(batch.source()),
			batch.destination(), copiedPatches.get(batch.patch().path()), batch.destinationWitness())).toList();
		Set<String> importedBatchIds = sealedBatches.stream().map(Batch::id).collect(Collectors.toSet());
		Map<String, PatchArtifact> sourcePatches = new HashMap<>();
		List<SourceReconstruction> reconstructions = copyReconstructions(previous, newManifestFile, sources,
			sealedSources, copiedPatches, importedBatchIds, sourcePatches);
		addAppendedSourceReconstructions(previous, replacement, newManifestFile, sourcePatches, reconstructions);
		writeUnsealedOutputBatches(previous, newManifestFile, outputs, sources, sealedSources,
			sealedPrefixLength, sourcePatches);
		List<SourceReview> reviews = copySourceReviews(previous, sources, sealedSources, sealedPrefixLength);
		BatchManifest imported = new BatchManifest(ManifestStore.BATCH_MANIFEST_VERSION, replacement.base(),
			replacement.tipTree(), replacement.sources(), outputs, replacement.inventory(), batches, reconstructions,
			previous.outputWitnesses().stream().filter(witness -> OutputManifestFacts.index(previous.outputs(),
				witness.outputId()) < sealedPrefixLength).toList(), previous.discardedOutputs().stream().filter(output ->
				OutputManifestFacts.index(previous.outputs(), output) < sealedPrefixLength).toList(), reviews, List.of(), null);
		manifests.write(newManifestFile, imported);
		importOriginReviews(previousManifestFile, newManifestFile, sources, sealedSources, sealedPrefixLength);
	}

	/**
	 * Reads a manifest that has a contiguous sealed output prefix.
	 *
	 * @param manifestFile the manifest supplying the prefix
	 * @return the parsed manifest with a valid sealed prefix
	 * @throws IOException if the manifest is invalid or has no valid sealed prefix
	 */
	private BatchManifest readPreparedManifest(Path manifestFile) throws IOException
	{
		BatchManifest manifest = manifests.readUnfinalized(manifestFile);
		sealedPrefixValidation.length(manifest);
		return manifest;
	}

	/**
	 * Rewrites the reused output sources for the replacement manifest.
	 *
	 * @param previous the manifest supplying reviewed outputs
	 * @param replacement the manifest receiving the outputs
	 * @param sources the old-to-new source mapping
	 * @return the replacement outputs with reused source references
	 */
	private List<Output> copyOutputs(BatchManifest previous, BatchManifest replacement, Map<String, String> sources)
	{
		List<Output> outputs = new ArrayList<>(replacement.outputs());
		for (int index = 0; index < previous.outputs().size(); ++index)
		{
			Output output = previous.outputs().get(index);
			outputs.set(index, new Output(output.id(), sources.get(output.source()), output.subject()));
		}
		return outputs;
	}

	/**
	 * Copies or recreates reconstructions required by preserved sources.
	 *
	 * @param previous the manifest supplying reconstructions
	 * @param newManifestFile the manifest receiving reconstructions
	 * @param sources the old-to-new source mapping
	 * @param sealedSources sources whose replay placement is entirely sealed
	 * @param copiedPatches copied immutable patch artifacts
	 * @param importedBatchIds batch identifiers copied into the replacement
	 * @param sourcePatches receives whole-source patches required by unsealed outputs
	 * @return the reconstructed source records
	 * @throws IOException if a replacement source patch cannot be captured
	 */
	private List<SourceReconstruction> copyReconstructions(BatchManifest previous, Path newManifestFile,
		Map<String, String> sources, Set<String> sealedSources, Map<String, PatchArtifact> copiedPatches,
		Set<String> importedBatchIds, Map<String, PatchArtifact> sourcePatches) throws IOException
	{
		List<SourceReconstruction> reconstructions = new ArrayList<>();
		for (SourceReconstruction reconstruction : previous.sourceReconstructions())
		{
			if (!sealedSources.contains(reconstruction.source()))
				continue;
			String source = sources.get(reconstruction.source());
			if (reconstruction.sourcePatch() != null)
			{
				PatchArtifact sourcePatch = copiedPatches.get(reconstruction.sourcePatch().path());
				sourcePatches.put(source, sourcePatch);
				reconstructions.add(new SourceReconstruction(source, reconstruction.batchIds(), sourcePatch,
					reconstruction.beforeTree(), reconstruction.afterTree()));
				continue;
			}
			if (importedBatchIds.containsAll(reconstruction.batchIds()))
			{
				reconstructions.add(new SourceReconstruction(source, reconstruction.batchIds(), null,
					reconstruction.beforeTree(), reconstruction.afterTree()));
				continue;
			}
			Path sourcePatchFile = newManifestFile.toAbsolutePath().normalize().getParent().resolve("imported-patches").
				resolve(source + ".raw.patch");
			patchWorkflow.captureSourcePatch(newManifestFile, source, sourcePatchFile);
			PatchArtifact sourcePatch = patches.declare(newManifestFile, sourcePatchFile);
			sourcePatches.put(source, sourcePatch);
			reconstructions.add(new SourceReconstruction(source, null, sourcePatch, reconstruction.beforeTree(),
				reconstruction.afterTree()));
		}
		return reconstructions;
	}

	/**
	 * Adds default reconstructions and output batches for sources appended after the reused prefix.
	 *
	 * @param previous the manifest supplying the reused prefix length
	 * @param replacement the manifest supplying appended sources
	 * @param manifestFile the replacement manifest file
	 * @param sourcePatches receives each appended source patch
	 * @param reconstructions receives each appended source reconstruction
	 * @throws IOException if an appended source patch or output batch cannot be written
	 */
	private void addAppendedSourceReconstructions(BatchManifest previous, BatchManifest replacement, Path manifestFile,
		Map<String, PatchArtifact> sourcePatches, List<SourceReconstruction> reconstructions) throws IOException
	{
		for (int index = previous.sources().size(); index < replacement.sources().size(); ++index)
		{
			SourceSnapshot source = replacement.sources().get(index);
			Path sourcePatchFile = manifestFile.toAbsolutePath().normalize().getParent().resolve("imported-patches").
				resolve(source.hash() + ".raw.patch");
			patchWorkflow.captureSourcePatch(manifestFile, source.hash(), sourcePatchFile);
			PatchArtifact sourcePatch = patches.declare(manifestFile, sourcePatchFile);
			sourcePatches.put(source.hash(), sourcePatch);
			reconstructions.add(new SourceReconstruction(source.hash(), null, sourcePatch, source.parentTree(),
				source.tree()));
			writePreservedOutputBatch(manifestFile, replacement.outputs().get(index), sourcePatch);
		}
	}

	/**
	 * Writes default batches for retained unsealed outputs whose source patch was recreated.
	 *
	 * @param previous the manifest supplying unsealed outputs
	 * @param newManifestFile the replacement manifest file
	 * @param outputs rewritten replacement outputs
	 * @param sources the old-to-new source mapping
	 * @param sealedSources sources whose every placement is sealed
	 * @param sealedPrefixLength number of sealed output positions
	 * @param sourcePatches recreated whole-source patches
	 * @throws IOException if an output batch cannot be written
	 */
	private void writeUnsealedOutputBatches(BatchManifest previous, Path newManifestFile,
		List<Output> outputs, Map<String, String> sources, Set<String> sealedSources, int sealedPrefixLength,
		Map<String, PatchArtifact> sourcePatches) throws IOException
	{
		for (int index = sealedPrefixLength; index < previous.outputs().size(); ++index)
		{
			String previousSource = previous.outputs().get(index).source();
			if (sealedSources.contains(previousSource))
				continue;
			String source = sources.get(previousSource);
			PatchArtifact sourcePatch = sourcePatches.get(source);
			if (sourcePatch != null)
				writePreservedOutputBatch(newManifestFile, outputs.get(index), sourcePatch);
		}
	}

	/**
	 * Writes the default whole-source batch declaration for one imported output.
	 *
	 * @param manifestFile the replacement manifest
	 * @param output the source's default logical output
	 * @param sourcePatch the captured whole-source patch
	 * @throws IOException if the declaration cannot be written without overwriting an existing artifact
	 */
	private void writePreservedOutputBatch(Path manifestFile, Output output, PatchArtifact sourcePatch)
		throws IOException
	{
		Path outputFile = manifestFile.toAbsolutePath().normalize().getParent().resolve("outputs").resolve(output.id() +
			".json");
		if (Files.exists(outputFile))
			throw new IOException("Topic-replay imported output batch already exists: " + outputFile);
		Files.createDirectories(outputFile.getParent());
		BatchRequest[] requests = {new BatchRequest(output.source() + "-raw", output.source(), sourcePatch.path())};
		json.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), requests);
	}

	/**
	 * Copies source reviews whose complete placement remains inside the sealed prefix.
	 *
	 * @param previous the manifest supplying source reviews
	 * @param sources the old-to-new source mapping
	 * @param sealedSources sources whose every placement is sealed
	 * @param sealedPrefixLength number of sealed output positions
	 * @return reviews that remain valid in the replacement
	 * @throws IOException if a reviewed placement cannot be mapped to a replacement source
	 */
	private List<SourceReview> copySourceReviews(BatchManifest previous, Map<String, String> sources,
		Set<String> sealedSources, int sealedPrefixLength)
		throws IOException
	{
		List<SourceReview> reviews = new ArrayList<>();
		for (SourceReview review : previous.sourceReviews())
		{
			if (!sealedSources.contains(review.source()) || review.destinations().stream().anyMatch(destination ->
				OutputManifestFacts.index(previous.outputs(), destination) >= sealedPrefixLength))
			{
				continue;
			}
			reviews.add(new SourceReview(sources.get(review.source()), review.disposition(), review.destinations(),
				ReviewEvidenceTransfer.mapUnitPlacements(sources, review.placements()), review.counterfactual(),
				review.evidence()));
		}
		return reviews;
	}

	/**
	 * Returns sources whose every recorded batch occurs in the sealed output prefix.
	 *
	 * @param manifest the manifest supplying batch declarations
	 * @param sealedBatches batches retained from its sealed prefix
	 * @return sources whose atomicity reviews may be reused
	 */
	private Set<String> sourcesWithOnlySealedBatches(BatchManifest manifest, List<Batch> sealedBatches)
	{
		Set<String> result = sealedBatches.stream().map(Batch::source).collect(Collectors.toSet());
		Set<String> unsealedSources = manifest.batches().stream().filter(batch -> !sealedBatches.contains(batch)).map(
			Batch::source).collect(Collectors.toSet());
		result.removeAll(unsealedSources);
		return result;
	}

	/**
	 * Copies origin evidence whose source and destination are both in the sealed output prefix.
	 *
	 * @param previousManifestFile the manifest that owns the evidence
	 * @param newManifestFile the manifest that receives the evidence
	 * @param sources the verified old-to-new source map
	 * @param sealedSources sources whose placement is entirely sealed
	 * @param sealedPrefixLength number of sealed outputs in the previous manifest
	 * @throws IOException if the evidence cannot be copied
	 */
	private void importOriginReviews(Path previousManifestFile, Path newManifestFile, Map<String, String> sources,
		Set<String> sealedSources, int sealedPrefixLength) throws IOException
	{
		List<OriginReview> previous = reviewEvidence.readOriginReviews(previousManifestFile);
		if (previous.isEmpty())
			return;
		BatchManifest manifest = manifests.readUnfinalized(previousManifestFile);
		Map<String, String> inventorySources = manifest.inventory().stream().collect(Collectors.toMap(InventoryItem::id,
			InventoryItem::source));
		List<OriginReview> replacement = previous.stream().filter(review -> sealedSources.contains(inventorySources.get(
			review.inventoryId())) && review.destination() != null && OutputManifestFacts.index(manifest.outputs(),
			review.destination()) < sealedPrefixLength).map(review -> new OriginReview(review.inventoryId(),
			review.predecessors().stream().map(sources::get).toList(), review.classification(), review.affectedUnit(),
			review.owningResponsibility(), review.predecessorRelationship(), review.destination(),
			review.rejectedDestinations(), review.evidence())).toList();
		if (replacement.isEmpty())
			return;
		reviewEvidence.writeOriginReviews(newManifestFile, replacement);
	}
}
