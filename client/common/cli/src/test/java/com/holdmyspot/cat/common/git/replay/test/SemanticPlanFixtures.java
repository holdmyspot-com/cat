package com.holdmyspot.cat.common.git.replay.test;

import com.holdmyspot.cat.common.git.replay.ActionPairAssessment;
import com.holdmyspot.cat.common.git.replay.ActionResultMatrix;
import com.holdmyspot.cat.common.git.replay.ActionResultRow;
import com.holdmyspot.cat.common.git.replay.BatchManifest;
import com.holdmyspot.cat.common.git.replay.CandidateUniverseEvidence;
import com.holdmyspot.cat.common.git.replay.CombinedCandidateBlocker;
import com.holdmyspot.cat.common.git.replay.InventoryItem;
import com.holdmyspot.cat.common.git.replay.ManifestStore;
import com.holdmyspot.cat.common.git.replay.Output;
import com.holdmyspot.cat.common.git.replay.OutputWitness;
import com.holdmyspot.cat.common.git.replay.RejectedEarlierOutput;
import com.holdmyspot.cat.common.git.replay.SemanticCandidate;
import com.holdmyspot.cat.common.git.replay.SemanticUnitReference;
import com.holdmyspot.cat.common.git.replay.SourceReview;
import com.holdmyspot.cat.common.git.replay.SourceSnapshot;
import com.holdmyspot.cat.common.git.replay.UnitPlacement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Supplies reviewed feature, correction, and independent note placements as captured data.
 */
final class SemanticPlanFixtures
{
	/** Prevents construction of static fixture operations. */
	private SemanticPlanFixtures()
	{
	}

	/**
	 * Supplies completed whole-source and split-source placements with two retained outputs.
	 *
	 * @return captured replay decisions
	 */
	static BatchManifest manifest()
	{
		return new BatchManifest(ManifestStore.BATCH_MANIFEST_VERSION, "base", "tree-2",
			List.of(new SourceSnapshot("feature", "tree-0", "tree-1"), new SourceSnapshot("split", "tree-1", "tree-2")),
			List.of(new Output("first", "feature", "feat: feature"), new Output("second", "split", "docs: note")),
			List.of(new InventoryItem("feature:1", "feature", "feature.txt", "000000", "zero", "100644", "original"),
				new InventoryItem("split:1", "split", "feature.txt", "100644", "original", "100644", "corrected"),
				new InventoryItem("split:2", "split", "note.txt", "000000", "zero", "100644", "note")),
			List.of(), List.of(), List.of(new OutputWitness("first", "tree-0", "tree-1"),
				new OutputWitness("second", "tree-1", "tree-2")), List.of(),
			List.of(new SourceReview("feature", "whole", List.of("first"), List.of(), "Feature is complete",
				"Feature owns code"),
				new SourceReview("split", "split", List.of("first", "second"), List.of(
					new UnitPlacement("split:1", "feature correction", "feature", "first", "Correction belongs with feature"),
					new UnitPlacement("split:2", "independent note", "note", "second", "Note remains independent")),
					"Note remains usable separately", "Changed paths are reviewed")), List.of(), null);
	}

	/**
	 * Supplies three independently discovered candidates and their completed placement decisions.
	 *
	 * @return the semantic candidates in declared order
	 */
	static List<SemanticCandidate> candidates()
	{
		SemanticCandidate feature = new SemanticCandidate("feature", "Feature runs", "Developer action finds feature",
			"Correction is required by feature", List.of("reviewer_a"), "first",
			List.of(new SemanticUnitReference("feature:1", "feature implementation")), List.of("check", "feature"),
			List.of(), List.of(), List.of(new CombinedCandidateBlocker("correction", "Feature requires corrected state")),
			"Feature behavior observed");
		SemanticCandidate correction = new SemanticCandidate("correction", "Feature runs correctly",
			"Correction is an observable behavior", "Correction cannot work without feature", List.of("reviewer_b"),
			"first", List.of(new SemanticUnitReference("split:1", "feature correction")), List.of("check", "correction"),
			List.of(), List.of(), List.of(), "Corrected behavior observed");
		SemanticCandidate note = new SemanticCandidate("note", "Reader finds independent note", "Reader action finds note",
			"Note remains separately useful", List.of("reviewer_c"), "second",
			List.of(new SemanticUnitReference("split:2", "independent note")), List.of("check", "note"), List.of(),
			List.of(new RejectedEarlierOutput("first", "First output lacks the independent note")), List.of(),
			"Independent note observed");
		return List.of(feature, correction, note);
	}

	/**
	 * Supplies complete captured reviewer pair decisions, including an optional additional discovered behavior.
	 *
	 * @param reviewerIds the validated reviewer discovery identifiers
	 * @return the discovery facts consumed by semantic policy
	 */
	static CandidateUniverseEvidence evidence(Set<String> reviewerIds)
	{
		List<String> ids = reviewerIds.stream().sorted().toList();
		List<ActionResultRow> rows = ids.stream().map(id -> new ActionResultRow(id, "Action " + id, "Result " + id,
			"Check " + id, List.of("artifact " + id), List.<String>of())).toList();
		List<ActionPairAssessment> pairs = new ArrayList<>();
		for (int first = 0; first < ids.size(); ++first)
		{
			for (int second = first + 1; second < ids.size(); ++second)
			{
				boolean combined = ids.get(first).equals("reviewer_a") && ids.get(second).equals("reviewer_b");
				if (combined)
				{
					pairs.add(new ActionPairAssessment(ids.get(first), ids.get(second), "combined",
						"Correction needs the feature", List.of("reviewed artifact"), "Feature implementation is required"));
				}
				else
				{
					pairs.add(new ActionPairAssessment(ids.get(first), ids.get(second), "separate",
						"Actions remain independently observable", List.of("reviewed artifact"), null));
				}
			}
		}
		return new CandidateUniverseEvidence(reviewerIds, new ActionResultMatrix(rows, pairs));
	}
}
