package io.github.cowwoc.cat.common.runner;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes the runner-owned terminal result for one work-execute operation.
 */
public final class WorkExecuteResult
{
	/**
	 * Writes one machine-readable terminal result.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param destination the optional durable transcript destination
	 * @param output the caller-owned output when no durable destination was requested
	 * @param status the closed work-execute terminal status
	 * @param message the concise outcome or blocking reason
	 * @param append whether a durable destination already contains bridge events
	 * @throws IOException if the result cannot be serialized or written
	 */
	public static void write(JsonMapper json, Path destination, Writer output, Status status, String message,
		boolean append) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(output, "output must not be null");
		Objects.requireNonNull(status, "status must not be null");
		if (message == null || message.isBlank())
			throw new IllegalArgumentException("message must not be blank");
		ObjectNode result = json.createObjectNode();
		result.put("type", "work-execute-result");
		result.put("status", status.externalValue());
		result.put("message", message);
		String encoded;
		try
		{
			encoded = json.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(result) +
				System.lineSeparator();
		}
		catch (JacksonException e)
		{
			throw new IOException("Cannot serialize work-execute result", e);
		}
		if (destination == null)
		{
			output.write(encoded);
			output.flush();
			return;
		}
		Path parent = destination.toAbsolutePath().normalize().getParent();
		if (parent == null)
			throw new IOException("Work-execute transcript has no parent directory: " + destination);
		Files.createDirectories(parent);
		if (append)
			Files.writeString(destination, encoded, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
				StandardOpenOption.APPEND);
		else
			Files.writeString(destination, encoded, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING);
	}

	/**
	 * Prevents construction of this static result writer.
	 */
	private WorkExecuteResult()
	{
	}

	/**
	 * Names the runner-owned terminal result emitted after manifest validation.
	 */
	public enum Status
	{
		SUCCESS("SUCCESS"),
		FAILED("FAILED"),
		BLOCKED_PLAN_NOT_MECHANICAL("BLOCKED_PLAN_NOT_MECHANICAL");

		private final String externalValue;

		/**
		 * Creates one serialized terminal status.
		 *
		 * @param externalValue the exact result value
		 */
		Status(String externalValue)
		{
			this.externalValue = externalValue;
		}

		/**
		 * Returns the exact terminal result value.
		 *
		 * @return the serialized status
		 */
		public String externalValue()
		{
			return externalValue;
		}
	}
}
