package io.github.cowwoc.cat.common.git.replay;

import static io.github.cowwoc.cat.common.git.replay.TopicReplayActionResultFixtures.write;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayAttestations.recordIndependentReviewAttestation;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayAttestations.recordScopeAttestation;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayDiscoveryFixtures.recordPasses;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import tools.jackson.databind.JsonNode;

/**
 * Writes reconciled candidate-universe evidence for topic-replay tests.
 */
final class TopicReplayCandidateUniverseFixtures
{
	/**
	 * Prevents construction of static fixture operations.
	 */
	private TopicReplayCandidateUniverseFixtures()
	{
	}

	/**
	 * Writes planner and independent candidate universes that cover every fixture artifact.
	 *
	 * @param manifest the prepared manifest
	 * @throws IOException if the fixture evidence cannot be written
	 */
	static void recordCandidateUniverseEvidence(Path manifest) throws IOException
	{
		recordScopeAttestation(manifest);
		JsonNode replay = ReplayJson.newMapper().readTree(Files.readString(manifest));
		StringBuilder planner = new StringBuilder(512).append("{\"reviewer\":\"planner\",\"candidates\":[");
		StringBuilder independent = new StringBuilder(512).append("{\"reviewer\":\"independent\",\"candidates\":[");
		for (int index = 0; index < replay.path("inventory").size(); ++index)
		{
			if (index > 0)
			{
				planner.append(',');
				independent.append(',');
			}
			String inventoryId = replay.path("inventory").get(index).path("id").stringValue();
			planner.append("{\"id\":\"planner-").append(index).append("\",\"observable_outcome\":\"fixture ").
				append(index).append(" outcome\",\"discovery_evidence\":\"fixture raw evidence ").append(index).
				append("\",\"responsibility\":\"fixture responsibility ").append(index).
				append("\",\"acceptance_evidence\":\"fixture acceptance evidence ").append(index).
				append("\",\"split_counterfactual\":\"fixture split assessment ").append(index).
				append("\",\"units\":[{\"inventory_id\":\"").append(inventoryId).
				append("\",\"affected_unit\":\"fixture unit ").append(index).
				append("\"}],\"exposed_entry_points\":[]}");
			independent.append("{\"id\":\"independent-").append(index).
				append("\",\"observable_outcome\":\"fixture ").append(index).
				append(" outcome\",\"discovery_evidence\":\"independent fixture raw evidence ").append(index).
				append("\",\"responsibility\":\"independent fixture responsibility ").append(index).
				append("\",\"acceptance_evidence\":\"independent fixture acceptance evidence ").append(index).
				append("\",\"split_counterfactual\":\"independent fixture split assessment ").append(index).
				append("\",\"units\":[{\"inventory_id\":\"").append(inventoryId).
				append("\",\"affected_unit\":\"independent fixture unit ").append(index).
				append("\"}],\"exposed_entry_points\":[]}");
		}
		planner.append("],\"entry_point_families\":[]}\n");
		independent.append("],\"entry_point_families\":[]}\n");
		Files.writeString(manifest.resolveSibling(manifest.getFileName() + ".candidate-universe.json"),
			planner.toString());
		Files.writeString(manifest.resolveSibling(manifest.getFileName() + ".independent-candidate-universe.json"),
			independent.toString());
		write(manifest, "planner", "planner-", replay.path("inventory").size());
		write(manifest, "independent", "independent-", replay.path("inventory").size());
		recordPasses(manifest, "planner", "planner-", replay.path("inventory").size());
		recordPasses(manifest, "independent", "independent-", replay.path("inventory").size());
		recordIndependentReviewAttestation(manifest);
	}
}
