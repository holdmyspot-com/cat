package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.ToolScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import tools.jackson.databind.JsonNode;

/**
 * Writes scope and independent-review attestations for topic-replay test evidence.
 */
final class TopicReplayAttestations
{
	/**
	 * Prevents construction of static fixture operations.
	 */
	private TopicReplayAttestations()
	{
	}

	/**
	 * Writes an independent-review handoff attestation for the fixture range.
	 *
	 * @param manifest the prepared manifest
	 * @throws IOException if the fixture evidence cannot be written
	 */
	static void recordIndependentReviewAttestation(Path manifest) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			JsonNode replay = scope.json().readTree(Files.readString(manifest));
			String base = replay.path("base").stringValue();
			String tip = replay.path("sources").get(replay.path("sources").size() - 1).path("hash").stringValue();
			Path universe = manifest.resolveSibling(manifest.getFileName() + ".independent-candidate-universe.json");
			Path matrix = manifest.resolveSibling(manifest.getFileName() + ".independent-action-result-matrix.json");
			Files.writeString(manifest.resolveSibling(manifest.getFileName() + ".independent-review-attestation.json"),
				"{\"reviewer\":\"independent\",\"base\":\"" + base + "\",\"tip\":\"" + tip +
					"\",\"workflowRevision\":\"fixture\",\"rawDiffHandoff\":\"fixture raw diffs\",\n" +
						"\"candidateUniverseSha256\":\"" +
					sha256(universe) + "\",\"actionResultMatrixSha256\":\"" + sha256(matrix) +
					"\",\"unprimed\":true}\n");
		}
	}

	/**
	 * Writes scope evidence that matches the prepared fixture manifest.
	 *
	 * @param manifest the prepared manifest
	 * @throws IOException if the fixture evidence cannot be written
	 */
	static void recordScopeAttestation(Path manifest) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			JsonNode replay = scope.json().readTree(Files.readString(manifest));
			String base = replay.path("base").stringValue();
			String tip = replay.path("sources").get(replay.path("sources").size() - 1).path("hash").stringValue();
			StringBuilder attestation = new StringBuilder(256).append("{\"base\":\"").append(base).
				append("\",\"tip\":\"").append(tip).
				append("\",\"governingRuleRevision\":\"fixture\",\"authorizationEvidence\":\"fixture user " +
					"authorization\"");
			if (replay.path("fixedSources").isEmpty())
				attestation.append(",\"mode\":\"whole-range\"}\n");
			else
			{
				String fixedPrefixTip = replay.path("fixedSources").get(replay.path("fixedSources").size() - 1).stringValue();
				attestation.append(",\"mode\":\"fixed-prefix\",\"fixedPrefixTip\":\"").append(fixedPrefixTip).
					append("\",\"priorCompletionArtifact\":\"fixture prior review\"}\n");
			}
			Files.writeString(manifest.resolveSibling(manifest.getFileName() + ".scope-attestation.json"),
				attestation.toString());
		}
	}

	/**
	 * Returns the SHA-256 digest of one fixture artifact.
	 *
	 * @param file the artifact
	 * @return its lowercase digest
	 * @throws IOException if the artifact cannot be read
	 */
	private static String sha256(Path file) throws IOException
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new AssertionError("The Java runtime does not provide SHA-256", e);
		}
	}
}
