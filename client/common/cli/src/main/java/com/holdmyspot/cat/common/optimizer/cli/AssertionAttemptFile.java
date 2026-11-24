package com.holdmyspot.cat.common.optimizer.cli;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Records failed attempts of the one currently undecided assertion check. The campaign lock guards the file.
 */
final class AssertionAttemptFile
{
	private final Path file;
	private final JsonMapper json;
	private final FileReplacer replacer;

	/**
	 * Opens the local counter.
	 *
	 * @param heldOut the campaign's private held-out directory
	 * @param json the campaign's mapper
	 * @param replacer atomically replaces the counter
	 */
	AssertionAttemptFile(Path heldOut, JsonMapper json, FileReplacer replacer)
	{
		this.file = heldOut.resolve("assertion_attempts.json");
		this.json = json;
		this.replacer = replacer;
	}

	/**
	 * Returns the attempts that already failed for a check.
	 *
	 * @param key the exact input key of the check
	 * @return its failed attempts, or zero for a different or unseen check
	 * @throws IOException if the count cannot be read
	 */
	int failures(Sha256 key) throws IOException
	{
		JsonNode saved = read();
		if (saved == null || !saved.path("check").asString().equals(key.hex()))
			return 0;
		return saved.path("failures").intValue();
	}

	/**
	 * Records the failure count before more attempts can run.
	 *
	 * @param key the logical check
	 * @param failures the failures, including the one that just ended
	 * @throws IOException if the count cannot be stored
	 */
	void failed(Sha256 key, int failures) throws IOException
	{
		ObjectNode node = json.createObjectNode().put("check", key.hex()).put("failures", failures);
		Path temporary = Files.createTempFile(file.getParent(), ".assertion-attempts-", ".json");
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
	 * Clears the count of a completed check.
	 *
	 * @param key the completed check's inputs
	 * @throws IOException if the count cannot be read or cleared
	 */
	void decided(Sha256 key) throws IOException
	{
		if (failures(key) != 0)
			Files.deleteIfExists(file);
	}

	/**
	 * Resets an exhausted budget before the campaign accepts a retry choice.
	 *
	 * @throws IOException if the count cannot be read or cleared
	 */
	void retry() throws IOException
	{
		JsonNode saved = read();
		if (saved != null && saved.path("failures").intValue() == Campaign.INFRASTRUCTURE_FAILURE_LIMIT)
			Files.deleteIfExists(file);
	}

	/**
	 * Reads the current failure record.
	 *
	 * @return the record, or null before any recorded failure
	 * @throws IOException if the record is not readable or does not describe a failure
	 */
	private JsonNode read() throws IOException
	{
		if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS))
			return null;
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("The assertion attempt count is not a regular file.");
		try
		{
			JsonNode saved = json.readTree(Files.readAllBytes(file));
			JsonNode check = saved.path("check");
			JsonNode count = saved.path("failures");
			if (!check.isString() || !check.asString().matches("[0-9a-f]{64}") || !count.isInt() ||
				count.intValue() < 1 || count.intValue() > Campaign.INFRASTRUCTURE_FAILURE_LIMIT)
			{
				throw new IOException("The assertion attempt count is malformed.");
			}
			return saved;
		}
		catch (tools.jackson.core.JacksonException e)
		{
			throw new IOException("The assertion attempt count is malformed.", e);
		}
	}
}
