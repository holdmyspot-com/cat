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
 * Writes the deliberately over-broad root candidate used to test decomposition review.
 */
final class TopicReplayRootCandidateFixture
{
	/**
	 * Prevents construction of static fixture operations.
	 */
	private TopicReplayRootCandidateFixture()
	{
	}

	/**
	 * Writes the deliberately inadequate root candidate and its companion evidence artifacts.
	 *
	 * @param manifest the prepared replay manifest
	 * @throws IOException if fixture evidence cannot be written
	 */
	static void record(Path manifest) throws IOException
	{
		recordScopeAttestation(manifest);
		try (ToolScope scope = new ToolScope())
		{
			JsonNode replay = scope.json().readTree(Files.readString(manifest));
			StringBuilder units = new StringBuilder(512);
			for (int index = 0; index < replay.path("inventory").size(); ++index)
			{
				if (index > 0)
					units.append(',');
				units.append("{\"inventoryId\":\"").append(replay.path("inventory").get(index).path("id").
					stringValue()).append("\",\"affectedUnit\":\"root artifact ").append(index).append("\"}");
			}
			String planner = "{\"reviewer\":\"planner\",\"candidates\":[{\"id\":\"planner-root-0\",\"observableOutcome\":" +
				"\"Clean Maven/Java build and foundational developer configuration\",\"discoveryEvidence\":" +
				"\"fixture root artifacts\",\"responsibility\":\"Owns the observable contract: bootstrap.\"," +
				"\"acceptanceEvidence\":\"fixture build\",\"splitCounterfactual\":" +
				"\"Separate unless an exact prerequisite prevents independent activation.\",\"units\":[" + units +
				"],\"exposedEntryPoints\":[]}],\"entryPointFamilies\":[],\"reconciliation\":[{\"plannerCandidateId\":" +
				"\"planner-root-0\",\"independentCandidateId\":\"independent-root-0\",\"disposition\":\"matched\"," +
				"\"evidence\":\"fixture reconciliation\"}]}\n";
			String independent = planner.replace("\"planner\"", "\"independent\"").replace("planner-root-0",
				"independent-root-0").replace(",\"reconciliation\":[{\"plannerCandidateId\":\"independent-root-0\"," +
					"\"independentCandidateId\":\"independent-root-0\",\"disposition\":\"matched\"," +
					"\"evidence\":\"fixture reconciliation\"}]", ",\"reconciliation\":[]");
			Files.writeString(manifest.resolveSibling(manifest.getFileName() + ".candidate-universe.json"), planner);
			Files.writeString(manifest.resolveSibling(manifest.getFileName() + ".independent-candidate-universe.json"),
				independent);
		}
		write(manifest, "planner", "planner-root-", 1);
		write(manifest, "independent", "independent-root-", 1);
		recordPasses(manifest, "planner", "planner-root-", 1);
		recordPasses(manifest, "independent", "independent-root-", 1);
		recordIndependentReviewAttestation(manifest);
	}
}
