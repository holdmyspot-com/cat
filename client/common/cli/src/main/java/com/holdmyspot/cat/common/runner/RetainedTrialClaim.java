package com.holdmyspot.cat.common.runner;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The retained trial checkpoint that a guarded resume asks the broker to claim.
 * The broker must verify the claim again before launching the runner.
 *
 * @param requestId the request that started the trial
 * @param identity the slot generation and harness session observed at the checkpoint
 * @param completedTurns the number of complete turns in the prefix
 */
public record RetainedTrialClaim(UUID requestId, RetainedTrialResponse.Identity identity, int completedTurns)
{
	/**
	 * Creates a claim for a complete prefix.
	 *
	 * @throws NullPointerException if the request ID or identity is null
	 * @throws IllegalArgumentException if the identity has no harness session or the count is negative
	 */
	public RetainedTrialClaim
	{
		Objects.requireNonNull(requestId, "requestId");
		Objects.requireNonNull(identity, "identity");
		if (identity.session().isEmpty() || completedTurns < 0)
			throw new IllegalArgumentException("A retained trial claim requires a session and a nonnegative turn count");
	}

	/**
	 * Writes the claim fields that the rest of the resume archive does not already supply.
	 *
	 * @param json the document mapper
	 * @return the claim document
	 */
	ObjectNode write(JsonMapper json)
	{
		return json.createObjectNode().put("request_id", requestId.toString()).
			put("slot_generation", identity.slotGeneration().toString()).put("completed_turns", completedTurns);
	}

	/**
	 * Reads a claim bound to the harness and session named in the same resume archive.
	 *
	 * @param value the claim document
	 * @param harness the harness named in the archive
	 * @param sessionId the harness session named in the archive
	 * @return the claim
	 * @throws IOException if the claim is malformed
	 */
	static RetainedTrialClaim read(JsonNode value, String harness, String sessionId) throws IOException
	{
		JsonNode turns = value.path("completed_turns");
		if (!value.isObject() || value.size() != 3 || !turns.isIntegralNumber() || !turns.canConvertToInt())
			throw new IOException("Malformed retained trial checkpoint claim");
		try
		{
			UUID requestId = readUuid(value.path("request_id"));
			UUID slotGeneration = readUuid(value.path("slot_generation"));
			RetainedTrialResponse.HarnessSession session = new RetainedTrialResponse.HarnessSession(harness, sessionId);
			return new RetainedTrialClaim(requestId, new RetainedTrialResponse.Identity(slotGeneration,
				Optional.of(session)), turns.intValue());
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Malformed retained trial checkpoint claim", e);
		}
	}

	/**
	 * Reads the canonical UUID spelling used for launch and slot identifiers.
	 *
	 * @param node the JSON value
	 * @return the identifier
	 * @throws IllegalArgumentException if the value does not contain a canonical UUID
	 */
	private static UUID readUuid(JsonNode node)
	{
		String text = node.stringValue("");
		UUID uuid = UUID.fromString(text);
		if (!uuid.toString().equals(text))
			throw new IllegalArgumentException("A checkpoint identifier must be a canonical UUID");
		return uuid;
	}
}
