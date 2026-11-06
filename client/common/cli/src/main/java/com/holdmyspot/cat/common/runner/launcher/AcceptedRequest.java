package com.holdmyspot.cat.common.runner.launcher;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The document that a session launcher puts in a request's directory when it accepts the request. Its sender waits
 * for it to appear; a launcher's start-up sweep reads which launcher accepted the request.
 *
 * @param launcher the name of the accepting launcher's directory
 * @param accepted the instant at which it accepted the request
 */
public record AcceptedRequest(String launcher, Instant accepted)
{
	/**
	 * Creates the document.
	 *
	 * @param launcher the name of the accepting launcher's directory
	 * @param accepted the instant at which it accepted the request
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code launcher} is blank
	 */
	public AcceptedRequest
	{
		requireThat(launcher, "launcher").isNotBlank();
		requireThat(accepted, "accepted").isNotNull();
	}

	/**
	 * Writes the document.
	 *
	 * @param json        the document mapper
	 * @param destination the new file
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the file cannot be written
	 */
	public void write(JsonMapper json, Path destination) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(destination, "destination").isNotNull();
		Files.writeString(destination, json.writeValueAsString(json.createObjectNode().put("launcher", launcher).
			put("accepted", accepted.toString())));
	}

	/**
	 * Reads the document.
	 *
	 * @param json   the document mapper
	 * @param source the file
	 * @return the document
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the file cannot be read or is not such a document
	 */
	public static AcceptedRequest read(JsonMapper json, Path source) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(source, "source").isNotNull();
		try
		{
			JsonNode document = json.readTree(Files.readString(source));
			return new AcceptedRequest(document.path("launcher").asString(),
				Instant.parse(document.path("accepted").asString()));
		}
		catch (JacksonException | DateTimeException | IllegalArgumentException e)
		{
			throw new IOException("Malformed acceptance: " + source, e);
		}
	}
}
