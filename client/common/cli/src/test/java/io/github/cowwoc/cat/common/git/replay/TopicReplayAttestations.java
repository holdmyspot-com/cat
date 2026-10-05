package io.github.cowwoc.cat.common.git.replay;

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
		JsonNode replay = ReplayJson.newMapper().readTree(Files.readString(manifest));
		String base = replay.path("base").stringValue();
		String tip = replay.path("sources").get(replay.path("sources").size() - 1).path("hash").stringValue();
		Path universe = manifest.resolveSibling(manifest.getFileName() + ".independent-candidate-universe.json");
		Path matrix = manifest.resolveSibling(manifest.getFileName() + ".independent-action-result-matrix.json");
		Files.writeString(manifest.resolveSibling(manifest.getFileName() + ".independent-review-attestation.json"),
			"{\"reviewer\":\"independent\",\"base\":\"" + base + "\",\"tip\":\"" + tip +
				"\",\"workflow_revision\":\"fixture\",\"raw_diff_handoff\":\"fixture raw diffs\",\n" +
					"\"candidate_universe_sha256\":\"" +
				sha256(universe) + "\",\"action_result_matrix_sha256\":\"" + sha256(matrix) +
				"\",\"unprimed\":true}\n");
	}

	/**
	 * Writes scope evidence that matches the prepared fixture manifest.
	 *
	 * @param manifest the prepared manifest
	 * @throws IOException if the fixture evidence cannot be written
	 */
	static void recordScopeAttestation(Path manifest) throws IOException
	{
		JsonNode replay = ReplayJson.newMapper().readTree(Files.readString(manifest));
		String base = replay.path("base").stringValue();
		String tip = replay.path("sources").get(replay.path("sources").size() - 1).path("hash").stringValue();
		StringBuilder attestation = new StringBuilder(256).append("{\"base\":\"").append(base).
			append("\",\"tip\":\"").append(tip).
			append("\",\"governing_rule_revision\":\"fixture\",\"authorization_evidence\":\"fixture user " +
				"authorization\"");
		if (replay.path("fixed_sources").isEmpty())
			attestation.append(",\"mode\":\"whole_range\"}\n");
		else
		{
			String fixedPrefixTip = replay.path("fixed_sources").get(replay.path("fixed_sources").size() - 1).stringValue();
			attestation.append(",\"mode\":\"fixed_prefix\",\"fixed_prefix_tip\":\"").append(fixedPrefixTip).
				append("\",\"prior_completion_artifact\":\"fixture prior review\"}\n");
		}
		Files.writeString(manifest.resolveSibling(manifest.getFileName() + ".scope-attestation.json"),
			attestation.toString());
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
