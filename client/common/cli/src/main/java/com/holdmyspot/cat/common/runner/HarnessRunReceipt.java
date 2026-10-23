package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileReplacer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Writes the retained evidence locations that an independent evaluator needs after a successful harness run.
 */
public final class HarnessRunReceipt
{
	private static final String SESSION_TRANSCRIPTS = "session_transcripts";


	/**
	 * Writes one receipt after verifying that both referenced artifacts are retained.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param replacer replaces the receipt destination
	 * @param destination the receipt destination
	 * @param run the retained harness run
	 * @param rawResult the durable harness response
	 * @param sessionTranscripts the copies of the harness's native session transcripts, which the receipt lists only if
	 *   there are any
	 * @throws IOException if retained evidence cannot be verified or written
	 */
	public static void write(JsonMapper json, FileReplacer replacer, Path destination, HarnessRun run, Path rawResult,
		List<Path> sessionTranscripts) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		requireThat(destination, "destination").isNotNull();
		requireThat(run, "run").isNotNull();
		requireThat(rawResult, "rawResult").isNotNull();
		HarnessEvaluationResult result = HarnessEvaluationResult.retained(run.directory().resolve("project"), rawResult,
			sessionTranscripts);
		Path parent = destination.toAbsolutePath().normalize().getParent();
		if (parent == null)
			throw new IOException("Receipt has no parent directory: " + destination);
		Files.createDirectories(parent);
		ObjectNode receipt = json.createObjectNode();
		receipt.put("retained_project", result.retainedProject().toString());
		receipt.put("raw_result", result.rawResult().toString());
		if (!result.sessionTranscripts().isEmpty())
		{
			ArrayNode copies = receipt.putArray(SESSION_TRANSCRIPTS);
			for (Path transcript : result.sessionTranscripts())
				copies.add(transcript.toString());
		}
		Path temporary = Files.createTempFile(parent, ".harness-receipt-", ".json");
		try
		{
			json.writeValue(temporary.toFile(), receipt);
			replacer.replace(temporary, destination);
		}
		catch (IOException e)
		{
			Files.deleteIfExists(temporary);
			throw e;
		}
	}

	/**
	 * Reads the retained evidence locations from one completed harness execution.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param source the receipt path
	 * @return the validated retained evidence result
	 * @throws IOException if the receipt is malformed or names unavailable evidence
	 */
	public static HarnessEvaluationResult read(JsonMapper json, Path source) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(source, "source").isNotNull();
		if (!Files.isRegularFile(source))
			throw new IOException("Harness receipt is not a regular file: " + source);
		return fromReceipt(json.readTree(source.toFile()), source);
	}

	/**
	 * Converts one already-parsed harness receipt to its typed retained evidence.
	 *
	 * <p>
	 * Callers that already own the JSON parsing boundary use this method so the receipt schema remains authoritative in
	 * this class rather than being reimplemented by an evaluator.
	 *
	 * @param value the parsed receipt value
	 * @param source the receipt source used in diagnostics
	 * @return the validated retained evidence result
	 * @throws IOException if the receipt is malformed or names unavailable evidence
	 */
	public static HarnessEvaluationResult fromReceipt(JsonNode value, Path source) throws IOException
	{
		requireThat(value, "value").isNotNull();
		requireThat(source, "source").isNotNull();
		if (!(value instanceof ObjectNode receipt))
			throw new IOException("Harness receipt is not a JSON object: " + source);
		Set<String> names = new HashSet<>(receipt.propertyNames());
		Set<String> required = Set.of("retained_project", "raw_result");
		Set<String> unsupported = new HashSet<>(names);
		unsupported.removeAll(required);
		unsupported.remove(SESSION_TRANSCRIPTS);
		if (!names.containsAll(required) || !unsupported.isEmpty())
			throw new IOException("Harness receipt has unsupported fields: " + source);
		List<Path> sessionTranscripts = new ArrayList<>();
		for (JsonNode element : receipt.path(SESSION_TRANSCRIPTS))
			sessionTranscripts.add(path(element.asString(), SESSION_TRANSCRIPTS, source));
		return HarnessEvaluationResult.retained(path(receipt, "retained_project", source),
			path(receipt, "raw_result", source), sessionTranscripts);
	}

	/**
	 * Reads one required absolute artifact path from a receipt.
	 *
	 * @param receipt the parsed receipt
	 * @param field the required field name
	 * @param source the receipt source used in diagnostics
	 * @return the parsed path
	 * @throws IOException if the field is blank or invalid
	 */
	private static Path path(ObjectNode receipt, String field, Path source) throws IOException
	{
		return path(receipt.path(field).asString(), field, source);
	}

	/**
	 * Parses one required absolute artifact path from a receipt value.
	 *
	 * @param value the text of the path
	 * @param field the field name used in diagnostics
	 * @param source the receipt source used in diagnostics
	 * @return the parsed path
	 * @throws IOException if the value is blank or invalid
	 */
	private static Path path(String value, String field, Path source) throws IOException
	{
		if (value.isBlank())
			throw new IOException("Harness receipt has no " + field + ": " + source);
		try
		{
			Path result = Path.of(value);
			if (!result.isAbsolute())
				throw new IOException("Harness receipt has a relative " + field + ": " + source);
			return result;
		}
		catch (java.nio.file.InvalidPathException e)
		{
			throw new IOException("Harness receipt has an invalid " + field + ": " + source, e);
		}
	}

	/**
	 * Prevents utility-class construction.
	 */
	private HarnessRunReceipt()
	{
	}
}
