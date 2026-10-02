package io.github.cowwoc.cat.common.git.replay;

import static io.github.cowwoc.cat.common.git.replay.TopicReplayCandidateUniverseFixtures.recordCandidateUniverseEvidence;

import io.github.cowwoc.cat.common.ToolScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * Writes semantic-placement evidence for topic-replay tests.
 */
final class TopicReplaySemanticPlacementFixtures
{
	/**
	 * Prevents construction of static fixture operations.
	 */
	private TopicReplaySemanticPlacementFixtures()
	{
	}

	/**
	 * Writes a complete candidate inventory before topic outputs are declared.
	 *
	 * @param manifest the prepared manifest
	 * @param outputs declared output identifiers in chronological order
	 * @param destinations explicit inventory-to-output assignments; unspecified items are distributed across outputs
	 * @throws IOException if the fixture artifact cannot be written
	 */
	static void recordSemanticPlacementLedger(Path manifest, List<String> outputs,
		Map<String, String> destinations) throws IOException
	{
		recordCandidateUniverseEvidence(manifest);
		try (ToolScope scope = new ToolScope())
		{
			JsonNode replay = scope.json().readTree(Files.readString(manifest));
			String acceptanceCommand = scope.json().writeValueAsString(
				GateCommandFixture.command(GateCommandFixture.Mode.CHECK_FEATURE));
			StringBuilder candidates = new StringBuilder(800).append('[');
			List<JsonNode> inventory = new ArrayList<>();
			for (JsonNode item : replay.path("inventory"))
				inventory.add(item);
			for (int index = 0; index < inventory.size(); ++index)
			{
				if (index > 0)
					candidates.append(',');
				String inventoryId = inventory.get(index).path("id").stringValue();
				String output = destinations.getOrDefault(inventoryId, outputs.get(index % outputs.size()));
				String candidateId = "candidate-" + index;
				candidates.append("{\"id\":\"").append(candidateId).
					append("\",\"observableOutcome\":\"fixture ").append(candidateId).
					append(" outcome\",\"candidateDiscovery\":\"fixture responsibility and observable outcome ").
					append(candidateId).append("\",\"splitCounterfactual\":\"fixture split assessment ").
					append(candidateId).append("\",\"candidateUniverseIds\":[\"independent-").append(index).
					append("\"],\"output\":\"").append(output).
					append("\",\"units\":[{\"inventoryId\":\"").
					append(inventoryId).append("\",\"affectedUnit\":\"fixture ").append(candidateId).
					append(" unit\"}],\"prerequisites\":[],\"rejectedEarlierOutputs\":[");
				int outputIndex = outputs.indexOf(output);
				for (int earlier = 0; earlier < outputIndex; ++earlier)
				{
					if (earlier > 0)
						candidates.append(',');
					candidates.append("{\"output\":\"").append(outputs.get(earlier)).
						append("\",\"reason\":\"Fixture output lacks the later behavior.\"}");
				}
				candidates.append("],\"combinedCandidateBlockers\":[");
				boolean firstBlocker = true;
				for (int other = index + 1; other < inventory.size(); ++other)
				{
					String otherOutput = destinations.getOrDefault(inventory.get(other).path("id").stringValue(),
						outputs.get(other % outputs.size()));
					if (!output.equals(otherOutput))
						continue;
					if (!firstBlocker)
						candidates.append(',');
					candidates.append("{\"candidateId\":\"candidate-").append(other).
						append("\",\"reason\":\"Fixture artifacts require one output.\"}");
					firstBlocker = false;
				}
				candidates.append("],\"acceptanceCommand\":").append(acceptanceCommand).append(',').append(
					"\"evidence\":\"fixture evidence ").append(candidateId).append("\"}");
			}
			candidates.append("]\n");
			Files.writeString(manifest.resolveSibling(manifest.getFileName() + ".semantic-placement-ledger.json"),
				candidates.toString());
		}
	}
}
