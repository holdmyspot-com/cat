package io.github.cowwoc.cat.common.git.replay;

import static io.github.cowwoc.cat.common.git.replay.TopicReplayActionResultFixtures.write;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayAttestations.recordIndependentReviewAttestation;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayAttestations.recordScopeAttestation;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayDiscoveryFixtures.recordPasses;

import io.github.cowwoc.cat.common.ToolScope;
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
	 * Writes reconciled planner and independent candidate universes that cover every fixture artifact.
	 *
	 * @param manifest the prepared manifest
	 * @throws IOException if the fixture evidence cannot be written
	 */
	static void recordCandidateUniverseEvidence(Path manifest) throws IOException
	{
		recordScopeAttestation(manifest);
		try (ToolScope scope = new ToolScope())
		{
			JsonNode replay = scope.json().readTree(Files.readString(manifest));
			StringBuilder planner = new StringBuilder(512).append("{\"reviewer\":\"planner\",\"candidates\":[");
			StringBuilder independent = new StringBuilder(512).append("{\"reviewer\":\"independent\",\"candidates\":[");
			StringBuilder reconciliation = new StringBuilder(512).append(",\"reconciliation\":[");
			for (int index = 0; index < replay.path("inventory").size(); ++index)
			{
				if (index > 0)
				{
					planner.append(',');
					independent.append(',');
					reconciliation.append(',');
				}
				String inventoryId = replay.path("inventory").get(index).path("id").stringValue();
				planner.append("{\"id\":\"planner-").append(index).append("\",\"observableOutcome\":\"fixture ").
					append(index).append(" outcome\",\"discoveryEvidence\":\"fixture raw evidence ").append(index).
					append("\",\"responsibility\":\"fixture responsibility ").append(index).
					append("\",\"acceptanceEvidence\":\"fixture acceptance evidence ").append(index).
					append("\",\"splitCounterfactual\":\"fixture split assessment ").append(index).
					append("\",\"units\":[{\"inventoryId\":\"").append(inventoryId).
					append("\",\"affectedUnit\":\"fixture unit ").append(index).
					append("\"}],\"exposedEntryPoints\":[]}");
				independent.append("{\"id\":\"independent-").append(index).
					append("\",\"observableOutcome\":\"fixture ").append(index).
					append(" outcome\",\"discoveryEvidence\":\"independent fixture raw evidence ").append(index).
					append("\",\"responsibility\":\"independent fixture responsibility ").append(index).
					append("\",\"acceptanceEvidence\":\"independent fixture acceptance evidence ").append(index).
					append("\",\"splitCounterfactual\":\"independent fixture split assessment ").append(index).
					append("\",\"units\":[{\"inventoryId\":\"").append(inventoryId).
					append("\",\"affectedUnit\":\"independent fixture unit ").append(index).
					append("\"}],\"exposedEntryPoints\":[]}");
				reconciliation.append("{\"plannerCandidateId\":\"planner-").append(index).
					append("\",\"independentCandidateId\":\"independent-").append(index).
					append("\",\"disposition\":\"matched\",\"evidence\":\"fixture reconciliation ").append(index).
					append("\"}");
			}
			planner.append("],\"entryPointFamilies\":[]").append(reconciliation).append("]}\n");
			independent.append("],\"entryPointFamilies\":[],\"reconciliation\":[]}\n");
			Files.writeString(manifest.resolveSibling(manifest.getFileName() + ".candidate-universe.json"),
				planner.toString());
			Files.writeString(manifest.resolveSibling(manifest.getFileName() + ".independent-candidate-universe.json"),
				independent.toString());
			write(manifest, "planner", "planner-", replay.path("inventory").size());
			write(manifest, "independent", "independent-", replay.path("inventory").size());
			recordPasses(manifest, "planner", "planner-", replay.path("inventory").size());
			recordPasses(manifest, "independent", "independent-", replay.path("inventory").size());
		}
		recordIndependentReviewAttestation(manifest);
	}
}
