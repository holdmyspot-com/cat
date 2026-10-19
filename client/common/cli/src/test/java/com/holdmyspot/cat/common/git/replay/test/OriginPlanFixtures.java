package com.holdmyspot.cat.common.git.replay.test;

import com.holdmyspot.cat.common.git.replay.Batch;
import com.holdmyspot.cat.common.git.replay.BatchManifest;
import com.holdmyspot.cat.common.git.replay.DestinationWitness;
import com.holdmyspot.cat.common.git.replay.InventoryItem;
import com.holdmyspot.cat.common.git.replay.OriginReview;
import com.holdmyspot.cat.common.git.replay.Output;
import com.holdmyspot.cat.common.git.replay.OutputPlan;
import com.holdmyspot.cat.common.git.replay.OutputWitness;
import com.holdmyspot.cat.common.git.replay.PatchArtifact;
import com.holdmyspot.cat.common.git.replay.PathState;
import com.holdmyspot.cat.common.git.replay.PlannedBatch;
import com.holdmyspot.cat.common.git.replay.PlannedOutput;
import com.holdmyspot.cat.common.git.replay.SourceReview;
import com.holdmyspot.cat.common.git.replay.SourceSnapshot;
import com.holdmyspot.cat.common.git.replay.UnitPlacement;
import com.holdmyspot.cat.common.git.replay.ValidatedOutputPlan;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Supplies captured source, planned-output, and path-state facts for origin sealing decisions.
 */
final class OriginPlanFixtures
{
	/**
	 * Prevents construction.
	 */
	private OriginPlanFixtures()
	{
	}

	/**
	 * Supplies an absorbed correction whose batch contributes to the retained output.
	 *
	 * @return the correction facts
	 */
	static Facts correction()
	{
		return correctionFacts(List.of("original", "corrected"), List.of("whole", "absorbed"),
			List.of("source-1", "source-2"));
	}

	/**
	 * Supplies three chronological batches whose retained result supersedes an earlier correction.
	 *
	 * @return the later-correction facts
	 */
	static Facts laterCorrection()
	{
		return correctionFacts(List.of("original", "corrected", "documented"),
			List.of("whole", "whole", "whole"), List.of("source-1", "source-2", "source-3"));
	}

	/**
	 * Supplies an absorbed source with no batch whose path state equals the retained result.
	 *
	 * @return the equal-state absorption facts
	 */
	static Facts absorbedWithoutBatch()
	{
		return correctionFacts(List.of("original", "corrected"), List.of("whole", "absorbed"),
			List.of("source-1"));
	}

	/**
	 * Supplies an absorbed intermediate source with no batch whose path state differs from the retained result.
	 *
	 * @return the superseded absorption facts
	 */
	static Facts supersededAbsorbed()
	{
		return correctionFacts(List.of("original", "corrected", "revised"),
			List.of("whole", "absorbed", "absorbed"), List.of("source-1"));
	}

	/**
	 * Creates immutable facts for a retained feature output with later logical outputs discarded.
	 *
	 * @param contents chronological source path-state identities
	 * @param dispositions the source review dispositions in that order
	 * @param contributingSources the sources whose batches contribute to the retained output
	 * @return captured source facts, projected decisions, and path states
	 */
	private static Facts correctionFacts(List<String> contents, List<String> dispositions,
		List<String> contributingSources)
	{
		List<SourceSnapshot> sources = new ArrayList<>();
		List<Output> outputs = new ArrayList<>();
		List<InventoryItem> inventory = new ArrayList<>();
		List<SourceReview> sourceReviews = new ArrayList<>();
		List<OriginReview> origins = new ArrayList<>();
		List<PlannedOutput> plannedOutputs = new ArrayList<>();
		List<String> discarded = new ArrayList<>();
		List<String> predecessors = new ArrayList<>();
		for (int index = 0; index < contents.size(); ++index)
		{
			String source = "source-" + (index + 1);
			String tree = "tree-" + (index + 1);
			sources.add(new SourceSnapshot(source, "tree-" + index, tree));
			outputs.add(new Output("output_" + (index + 1), source, contents.get(index)));
			PathState before = PathState.absent();
			if (index > 0)
				before = state(contents.get(index - 1));
			inventory.add(item(source + ":1", source, "feature.txt", before, state(contents.get(index))));
			sourceReviews.add(review(source, dispositions.get(index), List.of("output_1"), List.of()));
			if (index > 0)
			{
				origins.add(origin(source + ":1", predecessors, "corrects_predecessor", "output_1"));
				discarded.add("output_" + (index + 1));
				plannedOutputs.add(new PlannedOutput("output_" + (index + 1), "discarded", List.of()));
			}
			predecessors.add(source);
		}

		List<Batch> batches = new ArrayList<>();
		List<PlannedBatch> plannedBatches = new ArrayList<>();
		String currentTree = "tree-0";
		for (String source : contributingSources)
		{
			String afterTree = sources.stream().filter(candidate -> candidate.hash().equals(source)).findFirst().
				orElseThrow().tree();
			if (contributingSources.size() == 1)
				afterTree = sources.getLast().tree();
			PatchArtifact patch = new PatchArtifact(source + ".patch", "0".repeat(64));
			batches.add(new Batch(source, source, "output_1", patch, new DestinationWitness(currentTree, afterTree)));
			plannedBatches.add(new PlannedBatch(source, source, patch));
			currentTree = afterTree;
		}
		plannedOutputs.addFirst(new PlannedOutput("output_1", "retained", plannedBatches));
		BatchManifest manifest = manifest(sources, outputs, inventory, sourceReviews);
		ValidatedOutputPlan plan = new ValidatedOutputPlan(new OutputPlan(plannedOutputs), batches,
			List.of(new OutputWitness("output_1", "tree-0", currentTree)), discarded);
		Map<String, Map<String, PathState>> paths = new HashMap<>();
		paths.put("tree-0", Map.of("feature.txt", PathState.absent()));
		for (int index = 0; index < contents.size(); ++index)
			paths.put("tree-" + (index + 1), Map.of("feature.txt", state(contents.get(index))));
		return new Facts(manifest, plan, origins, paths);
	}

	/**
	 * Supplies a plan that leaves two reviewed corrections outside their selected earlier output.
	 *
	 * @return the conflicting placement facts
	 */
	static Facts conflict()
	{
		List<SourceSnapshot> sources = List.of(new SourceSnapshot("source-1", "tree-0", "tree-1"),
			new SourceSnapshot("source-2", "tree-1", "tree-2"));
		List<InventoryItem> inventory = new ArrayList<>();
		for (String path : List.of("feature.txt", "secondary.txt"))
		{
			inventory.add(item("source-1:" + path, "source-1", path, PathState.absent(), state("original")));
			inventory.add(item("source-2:" + path, "source-2", path, state("original"), state("corrected")));
		}
		List<Batch> batches = List.of(batch("feature", "source-1", "output_1", "tree-0", "tree-1"),
			batch("correction", "source-2", "output_2", "tree-1", "tree-2"));
		List<OriginReview> origins = List.of(
			origin("source-2:feature.txt", List.of("source-1"), "corrects_predecessor", "output_1"),
			origin("source-2:secondary.txt", List.of("source-1"), "corrects_predecessor", "output_1"));
		BatchManifest manifest = manifest(sources, outputs(), inventory, List.of(
			review("source-1", "whole", List.of("output_1"), List.of()),
			review("source-2", "whole", List.of("output_2"), List.of())));
		ValidatedOutputPlan plan = retainedPlan(batches, List.of(
			new OutputWitness("output_1", "tree-0", "tree-1"),
			new OutputWitness("output_2", "tree-1", "tree-2")));
		return new Facts(manifest, plan, origins, Map.of(
			"tree-1", Map.of("feature.txt", state("original"), "secondary.txt", state("original")),
			"tree-2", Map.of("feature.txt", state("corrected"), "secondary.txt", state("corrected"))));
	}

	/**
	 * Supplies a removed invalid test in its feature output and independent routing in the next output.
	 *
	 * @return the split-removal facts
	 */
	static Facts removal()
	{
		List<SourceSnapshot> sources = List.of(new SourceSnapshot("source-1", "tree-0", "feature-tree"),
			new SourceSnapshot("source-2", "feature-tree", "routing-tree"));
		List<InventoryItem> inventory = List.of(
			item("source-1:1", "source-1", "feature.txt", PathState.absent(), state("feature")),
			item("source-1:2", "source-1", "structural-test.txt", PathState.absent(), state("invalid-test")),
			item("source-2:1", "source-2", "runtime-routing.txt", PathState.absent(), state("routing")),
			item("source-2:2", "source-2", "structural-test.txt", state("invalid-test"), PathState.absent()));
		List<SourceReview> sourceReviews = List.of(review("source-1", "whole", List.of("output_1"), List.of()),
			review("source-2", "split", List.of("output_1", "output_2"), List.of(
				new UnitPlacement("source-2:1", "runtime routing", "routing", "output_2", "Routing is independent."),
				new UnitPlacement("source-2:2", "invalid test", "feature", "output_1", "Removal completes the feature."))));
		List<Batch> batches = List.of(batch("feature", "source-1", "output_1", "tree-0", "feature-tree"),
			batch("removal", "source-2", "output_1", "feature-tree", "clean-tree"),
			batch("routing", "source-2", "output_2", "clean-tree", "routing-tree"));
		ValidatedOutputPlan plan = retainedPlan(batches, List.of(
			new OutputWitness("output_1", "tree-0", "clean-tree"),
			new OutputWitness("output_2", "clean-tree", "routing-tree")));
		return new Facts(manifest(sources, outputs(), inventory, sourceReviews), plan,
			List.of(origin("source-2:2", List.of("source-1"), "removes_invalid_predecessor", "output_1")),
			Map.of("feature-tree", Map.of("structural-test.txt", state("invalid-test")),
				"clean-tree", Map.of("structural-test.txt", PathState.absent()),
				"routing-tree", Map.of("structural-test.txt", PathState.absent())));
	}

	/**
	 * Constructs the two logical output associations used by conflicting and split placements.
	 *
	 * @return the chronological logical outputs
	 */
	private static List<Output> outputs()
	{
		return List.of(new Output("output_1", "source-1", "feature"),
			new Output("output_2", "source-2", "routing"));
	}

	/**
	 * Constructs an unsealed manifest from captured facts and completed source reviews.
	 *
	 * @param sources chronological source snapshots
	 * @param outputs chronological logical outputs
	 * @param inventory captured changed paths
	 * @param reviews completed source reviews
	 * @return the unsealed manifest
	 */
	private static BatchManifest manifest(List<SourceSnapshot> sources, List<Output> outputs,
		List<InventoryItem> inventory, List<SourceReview> reviews)
	{
		return new BatchManifest(5, "base", sources.getLast().tree(), sources, outputs, inventory, List.of(),
			List.of(), List.of(), List.of(), reviews, List.of(), null);
	}

	/**
	 * Constructs one path's captured before-and-after inventory fact.
	 *
	 * @param id the inventory identifier
	 * @param source the source that changed the path
	 * @param path the repository-relative path
	 * @param before the captured previous state
	 * @param after the captured resulting state
	 * @return the path-change fact
	 */
	private static InventoryItem item(String id, String source, String path, PathState before, PathState after)
	{
		return new InventoryItem(id, source, path, before.mode(), before.blob(), after.mode(), after.blob());
	}

	/**
	 * Constructs a present regular-file state from its captured contents identity.
	 *
	 * @param blob the contents identity
	 * @return the present file state
	 */
	private static PathState state(String blob)
	{
		return new PathState("100644", blob);
	}

	/**
	 * Constructs a completed source review with explicit destinations and any split placements.
	 *
	 * @param source the reviewed source
	 * @param disposition the completed atomicity decision
	 * @param destinations reviewed destinations
	 * @param placements reviewed split placements
	 * @return the source review
	 */
	private static SourceReview review(String source, String disposition, List<String> destinations,
		List<UnitPlacement> placements)
	{
		return new SourceReview(source, disposition, destinations, placements,
			"The source's reviewed units belong in their declared outputs.", "Reviewed source " + source);
	}

	/**
	 * Constructs one same-unit predecessor decision.
	 *
	 * @param inventoryId the changed-path identifier
	 * @param predecessors exact earlier same-path sources
	 * @param relationship the reviewed semantic relationship
	 * @param destination the selected earlier output
	 * @return the origin decision
	 */
	private static OriginReview origin(String inventoryId, List<String> predecessors, String relationship,
		String destination)
	{
		return new OriginReview(inventoryId, List.copyOf(predecessors), "same_unit", inventoryId, "feature", relationship,
			destination, List.of(), "Reviewed placement of " + inventoryId);
	}

	/**
	 * Constructs a batch and its captured destination-tree transition.
	 *
	 * @param id the batch identifier
	 * @param source the source that supplies the batch
	 * @param destination the receiving output
	 * @param beforeTree the captured state before the batch
	 * @param afterTree the captured state after the batch
	 * @return the captured batch
	 */
	private static Batch batch(String id, String source, String destination, String beforeTree, String afterTree)
	{
		return new Batch(id, source, destination, new PatchArtifact(id + ".patch", "0".repeat(64)),
			new DestinationWitness(beforeTree, afterTree));
	}

	/**
	 * Constructs retained plan declarations from captured batches and witnesses.
	 *
	 * @param batches captured ordered batches
	 * @param witnesses captured retained-output transitions
	 * @return the validated retained plan
	 */
	private static ValidatedOutputPlan retainedPlan(List<Batch> batches, List<OutputWitness> witnesses)
	{
		List<PlannedOutput> outputs = witnesses.stream().map(witness -> new PlannedOutput(witness.outputId(), "retained",
			batches.stream().filter(batch -> batch.destination().equals(witness.outputId())).map(batch ->
				new PlannedBatch(batch.id(), batch.source(), batch.patch())).toList())).toList();
		return new ValidatedOutputPlan(new OutputPlan(outputs), batches, witnesses, List.of());
	}

	/**
	 * Captured inputs for source-placement and origin-sealing decisions.
	 *
	 * @param manifest captured source facts and completed source reviews
	 * @param plan projected batches, retained tree transitions, and discarded outputs
	 * @param origins the reviewed predecessor placements
	 * @param paths immutable tree-to-path state facts
	 */
	record Facts(BatchManifest manifest, ValidatedOutputPlan plan, List<OriginReview> origins,
		Map<String, Map<String, PathState>> paths)
	{
		/**
		 * Returns the captured state for the requested tree and path.
		 *
		 * @param tree the captured tree identity
		 * @param path the repository-relative path
		 * @return the captured path state
		 * @throws AssertionError if the fixture omitted a required fact
		 */
		PathState read(String tree, String path)
		{
			Map<String, PathState> treePaths = paths.get(tree);
			if (treePaths == null || !treePaths.containsKey(path))
				throw new AssertionError("Missing captured path state: " + tree + ":" + path);
			return treePaths.get(path);
		}
	}
}
