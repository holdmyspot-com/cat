package com.holdmyspot.cat.common.runner;

import static com.holdmyspot.cat.common.runner.RetainedTrialRequest.FORMAT_VERSION;
import static com.holdmyspot.cat.common.runner.RetainedTrialRequest.IDENTITY_FIELD;
import static com.holdmyspot.cat.common.runner.RetainedTrialRequest.REQUEST_ID_FIELD;
import static com.holdmyspot.cat.common.runner.RetainedTrialRequest.TURNS_FIELD;
import static com.holdmyspot.cat.common.runner.RetainedTrialRequest.VERSION_FIELD;

import com.holdmyspot.cat.common.runner.RetainedTrialResponse.Identity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The broker's receipt for an acknowledged guarded continuation. The receipt belongs to the slot and is removed
 * when the slot is emptied. The runner's ready checkpoint on its own does not show that suspension was acknowledged.
 */
final class RetainedTrialSuccessor
{
	private static final String FILE = ".retained-trial-successor.json";
	private static final String GENERATION = "successor_generation";
	private static final Set<String> FIELDS = Set.of(VERSION_FIELD, REQUEST_ID_FIELD, IDENTITY_FIELD, TURNS_FIELD,
		GENERATION);

	/**
	 * Records the new generation after the broker verifies the runner's terminal acknowledgement.
	 *
	 * @param json the document mapper
	 * @param slot the root of the reserved slot
	 * @param predecessor the checkpoint that the launch resumed
	 * @param generation the successor's startup generation
	 * @throws IOException if the receipt cannot be published
	 */
	static void record(JsonMapper json, Path slot, RetainedTrialClaim predecessor, String generation) throws IOException
	{
		ObjectNode value = json.createObjectNode().put(VERSION_FIELD, FORMAT_VERSION).
			put(REQUEST_ID_FIELD, predecessor.requestId().toString()).put(TURNS_FIELD, predecessor.completedTurns()).
			put(GENERATION, generation);
		value.set(IDENTITY_FIELD, predecessor.identity().document(json));
		SessionRequestCli.write(json, slot.resolve(FILE), value);
	}

	/**
	 * Checks that this slot recorded the supplied predecessor and the current successor together.
	 *
	 * @param json the document mapper
	 * @param slot the root of the reserved slot
	 * @param request the predecessor to match
	 * @param successor the observed current generation and session
	 * @return whether the broker recorded the transition
	 * @throws IOException if the receipt cannot be read or is malformed
	 */
	static boolean matches(JsonMapper json, Path slot, RetainedTrialRequest request, Identity successor)
		throws IOException
	{
		Path file = slot.resolve(FILE);
		if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS))
			return false;
		JsonNode value = RetainedTrialRequest.readDocument(json, file, FIELDS);
		return RetainedTrialRequest.readUuid(value.path(REQUEST_ID_FIELD), REQUEST_ID_FIELD).equals(request.requestId()) &&
			Identity.readOptional(value).equals(request.identity()) &&
			RetainedTrialRequest.readTurns(value).equals(request.completedTurns()) &&
			RetainedTrialRequest.readUuid(value.path(GENERATION), GENERATION).equals(successor.slotGeneration());
	}

	/**
	 * Prevents instantiation.
	 */
	private RetainedTrialSuccessor()
	{
	}
}
