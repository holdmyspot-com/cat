package io.github.cowwoc.cat.common.git.replay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes candidate-discovery evidence for topic-replay tests.
 */
final class TopicReplayDiscoveryFixtures
{
	/**
	 * Prevents construction of static fixture operations.
	 */
	private TopicReplayDiscoveryFixtures()
	{
	}

	/**
	 * Writes two complete fixture discovery passes: one that finds all candidates and one fixed-point pass.
	 *
	 * @param manifest the prepared manifest
	 * @param reviewer the evidence owner
	 * @param candidatePrefix the fixture candidate identifier prefix
	 * @param candidateCount the number of fixture candidates
	 * @throws IOException if the evidence cannot be written
	 */
	static void recordPasses(Path manifest, String reviewer, String candidatePrefix, int candidateCount)
		throws IOException
	{
		StringBuilder ids = new StringBuilder();
		for (int index = 0; index < candidateCount; ++index)
		{
			if (index > 0)
				ids.append(',');
			ids.append('"').append(candidatePrefix).append(index).append('"');
		}
		String passes = "{\"passes\":[{\"number\":1,\"examined_candidate_ids\":[" + ids +
			"],\"new_candidate_ids\":[" + ids + "]},{\"number\":2,\"examined_candidate_ids\":[" + ids +
			"],\"new_candidate_ids\":[]}]}\n";
		Files.writeString(manifest.resolveSibling(manifest.getFileName() + "." + reviewer +
			"-candidate-discovery-passes.json"), passes);
	}
}
