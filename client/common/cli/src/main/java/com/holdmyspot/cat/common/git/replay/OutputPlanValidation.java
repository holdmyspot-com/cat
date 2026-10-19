package com.holdmyspot.cat.common.git.replay;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates a complete reviewer-authored output plan against immutable replay facts.
 *
 * <p>
 * This class does not persist a plan or select the replay lifecycle transition that consumes it.
 */
public final class OutputPlanValidation
{
	private final History history;
	private final PatchArtifacts patches;
	private final PatchApplication patchApplication;

	/**
	 * Creates an output-plan validation boundary.
	 *
	 * @param history the owner of captured Git tree facts
	 * @param patches the owner of manifest-local patch declarations
	 * @param patchApplication the isolated patch-application boundary
	 */
	public OutputPlanValidation(History history, PatchArtifacts patches,
		PatchApplication patchApplication)
	{
		this.history = history;
		this.patches = patches;
		this.patchApplication = patchApplication;
	}

	/**
	 * Converts and validates reviewers' complete output plan without changing replay state.
	 *
	 * @param manifestFile the replay manifest that owns the plan's patch artifacts
	 * @param manifest the immutable replay facts and completed reviews
	 * @param requests every requested output disposition in chronological order
	 * @param sources the captured source hashes
	 * @return the immutable plan and its projected replay state that can be executed one output at a time
	 * @throws IOException if a request is incomplete, unsafe, inconsistent with review evidence, or fails dry-run replay
	 */
	ValidatedOutputPlan validate(Path manifestFile, BatchManifest manifest, List<OutputPlanRequest> requests,
		Set<String> sources) throws IOException
	{
		if (requests.size() != manifest.outputs().size())
		{
			throw new IOException("Topic-replay output plan must decide every logical output; expected " +
				manifest.outputs().size() + " but found " + requests.size());
		}
		List<PlannedOutput> plannedOutputs = new ArrayList<>(requests.size());
		List<Batch> projectedBatches = new ArrayList<>();
		List<OutputWitness> projectedWitnesses = new ArrayList<>();
		List<String> projectedDiscardedOutputs = new ArrayList<>();
		Set<String> batchIds = new HashSet<>();
		Map<String, Set<String>> destinationsBySource = new HashMap<>();
		String currentTree = history.baseTree(manifest.base());
		for (int index = 0; index < requests.size(); ++index)
		{
			OutputPlanRequest request = requests.get(index);
			Output output = manifest.outputs().get(index);
			if (request == null || !output.id().equals(request.outputId()) || request.disposition() == null)
			{
				throw new IOException("Topic-replay output plan entries must name each logical output in chronological order");
			}
			if (!request.disposition().equals("retained") && !request.disposition().equals("discarded"))
			{
				throw new IOException("Topic-replay output plan must mark " + output.id() + " retained or discarded");
			}
			List<BatchRequest> batches = request.batches();
			if (batches == null)
				batches = List.of();
			if (request.disposition().equals("retained") && batches.isEmpty())
				throw new IOException("Retained output " + output.id() + " must declare at least one batch");
			if (request.disposition().equals("discarded") && !batches.isEmpty())
				throw new IOException("Discarded output " + output.id() + " must not declare batches");
			List<PlannedBatch> plannedBatches = new ArrayList<>(batches.size());
			String beforeTree = currentTree;
			for (BatchRequest batch : batches)
			{
				if (batch == null || batch.id() == null || batch.id().isBlank() || batch.source() == null ||
					batch.source().isBlank() || batch.patch() == null || batch.patch().isBlank())
				{
					throw new IOException("Output-plan batches require non-blank id, source, and patch values: " + output.id());
				}
				if (!batchIds.add(batch.id()))
					throw new IOException("Topic-replay output plan reuses batch ID: " + batch.id());
				if (!sources.contains(batch.source()))
					throw new IOException("Topic-replay output plan names an unknown source: " + batch.source());
				Path patchFile = patches.resolve(manifestFile, batch.patch());
				PatchArtifact patch = patches.declare(manifestFile, patchFile);
				String afterTree = patchApplication.apply(manifestFile, patches.resolve(manifestFile, patch), currentTree);
				plannedBatches.add(new PlannedBatch(batch.id(), batch.source(), patch));
				projectedBatches.add(new Batch(batch.id(), batch.source(), output.id(), patch,
					new DestinationWitness(currentTree, afterTree)));
				currentTree = afterTree;
				destinationsBySource.computeIfAbsent(batch.source(), ignored -> new HashSet<>()).add(output.id());
			}
			plannedOutputs.add(new PlannedOutput(output.id(), request.disposition(), plannedBatches));
			if (request.disposition().equals("retained"))
				projectedWitnesses.add(new OutputWitness(output.id(), beforeTree, currentTree));
			else
				projectedDiscardedOutputs.add(output.id());
		}
		validateSourceDestinations(manifest, plannedOutputs, destinationsBySource);
		if (!manifest.tipTree().equals(currentTree))
		{
			throw new IOException("Topic-replay output plan produces tree " + currentTree + " instead of captured tip tree " +
				manifest.tipTree() + ". Inspect the tree difference and add the missing reviewed batch before sealing.");
		}
		return new ValidatedOutputPlan(new OutputPlan(plannedOutputs), projectedBatches, projectedWitnesses,
			projectedDiscardedOutputs);
	}

	/**
	 * Verifies projected batch destinations agree with the completed source reviews.
	 *
	 * @param manifest the completed source reviews and source-to-output associations
	 * @param plannedOutputs the projected retained-or-discarded output decisions
	 * @param destinationsBySource the projected batch destinations for each source
	 * @throws IOException if a source placement contradicts its completed review
	 */
	public static void validateSourceDestinations(BatchManifest manifest, List<PlannedOutput> plannedOutputs,
		Map<String, Set<String>> destinationsBySource) throws IOException
	{
		for (SourceReview review : sourceReviews(manifest))
		{
			Set<String> destinations = destinationsBySource.getOrDefault(review.source(), Set.of());
			if (review.disposition().equals("absorbed") && destinations.isEmpty())
			{
				if (!isPlannedSourceOutputDiscarded(manifest, plannedOutputs, review.source()))
				{
					throw new IOException("An absorbed source without a batch must discard its own output: " +
						review.source());
				}
				continue;
			}
			if (!new HashSet<>(review.destinations()).equals(destinations))
			{
				throw new IOException("Output plan destinations for source " + review.source() +
					" do not match its completed source review");
			}
		}
	}

	/**
	 * Recreates the tree after one draft output without sealing the draft.
	 *
	 * @param manifestFile the replay manifest that owns the plan's patch artifacts
	 * @param manifest the immutable replay facts and completed reviews
	 * @param requests the draft output declarations
	 * @param outputId the output after which to return the reconstructed tree
	 * @return the tree after the named output
	 * @throws IOException if the draft is unsafe, incomplete through the named output, or cannot be applied
	 */
	String draftTree(Path manifestFile, BatchManifest manifest, List<OutputPlanRequest> requests, String outputId)
		throws IOException
	{
		int outputIndex = outputIndex(manifest.outputs(), outputId);
		if (requests.size() <= outputIndex)
		{
			throw new IOException("Topic-replay draft plan does not decide output " + outputId + " and every earlier output");
		}
		Set<String> sources = new HashSet<>();
		for (SourceSnapshot source : manifest.sources())
			sources.add(source.hash());
		Map<String, SourceReview> reviewsBySource = new HashMap<>();
		for (SourceReview review : sourceReviews(manifest))
			reviewsBySource.put(review.source(), review);
		String currentTree = history.baseTree(manifest.base());
		for (int index = 0; index <= outputIndex; ++index)
		{
			OutputPlanRequest request = requests.get(index);
			Output output = manifest.outputs().get(index);
			if (request == null || !output.id().equals(request.outputId()) || request.disposition() == null ||
				!request.disposition().equals("retained") && !request.disposition().equals("discarded"))
			{
				throw new IOException("Topic-replay draft plan must decide output " + output.id() + " in chronological order");
			}
			List<BatchRequest> batches = request.batches();
			if (batches == null)
				batches = List.of();
			if (request.disposition().equals("retained") && batches.isEmpty())
				throw new IOException("Topic-replay retained draft output has no batches: " + output.id());
			if (request.disposition().equals("discarded") && !batches.isEmpty())
				throw new IOException("Topic-replay discarded draft output has batches: " + output.id());
			for (BatchRequest batch : batches)
			{
				if (batch == null || batch.id() == null || batch.id().isBlank() || batch.source() == null ||
					!sources.contains(batch.source()) || batch.patch() == null || batch.patch().isBlank())
				{
					throw new IOException("Topic-replay draft output requires an id, captured source, and patch: " + output.id());
				}
				validateDraftDestination(batch, output, reviewsBySource.get(batch.source()));
				Path patch = patches.resolve(manifestFile, batch.patch());
				currentTree = patchApplication.apply(manifestFile, patch, currentTree);
			}
		}
		return currentTree;
	}

	/**
	 * Verifies one draft batch respects a completed source review's destination choices.
	 *
	 * @param batch the draft batch declaration
	 * @param output the proposed destination output
	 * @param review the completed source review, or {@code null} if the source is not yet reviewed
	 * @throws IOException if the proposed destination is excluded by the completed review
	 */
	public static void validateDraftDestination(BatchRequest batch, Output output, SourceReview review) throws IOException
	{
		if (review != null && !review.destinations().contains(output.id()))
		{
			throw new IOException("Draft batch " + batch.id() + " places source " + batch.source() + " in " +
				output.id() + ", but its completed review assigns it only to: " + String.join(", ",
				review.destinations()) + ". Move the batch to one of those outputs, or revise the review.");
		}
	}

	/**
	 * Verifies that recorded outputs still match the complete plan sealed before replay began.
	 *
	 * @param manifest the replay manifest
	 * @throws IOException if the plan is absent, malformed, or differs from recorded output decisions
	 */
	void validateSealedPlan(BatchManifest manifest) throws IOException
	{
		OutputPlan plan = manifest.outputPlan();
		if (plan == null || plan.outputs() == null || plan.outputs().size() != manifest.outputs().size())
		{
			throw new IOException("Topic-replay preflight requires one sealed disposition for every logical output");
		}
		Set<String> discarded = new HashSet<>(discardedOutputs(manifest));
		Set<String> retained = manifest.outputWitnesses().stream().map(OutputWitness::outputId).collect(Collectors.toSet());
		for (int index = 0; index < manifest.outputs().size(); ++index)
		{
			Output output = manifest.outputs().get(index);
			PlannedOutput planned = plan.outputs().get(index);
			if (planned == null || !output.id().equals(planned.outputId()) || planned.disposition() == null ||
				planned.batches() == null)
			{
				throw new IOException("Topic-replay output plan must name every logical output in chronological order");
			}
			if (planned.disposition().equals("retained"))
			{
				if (planned.batches().isEmpty() || !retained.contains(output.id()) || discarded.contains(output.id()))
					throw new IOException("Topic-replay output plan and recorded retained output differ: " + output.id());
			}
			else if (planned.disposition().equals("discarded"))
			{
				if (!planned.batches().isEmpty() || !discarded.contains(output.id()) || retained.contains(output.id()))
				{
					throw new IOException("Topic-replay output plan and recorded discarded output differ: " + output.id() +
						"; planHasBatches=" + !planned.batches().isEmpty() + ", recordedDiscard=" + discarded.contains(
						output.id()) + ", recordedRetained=" + retained.contains(output.id()));
				}
			}
			else
				throw new IOException("Topic-replay output plan must mark " + output.id() + " retained or discarded");
		}
	}

	/**
	 * Returns one output's sealed disposition.
	 *
	 * @param manifest the replay manifest containing the sealed plan
	 * @param outputId the logical output identifier
	 * @return the sealed output disposition
	 * @throws IOException if replay has not sealed a complete plan or the plan omits the output
	 */
	PlannedOutput sealedOutput(BatchManifest manifest, String outputId) throws IOException
	{
		if (manifest.outputPlan() == null)
			throw new IOException("Topic-replay must seal a complete output plan before recording or discarding outputs");
		return manifest.outputPlan().outputs().stream().filter(output -> output.outputId().equals(outputId)).findFirst().
			orElseThrow(() -> new IOException("Topic-replay output plan does not contain output: " + outputId));
	}

	/**
	 * Indicates whether supplied batch declarations exactly repeat a sealed output declaration.
	 *
	 * @param requests the declarations supplied for recording
	 * @param plannedBatches the declarations sealed in the output plan
	 * @return {@code true} if every declaration has the same identifier, source, and patch path in the same order
	 */
	boolean hasSameBatches(List<BatchRequest> requests, List<PlannedBatch> plannedBatches)
	{
		if (requests.size() != plannedBatches.size())
			return false;
		for (int index = 0; index < requests.size(); ++index)
		{
			BatchRequest request = requests.get(index);
			PlannedBatch planned = plannedBatches.get(index);
			if (request == null || request.id() == null || request.source() == null || request.patch() == null ||
				!request.id().equals(planned.id()) || !request.source().equals(planned.source()) ||
				!request.patch().equals(planned.patch().path()))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Returns source reviews, treating an omitted field in an unfinished manifest as empty.
	 *
	 * @param manifest the replay manifest
	 * @return the declared or implied source reviews
	 */
	private static List<SourceReview> sourceReviews(BatchManifest manifest)
	{
		if (manifest.sourceReviews() == null)
			return List.of();
		return manifest.sourceReviews();
	}

	/**
	 * Returns discarded output identifiers, treating an omitted unfinished-manifest field as empty.
	 *
	 * @param manifest the replay manifest
	 * @return the declared or implied discarded output identifiers
	 */
	private List<String> discardedOutputs(BatchManifest manifest)
	{
		if (manifest.discardedOutputs() == null)
			return List.of();
		return manifest.discardedOutputs();
	}

	/**
	 * Returns the position of a logical output in the replay order.
	 *
	 * @param outputs the ordered logical outputs
	 * @param outputId the requested output identifier
	 * @return the zero-based output position
	 * @throws IOException if the output is unknown
	 */
	private int outputIndex(List<Output> outputs, String outputId) throws IOException
	{
		for (int index = 0; index < outputs.size(); ++index)
		{
			if (outputs.get(index).id().equals(outputId))
				return index;
		}
		throw new IOException("Topic-replay manifest does not contain output: " + outputId);
	}

	/**
	 * Indicates whether a reviewed source's logical output is declared discarded by the complete output plan.
	 *
	 * @param manifest the immutable replay facts
	 * @param plannedOutputs the complete replay plan
	 * @param source the reviewed source commit
	 * @return {@code true} if the source has a discarded logical output
	 */
	private static boolean isPlannedSourceOutputDiscarded(BatchManifest manifest, List<PlannedOutput> plannedOutputs,
		String source)
	{
		for (Output output : manifest.outputs())
		{
			if (!source.equals(output.source()))
				continue;
			return plannedOutputs.stream().filter(candidate -> candidate.outputId().equals(output.id())).findFirst().
				orElseThrow().disposition().equals("discarded");
		}
		return false;
	}
}
