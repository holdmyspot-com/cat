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
			matrix.append("{\"candidateId\":\"").append(candidatePrefix).append(index).
				append("\",\"observableBehavior\":\"fixture behavior ").append(index).
				append("\",\"observableResult\":\"fixture result ").append(index).
				append("\",\"acceptanceCheck\":\"fixture acceptance ").append(index).
				append("\",\"requiredArtifacts\":[\"fixture artifact ").append(index).
				append("\"],\"deferredArtifacts\":[]}");
		}
		matrix.append("],\"pairs\":[");
		boolean first = true;
		for (int left = 0; left < candidateCount; ++left)
		{
			for (int right = left + 1; right < candidateCount; ++right)
			{
				if (!first)
					matrix.append(',');
				matrix.append("{\"firstCandidateId\":\"").append(candidatePrefix).append(left).
					append("\",\"secondCandidateId\":\"").append(candidatePrefix).append(right).
					append("\",\"disposition\":\"").append(disposition).
					append("\",\"removalResult\":\"fixture removal ").append(left).
					append(" from ").append(right).
					append(" tests the independent observable behavior\",\"splitEnablingArtifacts\":[\"fixture artifact ").
					append(left).append("\"],\"missingPrerequisite\":");
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
