package io.github.cowwoc.cat.common.evaluation;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Set;

/**
 * Extracts the compact executor response that an independent validator reads beside raw protocol evidence.
 */
public final class ValidatorEvidence
{
	private static final String RAW_RESULT_NAME = "executor-output.jsonl";

	/**
	 * Prevents construction of this static utility.
	 */
	private ValidatorEvidence()
	{
	}

	/**
	 * Creates an isolated validator workspace from immutable executor evidence.
	 *
	 * <p>
	 * The validator can install dependencies and create findings in this copy, while the retained executor project
	 * remains unchanged for provenance and later diagnosis.
	 *
	 * @param json the native-event JSON codec
	 * @param retainedProject the completed executor project
	 * @param rawResult the retained executor protocol result
	 * @param workspace the initially absent validator workspace
	 * @param excludedDirectories generated directory names to omit from the validator copy
	 * @param finalResponseName the response filename inside the validator workspace
	 * @return the prepared validator workspace paths
	 * @throws IOException if an input is invalid or the evidence cannot be copied
	 */
	public static PreparedWorkspace prepare(JsonMapper json, Path retainedProject, Path rawResult, Path workspace,
		Set<String> excludedDirectories, String finalResponseName) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(retainedProject, "retainedProject must not be null");
		Objects.requireNonNull(rawResult, "rawResult must not be null");
		Objects.requireNonNull(workspace, "workspace must not be null");
		Objects.requireNonNull(excludedDirectories, "excludedDirectories must not be null");
		if (finalResponseName == null || finalResponseName.isBlank() || Path.of(finalResponseName).getNameCount() != 1)
			throw new IllegalArgumentException("finalResponseName must be one filename");
		Path normalizedProject = retainedProject.toAbsolutePath().normalize();
		Path normalizedRawResult = rawResult.toAbsolutePath().normalize();
		Path normalizedWorkspace = workspace.toAbsolutePath().normalize();
		if (Files.isSymbolicLink(normalizedProject) || !Files.isDirectory(normalizedProject, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Retained project is not a real directory: " + normalizedProject);
		if (!Files.isRegularFile(normalizedRawResult, LinkOption.NOFOLLOW_LINKS) ||
			Files.isSymbolicLink(normalizedRawResult))
			throw new IOException("Raw result is not a regular file: " + normalizedRawResult);
		if (Files.exists(normalizedWorkspace, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Validator workspace already exists: " + normalizedWorkspace);
		copyProject(normalizedProject, normalizedWorkspace, excludedDirectories);
		Path copiedRawResult = normalizedWorkspace.resolve(RAW_RESULT_NAME);
		Files.copy(normalizedRawResult, copiedRawResult);
		Path finalResponse = normalizedWorkspace.resolve(finalResponseName);
		Path extractedResponse = null;
		if (extractFinalResponse(json, copiedRawResult, finalResponse))
			extractedResponse = finalResponse;
		return new PreparedWorkspace(normalizedWorkspace, copiedRawResult, extractedResponse);
	}


	/**
	 * Creates one validator workspace and projects its retained evidence paths for evaluator callers.
	 *
	 * @param json the native-event JSON codec
	 * @param retainedProject the completed executor project
	 * @param rawResult the retained executor result
	 * @param workspace the initially absent validator workspace
	 * @param excludedDirectories generated directory names omitted from the copy
	 * @param finalResponseName the response filename inside the validator workspace
	 * @return the prepared workspace description
	 * @throws IOException if evidence cannot be copied or interpreted
	 */
	public static ObjectNode describe(JsonMapper json, Path retainedProject, Path rawResult, Path workspace,
		Set<String> excludedDirectories, String finalResponseName) throws IOException
	{
		PreparedWorkspace prepared = prepare(json, retainedProject, rawResult, workspace, excludedDirectories,
			finalResponseName);
		ObjectNode result = json.createObjectNode();
		result.put("project", prepared.project().toString());
		result.put("raw_result", prepared.rawResult().toString());
		if (prepared.finalResponse() != null)
			result.put("final_response", prepared.finalResponse().toString());
		return result;
	}

	/**
	 * Extracts the latest completed agent message, while retaining the raw event stream as provenance.
	 *
	 * @param json the native-event JSON codec
	 * @param rawResult the retained executor protocol result
	 * @param destination the response artifact to write
	 * @return whether the protocol stream contained a completed agent response
	 * @throws IOException if the evidence is blank or cannot be read or written
	 */
	public static boolean extractFinalResponse(JsonMapper json, Path rawResult, Path destination) throws IOException
	{
		boolean nativeEvents = false;
		String finalResponse = null;
		for (String line : Files.readAllLines(rawResult, StandardCharsets.UTF_8))
		{
			try
			{
				JsonNode envelope = json.readTree(line);
				if (!envelope.path("type").asString().equals("native-event") || !envelope.path("event").isString())
					continue;
				nativeEvents = true;
				JsonNode event = json.readTree(envelope.path("event").asString());
				JsonNode item = event.path("params").path("item");
				if (event.path("method").asString().equals("item/completed") &&
					item.path("type").asString().equals("agentMessage") && item.path("text").isString() &&
					!item.path("text").asString().isBlank())
				{
					finalResponse = item.path("text").asString();
				}
			}
			catch (JacksonException _)
			{
				// Non-event diagnostics remain available in the retained provenance stream.
			}
		}
		if (nativeEvents && finalResponse == null)
			return false;
		if (finalResponse == null)
			finalResponse = Files.readString(rawResult, StandardCharsets.UTF_8);
		if (finalResponse.isBlank())
			throw new IOException("Executor result contains no final response: " + rawResult);
		Files.writeString(destination, finalResponse, StandardCharsets.UTF_8);
		return true;
	}

	/**
	 * Copies only safe source evidence into the independent validator workspace.
	 *
	 * @param retainedProject the completed executor project
	 * @param workspace the initially absent validator workspace
	 * @param excludedDirectories generated directory names excluded from the copy
	 * @throws IOException if the retained project contains an unsupported entry outside excluded generated directories
	 */
	private static void copyProject(Path retainedProject, Path workspace, Set<String> excludedDirectories)
		throws IOException
	{
		Files.walkFileTree(retainedProject, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException
			{
				if (Files.isSymbolicLink(directory))
					throw new IOException("Retained project contains a symbolic-link directory: " + directory);
				Path relative = retainedProject.relativize(directory);
				if (relative.getFileName() != null && excludedDirectories.contains(relative.getFileName().toString()))
					return FileVisitResult.SKIP_SUBTREE;
				Files.createDirectories(workspace.resolve(relative));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
			{
				if (!attributes.isRegularFile() || Files.isSymbolicLink(file))
					throw new IOException("Retained project contains an unsupported entry: " + file);
				Files.copy(file, workspace.resolve(retainedProject.relativize(file)));
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/**
	 * Names evidence prepared for an independent validator.
	 *
	 * @param project the writable validator project copy
	 * @param rawResult the copied executor protocol result
	 * @param finalResponse the extracted final response, or {@code null} when native events contain none
	 */
	public record PreparedWorkspace(Path project, Path rawResult, Path finalResponse)
	{
		/**
		 * Validates the required prepared paths.
		 *
		 * @param project the writable validator project copy
		 * @param rawResult the copied executor protocol result
		 * @param finalResponse the extracted final response, or {@code null} when native events contain none
		 */
		public PreparedWorkspace
		{
			Objects.requireNonNull(project, "project must not be null");
			Objects.requireNonNull(rawResult, "rawResult must not be null");
		}
	}
}
