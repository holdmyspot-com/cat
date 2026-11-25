package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.execution.CheckProgram;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A request to run a check program outside the sandbox of the session that makes it. A check program's own sandbox
 * needs user namespaces and a network setup that a session's sandbox refuses to its commands, so the receiver that
 * serves the session's requests runs the program for it: a nested session's runner under the policy that it applied to
 * the session, and a request server under its trusted readings of the session that it serves.
 * <p>
 * The request travels as a request archive whose name starts with {@link #PREFIX}, in the same {@code requests}
 * directory as a launch, and is answered by a {@link WorktreeResponse} in the same {@code responses} directory, whose
 * result is the program's result, which {@link #encode(JsonMapper, CheckProgram.Result)} writes and
 * {@link #decode(JsonMapper, String)} reads. The request carries what the run needs and nothing that widens it: the
 * staged input, which the requester packed from files that its sandbox lets it read, the program's path inside that
 * input and its arguments, and directories that the program may not see even if the session may. It chooses no
 * executable, environment, limit or policy.
 *
 * @param input     the staged input directory: the requester's own directory, or the receiver's extracted copy
 * @param program   the program's path inside {@code input}
 * @param arguments the program's arguments, with their placeholders filled
 * @param heldOut   the absolute paths that the program may not see
 */
public record CheckProgramRequest(Path input, String program, List<String> arguments, List<Path> heldOut)
{
	/**
	 * The start of the name of a check program request's archive, before its identifier.
	 */
	public static final String PREFIX = "check-program-";
	private static final int FORMAT_VERSION = 1;
	private static final String VERSION_FIELD = "format_version";
	private static final String OUTCOME_FIELD = "outcome";
	private static final String OBSERVED_FIELD = "observed";
	private static final String ELAPSED_FIELD = "elapsed_nanos";

	/**
	 * Creates a request.
	 *
	 * @param input     the staged input directory
	 * @param program   the program's path inside {@code input}
	 * @param arguments the program's arguments
	 * @param heldOut   the absolute paths that the program may not see
	 * @throws NullPointerException     if any of the arguments or their elements are null
	 * @throws IllegalArgumentException if a held-out path is not absolute
	 */
	public CheckProgramRequest
	{
		requireThat(input, "input").isNotNull();
		requireThat(program, "program").isNotNull();
		arguments = List.copyOf(arguments);
		heldOut = List.copyOf(heldOut);
		requireThat(heldOut.stream().allMatch(Path::isAbsolute), "absoluteHeldOutPaths").isTrue();
	}

	/**
	 * Publishes the request into a requests directory under a new identifier.
	 *
	 * @param json     the document mapper
	 * @param requests the requests directory
	 * @return the request's identifier, which names its response directory
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the input cannot be packed or the archive cannot be written
	 */
	public String publish(JsonMapper json, Path requests) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(requests, "requests").isNotNull();
		String id = PREFIX + UUID.randomUUID();
		RequestArchive.packCheckProgram(json, requests.resolve(id + RequestArchive.EXTENSION), this,
			RequestLimits.IN_NESTED_SESSION);
		return id;
	}

	/**
	 * Reads the request that an extracted archive holds.
	 *
	 * @param json   the document mapper
	 * @param inputs the private directory that holds the extracted archive
	 * @return the request, whose input is the extracted copy
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the archive or its manifest is not a valid check program request
	 */
	public static CheckProgramRequest read(JsonMapper json, Path inputs) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(inputs, "inputs").isNotNull();
		return RequestArchive.readCheckProgram(json, inputs);
	}

	/**
	 * Writes a program's result as the result of the answer to a request.
	 *
	 * @param json   the document mapper
	 * @param result the result
	 * @return the document
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static String encode(JsonMapper json, CheckProgram.Result result)
	{
		requireThat(json, "json").isNotNull();
		requireThat(result, "result").isNotNull();
		ObjectNode document = json.createObjectNode().put(VERSION_FIELD, FORMAT_VERSION).
			put(OUTCOME_FIELD, result.outcome().name()).put(OBSERVED_FIELD, result.observed()).
			put(ELAPSED_FIELD, result.elapsed().toNanos());
		return document.toString();
	}

	/**
	 * Reads the result that {@link #encode(JsonMapper, CheckProgram.Result)} wrote.
	 *
	 * @param json     the document mapper
	 * @param document the document
	 * @return the result
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the document is not valid JSON, has another format version or lacks a field
	 */
	public static CheckProgram.Result decode(JsonMapper json, String document) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(document, "document").isNotNull();
		try
		{
			JsonNode value = json.readTree(document);
			JsonNode version = value.path(VERSION_FIELD);
			if (!version.isInt() || version.intValue() != FORMAT_VERSION)
				throw new IOException("The check program result's " + VERSION_FIELD + " must be " + FORMAT_VERSION);
			if (!value.path(OUTCOME_FIELD).isString() || !value.path(OBSERVED_FIELD).isString() ||
				!value.path(ELAPSED_FIELD).isIntegralNumber())
			{
				throw new IOException("The check program result must state " + OUTCOME_FIELD + ", " + OBSERVED_FIELD +
					" and " + ELAPSED_FIELD);
			}
			return new CheckProgram.Result(CheckProgram.Outcome.valueOf(value.path(OUTCOME_FIELD).asString()),
				value.path(OBSERVED_FIELD).asString(), Duration.ofNanos(value.path(ELAPSED_FIELD).longValue()));
		}
		catch (JacksonException | IllegalArgumentException e)
		{
			throw new IOException("The check program result is malformed: " + e.getMessage(), e);
		}
	}
}
