package io.github.cowwoc.cat.common.git.replay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes action-result matrix evidence for topic-replay candidate fixtures.
 */
final class TopicReplayActionResultFixtures
{
	/**
	 * Prevents construction of static fixture operations.
	 */
	private TopicReplayActionResultFixtures()
	{
	}

	/**
	 * Writes complete observable-behavior and pairwise-split evidence for one fixture candidate universe.
	 *
	 * @param manifest the prepared manifest
	 * @param reviewer the matrix owner
	 * @param candidatePrefix the fixture candidate identifier prefix
	 * @param candidateCount the number of fixture candidates
	 * @throws IOException if the matrix cannot be written
	 */
	static void write(Path manifest, String reviewer, String candidatePrefix, int candidateCount) throws IOException
	{
		write(manifest, reviewer, candidatePrefix, candidateCount, "combined");
	}

	/**
	 * Writes fixture observable-behavior evidence with one requested pair disposition.
	 *
	 * @param manifest the prepared manifest
	 * @param reviewer the matrix owner
	 * @param candidatePrefix the fixture candidate identifier prefix
	 * @param candidateCount the number of fixture candidates
	 * @param disposition the pairwise split conclusion
	 * @throws IOException if the matrix cannot be written
	 */
	static void write(Path manifest, String reviewer, String candidatePrefix, int candidateCount, String disposition)
		throws IOException
	{
		StringBuilder matrix = new StringBuilder(512).append("{\"rows\":[");
		for (int index = 0; index < candidateCount; ++index)
		{
			if (index > 0)
				matrix.append(',');
			matrix.append("{\"candidate_id\":\"").append(candidatePrefix).append(index).
				append("\",\"observable_behavior\":\"fixture behavior ").append(index).
				append("\",\"observable_result\":\"fixture result ").append(index).
				append("\",\"acceptance_check\":\"fixture acceptance ").append(index).
				append("\",\"required_artifacts\":[\"fixture artifact ").append(index).
				append("\"],\"deferred_artifacts\":[]}");
		}
		matrix.append("],\"pairs\":[");
		boolean first = true;
		for (int left = 0; left < candidateCount; ++left)
		{
			for (int right = left + 1; right < candidateCount; ++right)
			{
				if (!first)
					matrix.append(',');
				matrix.append("{\"first_candidate_id\":\"").append(candidatePrefix).append(left).
					append("\",\"second_candidate_id\":\"").append(candidatePrefix).append(right).
					append("\",\"disposition\":\"").append(disposition).
					append("\",\"removal_result\":\"fixture removal ").append(left).
					append(" from ").append(right).
					append(" tests the independent observable behavior\",\"split_enabling_artifacts\":[\"fixture artifact ").
					append(left).append("\"],\"missing_prerequisite\":");
				if (disposition.equals("combined"))
					matrix.append("\"fixture prerequisite\"");
				else
					matrix.append("null");
				matrix.append('}');
				first = false;
			}
		}
		matrix.append("]}\n");
		Files.writeString(manifest.resolveSibling(manifest.getFileName() + "." + reviewer +
			"-action-result-matrix.json"), matrix.toString());
	}
}
