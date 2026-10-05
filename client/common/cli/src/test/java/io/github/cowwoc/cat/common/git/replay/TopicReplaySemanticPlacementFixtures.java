package io.github.cowwoc.cat.common.git.replay;

import static io.github.cowwoc.cat.common.git.replay.TopicReplayCandidateUniverseFixtures.recordCandidateUniverseEvidence;

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
		JsonNode replay = ReplayJson.newMapper().readTree(Files.readString(manifest));
		String acceptanceCommand = ReplayJson.newMapper().writeValueAsString(
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
				append("\",\"observable_outcome\":\"fixture ").append(candidateId).
				append(" outcome\",\"candidate_discovery\":\"fixture responsibility and observable outcome ").
				append(candidateId).append("\",\"split_counterfactual\":\"fixture split assessment ").
				append(candidateId).append("\",\"candidate_universe_ids\":[\"independent-").append(index).
				append("\"],\"output\":\"").append(output).
				append("\",\"units\":[{\"inventory_id\":\"").
				append(inventoryId).append("\",\"affected_unit\":\"fixture ").append(candidateId).
				append(" unit\"}],\"prerequisites\":[],\"rejected_earlier_outputs\":[");
			int outputIndex = outputs.indexOf(output);
			for (int earlier = 0; earlier < outputIndex; ++earlier)
			{
				if (earlier > 0)
					candidates.append(',');
				candidates.append("{\"output\":\"").append(outputs.get(earlier)).
					append("\",\"reason\":\"Fixture output lacks the later behavior.\"}");
			}
			candidates.append("],\"combined_candidate_blockers\":[");
			boolean firstBlocker = true;
			for (int other = index + 1; other < inventory.size(); ++other)
			{
				String otherOutput = destinations.getOrDefault(inventory.get(other).path("id").stringValue(),
					outputs.get(other % outputs.size()));
				if (!output.equals(otherOutput))
					continue;
				if (!firstBlocker)
					candidates.append(',');
				candidates.append("{\"candidate_id\":\"candidate-").append(other).
					append("\",\"reason\":\"Fixture artifacts require one output.\"}");
				firstBlocker = false;
			}
			candidates.append("],\"acceptance_command\":").append(acceptanceCommand).append(',').append(
				"\"evidence\":\"fixture evidence ").append(candidateId).append("\"}");
		}
		candidates.append("]\n");
		Files.writeString(manifest.resolveSibling(manifest.getFileName() + ".semantic-placement-ledger.json"),
			candidates.toString());
	}
}
