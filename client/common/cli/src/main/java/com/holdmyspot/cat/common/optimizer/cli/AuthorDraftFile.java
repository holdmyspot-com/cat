package com.holdmyspot.cat.common.optimizer.cli;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.runner.RetainedTrialClaim;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse.HarnessSession;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse.Identity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Keeps the completed author phase while the campaign's held-out checks run. The campaign lock guards the file.
 */
final class AuthorDraftFile
{
	private static final int VERSION = 1;
	private final Path file;
	private final JsonMapper json;
	private final FileReplacer replacer;

	/**
	 * Opens the held-out checkpoint of the campaign's current author session.
	 *
	 * @param heldOut the campaign's held-out directory
	 * @param json the campaign's mapper
	 * @param replacer atomically updates the checkpoint
	 */
	AuthorDraftFile(Path heldOut, JsonMapper json, FileReplacer replacer)
	{
		this.file = heldOut.resolve("author_draft.json");
		this.json = json;
		this.replacer = replacer;
	}

	/**
	 * Reads the most recent durable author phase, including the original broker claim if it is suspended.
	 *
	 * @return the saved draft, or empty when there is none
	 * @throws IOException if the checkpoint cannot be read or has invalid fields
	 */
	Optional<Saved> read() throws IOException
	{
		if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS))
			return Optional.empty();
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("The author's draft checkpoint is not a regular file.");
		try
		{
			JsonNode node = json.readTree(Files.readAllBytes(file));
			if (node.path("version").asInt(-1) != VERSION || !node.path("salt").isIntegralNumber() ||
				!node.path("final").isBoolean() || !node.path("operation").isString() ||
				!node.path("request").isString())
			{
				throw new IOException("The author's draft checkpoint is malformed.");
			}
			SessionResult result = new PeerTrialProtocol(json).result(node.path("draft"));
			if (!(result instanceof SessionResult.Completed completed) || completed.endedEarly())
				throw new IOException("The author's draft checkpoint does not hold a complete phase.");
			return Optional.of(new Saved(new Sha256(node.path("operation").asString()), node.path("salt").longValue(),
				new Sha256(node.path("request").asString()), node.path("final").asBoolean(), completed,
				claim(node.path("claim"))));
		}
		catch (IllegalArgumentException | tools.jackson.core.JacksonException e)
		{
			throw new IOException("The author's draft checkpoint is malformed.", e);
		}
	}

	/**
	 * Reads the previously observed broker identity.
	 *
	 * @param node the optional claim fields
	 * @return the original author claim, if it was suspended
	 * @throws IOException if the claim is incomplete
	 */
	private static Optional<RetainedTrialClaim> claim(JsonNode node) throws IOException
	{
		if (node.isMissingNode())
			return Optional.empty();
		if (!node.path("request").isString() || !node.path("generation").isString() ||
			!node.path("harness").isString() || !node.path("session").isString() || !node.path("turns").isInt())
		{
			throw new IOException("The author's draft checkpoint has an incomplete broker identity.");
		}
		HarnessSession nativeSession = new HarnessSession(node.path("harness").asString(),
			node.path("session").asString());
		Identity identity = new Identity(UUID.fromString(node.path("generation").asString()), Optional.of(nativeSession));
		return Optional.of(new RetainedTrialClaim(UUID.fromString(node.path("request").asString()), identity,
			node.path("turns").intValue()));
	}

	/**
	 * Writes a phase before its checks begin.
	 *
	 * @param saved the phase and its request binding
	 * @throws IOException if the checkpoint cannot be written
	 */
	void write(Saved saved) throws IOException
	{
		ObjectNode node = json.createObjectNode().put("version", VERSION).put("operation", saved.operation().hex()).
			put("salt", saved.salt()).put("request", saved.request().hex()).put("final", saved.finalPhase());
		node.set("draft", new PeerTrialProtocol(json).result(saved.result()));
		saved.claim().ifPresent(claim ->
		{
			HarnessSession nativeSession = claim.identity().session().orElseThrow();
			node.putObject("claim").put("request", claim.requestId().toString()).
				put("generation", claim.identity().slotGeneration().toString()).
				put("harness", nativeSession.harness()).put("session", nativeSession.sessionId()).
				put("turns", claim.completedTurns());
		});
		Path temporary = Files.createTempFile(file.getParent(), ".author-draft-", ".json");
		try
		{
			Files.write(temporary, json.writeValueAsBytes(node));
			replacer.replace(temporary, file);
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Removes a phase once its validation and authorized use are complete.
	 *
	 * @throws IOException if the checkpoint cannot be removed
	 */
	void delete() throws IOException
	{
		Files.deleteIfExists(file);
	}

	/**
	 * The author result and the operation whose checks will use it.
	 *
	 * @param operation identifies the campaign inputs and feedback of this author operation
	 * @param salt fixes the authored case seeds of this operation
	 * @param request identifies the author request's first turn and launch configuration
	 * @param finalPhase whether the result is ready for the final validation without another author turn
	 * @param result the author output and session evidence
	 * @param claim the exact broker identity of a suspended initial phase, if present
	 */
	record Saved(Sha256 operation, long salt, Sha256 request, boolean finalPhase, SessionResult.Completed result,
		Optional<RetainedTrialClaim> claim)
	{
	}
}
