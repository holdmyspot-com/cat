package io.github.cowwoc.cat.common.git.replay;

import static io.github.cowwoc.cat.common.git.replay.TopicReplayCandidateUniverseFixtures.recordCandidateUniverseEvidence;

import io.github.cowwoc.cat.common.ToolScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * Writes review evidence for topic-replay tests.
 */
final class TopicReplayReviewEvidenceFixtures
{
	/**
	 * Prevents construction of static fixture operations.
	 */
	private TopicReplayReviewEvidenceFixtures()
	{
	}

	/**
	 * Records the review artifacts required before a fixture can replay a sealed plan.
	 *
	 * @param manifest the replay manifest
	 * @throws IOException if the review evidence cannot be written
	 */
	static void recordReviewEvidence(Path manifest) throws IOException
	{
		recordCandidateUniverseEvidence(manifest);
		Files.writeString(manifest.resolveSibling(manifest.getFileName() + ".review-packet.md"),
			"fixture artifact inventory reviewed\n");
		Files.writeString(manifest.resolveSibling(manifest.getFileName() + ".adversarial-challenges.json"),
			"[{\"operation\":\"add\",\"status\":\"resolved\",\"evidence\":\"fixture review completed\"}]\n");
		try (ToolScope scope = new ToolScope())
		{
			JsonNode replay = scope.json().readTree(Files.readString(manifest));
			Map<String, String> destinations = new HashMap<>();
			Map<String, String> affectedUnits = new HashMap<>();
			for (JsonNode review : replay.path("sourceReviews"))
			{
				if (!review.path("disposition").stringValue().equals("split"))
				{
					String output = review.path("destinations").get(0).stringValue();
					for (JsonNode item : replay.path("inventory"))
					{
						if (item.path("source").stringValue().equals(review.path("source").stringValue()))
							destinations.put(item.path("id").stringValue(), output);
					}
				}
				else
				{
					for (JsonNode placement : review.path("placements"))
					{
						destinations.put(placement.path("inventoryId").stringValue(),
							placement.path("destination").stringValue());
						affectedUnits.put(placement.path("inventoryId").stringValue(),
							placement.path("affectedUnit").stringValue());
					}
				}
			}
			Map<String, List<String>> inventoryByOutput = new HashMap<>();
			Map<String, Integer> candidateUniverseIndex = new HashMap<>();
			int universeIndex = 0;
			for (JsonNode item : replay.path("inventory"))
			{
				String inventoryId = item.path("id").stringValue();
				String output = destinations.get(inventoryId);
				inventoryByOutput.computeIfAbsent(output, _ -> new ArrayList<>()).add(inventoryId);
				candidateUniverseIndex.put(inventoryId, universeIndex);
				universeIndex += 1;
			}
			StringBuilder candidates = new StringBuilder(800).append('[');
			List<JsonNode> witnesses = new ArrayList<>();
			for (JsonNode witness : replay.path("outputWitnesses"))
				witnesses.add(witness);
			for (int witnessIndex = 0; witnessIndex < witnesses.size(); ++witnessIndex)
			{
				JsonNode witness = witnesses.get(witnessIndex);
				String output = witness.path("outputId").stringValue();
				List<String> inventory = inventoryByOutput.get(output);
				for (int candidateIndex = 0; candidateIndex < inventory.size(); ++candidateIndex)
				{
					if (!candidates.toString().equals("["))
						candidates.append(',');
					String candidateId = output + "-" + candidateIndex;
					candidates.append("{\"id\":\"").append(candidateId).
						append("\",\"observableOutcome\":\"fixture ").append(candidateId).
						append(" outcome\",\"candidateDiscovery\":\"fixture responsibility and observable outcome ").
						append(candidateId).append("\",\"splitCounterfactual\":\"fixture split assessment ").
						append(candidateId).append("\",\"candidateUniverseIds\":[\"independent-").append(
							candidateUniverseIndex.get(inventory.get(candidateIndex))).append("\"],\"output\":\"").
						append(output).append("\",\"units\":[{\"inventoryId\":\"").
						append(inventory.get(candidateIndex)).append("\",\"affectedUnit\":\"").append(affectedUnits.
							getOrDefault(inventory.get(candidateIndex), "fixture " + candidateId + " unit")).
						append("\"}],\"prerequisites\":[],\"rejectedEarlierOutputs\":[");
					for (int earlierIndex = 0; earlierIndex < witnessIndex; ++earlierIndex)
					{
						if (earlierIndex > 0)
							candidates.append(',');
						String earlier = witnesses.get(earlierIndex).path("outputId").stringValue();
						candidates.append("{\"output\":\"").append(earlier).
							append("\",\"reason\":\"Fixture output lacks the later behavior.\"}");
					}
					candidates.append("],\"combinedCandidateBlockers\":[");
					for (int laterIndex = candidateIndex + 1; laterIndex < inventory.size(); ++laterIndex)
					{
						if (laterIndex > candidateIndex + 1)
							candidates.append(',');
						candidates.append("{\"candidateId\":\"").append(output).append('-').append(laterIndex).
							append("\",\"reason\":\"Fixture artifacts require one output.\"}");
					}
					candidates.append("],\"acceptanceCommand\":[\"/bin/sh\",\"-c\",\"test -f feature.txt\"],").append(
						"\"evidence\":\"fixture evidence ").append(candidateId).append("\"}");
				}
			}
			candidates.append("]\n");
			Path ledger = manifest.resolveSibling(manifest.getFileName() + ".semantic-placement-ledger.json");
			if (!Files.exists(ledger))
				Files.writeString(ledger, candidates.toString());
			Files.writeString(manifest.resolveSibling(manifest.getFileName() + ".semantic-plan.json"),
				Files.readString(ledger));
		}
	}
}
