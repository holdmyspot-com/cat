package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Immutable producer evidence for one campaign interruption.
 *
 * @param schemaVersion supported evidence schema
 * @param binding exact campaign identity
 * @param expectedCounter predecessor lifecycle counter
 * @param reason finite interruption cause
 * @param stopRef original host or token stop, when applicable
 * @param sourceReviewRef source-review cause, when applicable
 * @param adoptingFence scheduler owner that adopted the stop
 * @param logicalCallId affected call, or empty when no call was admitted
 */
record InterruptionRecord(int schemaVersion, CampaignBinding binding, long expectedCounter, Reason reason,
	Optional<EvidenceRef> stopRef, Optional<EvidenceRef> sourceReviewRef, long adoptingFence,
	Optional<String> logicalCallId)
{
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** Supported interruption causes. */
	enum Reason
	{
		HOST_OR_TOKEN_STOP,
		SOURCE_REVIEW
	}

	/** Validates the finite cause and its exclusive evidence reference. */
	InterruptionRecord
	{
		if (schemaVersion != 1 || expectedCounter < 0 || adoptingFence <= 0)
			throw new IllegalArgumentException("Interruption schema, counter, or fence is invalid");
		Objects.requireNonNull(binding, "binding");
		Objects.requireNonNull(reason, "reason");
		Objects.requireNonNull(stopRef, "stopRef");
		Objects.requireNonNull(sourceReviewRef, "sourceReviewRef");
		Objects.requireNonNull(logicalCallId, "logicalCallId");
		if (reason == Reason.HOST_OR_TOKEN_STOP && (stopRef.isEmpty() || sourceReviewRef.isPresent()) ||
			reason == Reason.SOURCE_REVIEW && (sourceReviewRef.isEmpty() || stopRef.isPresent()))
			throw new IllegalArgumentException("Interruption cause and evidence disagree");
		if (stopRef.isPresent() && !stopRef.orElseThrow().collection().equals("stop-requests"))
			throw new IllegalArgumentException("Host interruption has a foreign stop reference");
		if (logicalCallId.isPresent() && logicalCallId.orElseThrow().isBlank())
			throw new IllegalArgumentException("Interruption call ID must not be blank");
	}

	/**
	 * Decodes only canonical retained producer bytes.
	 *
	 * @param bytes immutable producer JSON
	 * @return validated interruption producer
	 * @throws IOException if the bytes are malformed or noncanonical
	 */
	static InterruptionRecord decode(byte[] bytes) throws IOException
	{
		try
		{
			InterruptionRecord record = JSON.readValue(bytes, InterruptionRecord.class);
			if (record == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(record)))
				throw new IOException("Interruption producer is noncanonical");
			return record;
		}
		catch (RuntimeException failure)
		{
			throw new IOException("Interruption producer is malformed", failure);
		}
	}

	/**
	 * Encodes this validated record for immutable publication.
	 *
	 * @return canonical producer JSON
	 */
	byte[] encode()
	{
		return JSON.writeValueAsBytes(this);
	}
}
