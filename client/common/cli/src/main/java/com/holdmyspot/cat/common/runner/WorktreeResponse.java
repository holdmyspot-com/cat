package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The answer to a worktree request, which its receiver writes as the {@code response.json} of the request's response
 * directory.
 *
 * @param status {@link WorktreeRequest#DONE}, {@link WorktreeRequest#REFUSED} or {@link WorktreeRequest#FAILED}
 * @param reason why the request was refused or failed, or empty if it was done
 * @param result {@link WorktreeRequest#REPAIRED} or {@link WorktreeRequest#UNREPAIRABLE} for a repair that was done,
 *               or empty otherwise
 */
public record WorktreeResponse(String status, String reason, Optional<String> result)
{
	/**
	 * The name of the response's file in the request's response directory.
	 */
	public static final String FILE_NAME = "response.json";
	private static final Set<String> STATUSES = Set.of(WorktreeRequest.DONE, WorktreeRequest.REFUSED,
		WorktreeRequest.FAILED);

	/**
	 * Creates a response.
	 *
	 * @param status {@link WorktreeRequest#DONE}, {@link WorktreeRequest#REFUSED} or {@link WorktreeRequest#FAILED}
	 * @param reason why the request was refused or failed, or empty if it was done
	 * @param result {@link WorktreeRequest#REPAIRED} or {@link WorktreeRequest#UNREPAIRABLE} for a repair that was
	 *               done, or empty otherwise
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code status} is not one of the three
	 */
	public WorktreeResponse
	{
		requireThat(status, "status").isNotNull();
		requireThat(reason, "reason").isNotNull();
		requireThat(result, "result").isNotNull();
		if (!STATUSES.contains(status))
			throw new IllegalArgumentException("Unknown worktree response status: " + status);
	}

	/**
	 * Returns the response of a request that was carried out.
	 *
	 * @return the response
	 */
	static WorktreeResponse done()
	{
		return new WorktreeResponse(WorktreeRequest.DONE, "", Optional.empty());
	}

	/**
	 * Returns the response of a repair that was carried out.
	 *
	 * @param repaired {@code true} if the worktree is linked to the project's repository afterwards
	 * @return the response
	 */
	static WorktreeResponse repaired(boolean repaired)
	{
		String result = WorktreeRequest.UNREPAIRABLE;
		if (repaired)
			result = WorktreeRequest.REPAIRED;
		return new WorktreeResponse(WorktreeRequest.DONE, "", Optional.of(result));
	}

	/**
	 * Returns the response of a request that was refused before it changed anything.
	 *
	 * @param reason why
	 * @return the response
	 */
	static WorktreeResponse refused(String reason)
	{
		return new WorktreeResponse(WorktreeRequest.REFUSED, reason, Optional.empty());
	}

	/**
	 * Returns the response of a request whose operation failed.
	 *
	 * @param reason the failure's message
	 * @return the response
	 */
	static WorktreeResponse failed(String reason)
	{
		return new WorktreeResponse(WorktreeRequest.FAILED, reason, Optional.empty());
	}

	/**
	 * Writes the response into a request's response directory, atomically.
	 *
	 * @param json      the document mapper
	 * @param id        the request's identifier
	 * @param directory the request's response directory
	 * @throws IOException if the response cannot be written
	 */
	void write(JsonMapper json, String id, Path directory) throws IOException
	{
		ObjectNode response = json.createObjectNode().put("format_version", 1).put("id", id).put("status", status).
			put("reason", reason);
		result.ifPresent(value -> response.put("result", value));
		SessionRequestCli.write(json, directory.resolve(FILE_NAME), response);
	}

	/**
	 * Reads the response of a request, once its receiver has written it.
	 *
	 * @param json      the document mapper
	 * @param directory the request's response directory
	 * @return the response, or empty if the receiver has not written it yet
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the response cannot be read or is malformed
	 */
	public static Optional<WorktreeResponse> read(JsonMapper json, Path directory) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(directory, "directory").isNotNull();
		Path file = directory.resolve(FILE_NAME);
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			return Optional.empty();
		JsonNode value;
		try
		{
			value = json.readTree(Files.readAllBytes(file));
		}
		catch (JacksonException e)
		{
			throw new IOException("The worktree response is not valid JSON: " + file, e);
		}
		String status = value.path("status").asString("");
		if (!STATUSES.contains(status))
			throw new IOException("The worktree response holds no known status: " + file);
		Optional<String> result = Optional.empty();
		if (value.path("result").isString())
			result = Optional.of(value.path("result").asString());
		return Optional.of(new WorktreeResponse(status, value.path("reason").asString(""), result));
	}
}
