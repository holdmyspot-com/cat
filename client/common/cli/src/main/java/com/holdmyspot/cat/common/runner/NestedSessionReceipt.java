package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.PortablePaths;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes the retained evidence locations that an independent evaluator needs after a successful nested session.
 */
public final class NestedSessionReceipt
{
	/**
	 * The optional receipt field that lists paths a results copy skipped, relative to the nested session. An evaluator
	 * ignores it.
	 */
	public static final String SKIPPED_COPY_PATHS = "skipped_copy_paths";
	private static final String FORMAT_VERSION = "format_version";
	private static final String PATH_BASE = "path_base";
	private static final String RETAINED_PROJECT = "retained_project";
	private static final String RAW_RESULT = "raw_result";
	private static final String SESSION_TRANSCRIPTS = "session_transcripts";
	private static final String REQUESTED_SESSIONS = "requested_sessions";
	private static final String BASE_NAME = "retained_project_parent";
	private static final String PROJECT_NAME = "project";
	static final String BRIDGE_TRANSCRIPT = "artifacts/bridge-transcript.jsonl";
	/**
	 * The size of the largest nested-session manifest whose authentication record a receipt copies.
	 */
	private static final int MAXIMUM_MANIFEST_BYTES = 1_048_576;


	/**
	 * Writes one portable receipt after verifying that its referenced artifacts are retained in the nested session. The
	 * receipt also records the authentication that the nested session's harness used, as its {@code session.json}
	 * records it.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param replacer replaces files atomically
	 * @param destination the receipt destination
	 * @param nestedSession the retained nested session
	 * @param rawResult the harness response, retained in the nested session if it is outside the nested session
	 * @param sessionTranscripts the copies of the harness's native session transcripts
	 * @throws IOException if retained evidence cannot be verified, copied, or written
	 */
	public static void write(JsonMapper json, FileReplacer replacer, Path destination, NestedSession nestedSession,
		Path rawResult, List<Path> sessionTranscripts) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		requireThat(destination, "destination").isNotNull();
		requireThat(nestedSession, "nestedSession").isNotNull();
		requireThat(rawResult, "rawResult").isNotNull();
		requireThat(sessionTranscripts, "sessionTranscripts").isNotNull();
		Path base = nestedSession.directory().toAbsolutePath().normalize();
		Path project = base.resolve(PROJECT_NAME);
		Path response = rawResult.toAbsolutePath().normalize();
		if (!Files.isRegularFile(response))
			throw new IOException("Harness response is not a regular file: " + response);
		if (!response.startsWith(base))
		{
			Path retained = base.resolve(BRIDGE_TRANSCRIPT);
			Path parent = retained.getParent();
			Files.createDirectories(parent);
			Path temporary = Files.createTempFile(parent, ".bridge-transcript-", ".tmp");
			try
			{
				Files.copy(response, temporary, StandardCopyOption.REPLACE_EXISTING);
				replacer.replace(temporary, retained);
			}
			catch (IOException e)
			{
				Files.deleteIfExists(temporary);
				throw e;
			}
			response = retained;
		}
		response = response.toAbsolutePath().normalize();
		List<Path> transcripts = new ArrayList<>();
		for (Path transcript : sessionTranscripts)
		{
			Path normalized = transcript.toAbsolutePath().normalize();
			if (!normalized.startsWith(base))
				throw new IOException("Harness transcript is outside its nested session: " + transcript);
			transcripts.add(normalized);
		}
		HarnessEvaluationResult result = HarnessEvaluationResult.retained(project, response, transcripts);
		Path parent = destination.toAbsolutePath().normalize().getParent();
		if (parent == null)
			throw new IOException("Receipt has no parent directory: " + destination);
		Files.createDirectories(parent);
		ObjectNode receipt = json.createObjectNode();
		receipt.put(FORMAT_VERSION, 1);
		receipt.put(PATH_BASE, BASE_NAME);
		receipt.put(RETAINED_PROJECT, PortablePaths.text(base.relativize(result.retainedProject())));
		receipt.put(RAW_RESULT, relative(base, result.rawResult(), RAW_RESULT));
		// The receipt names the credential that the session used, as session.json records it, never a value
		Path manifest = base.resolve("session.json");
		if (Files.isRegularFile(manifest))
		{
			JsonNode authentication = json.readTree(BoundedInput.readUtf8(manifest, MAXIMUM_MANIFEST_BYTES,
				"nested session manifest")).path(AuthenticationEvidence.FIELD);
			if (!authentication.isMissingNode())
				receipt.set(AuthenticationEvidence.FIELD, AuthenticationEvidence.fromJson(authentication).toJson(json));
		}
		if (!result.sessionTranscripts().isEmpty())
		{
			ArrayNode copies = receipt.putArray(SESSION_TRANSCRIPTS);
			for (Path transcript : result.sessionTranscripts())
				copies.add(relative(base, transcript, SESSION_TRANSCRIPTS));
		}
		Path responses = SessionRequestAccess.responsesDirectory(nestedSession);
		if (Files.isDirectory(responses))
		{
			ArrayNode sessions = receipt.putArray(REQUESTED_SESSIONS);
			try (DirectoryStream<Path> directories = Files.newDirectoryStream(responses))
			{
				for (Path directory : directories)
				{
					String requestId = directory.getFileName().toString();
					boolean isControlResponse = requestId.startsWith(WorktreeRequest.PREFIX) ||
						requestId.startsWith(DiscardRequest.PREFIX) || requestId.startsWith(RetainedTrialRequest.PREFIX);
					if (!Files.isDirectory(directory) || isControlResponse)
						continue;
					Path responsePath = directory.resolve("response.json").toAbsolutePath().normalize();
					if (!responsePath.startsWith(base) || !Files.isRegularFile(responsePath))
						throw new IOException("Requested-session response is not retained in its nested session: " + responsePath);
					JsonNode resultDocument = json.readTree(responsePath.toFile());
					ObjectNode entry = sessions.addObject();
					for (String field : List.of("id", "harness", "status", "session_id"))
					{
						JsonNode fieldValue = resultDocument.path(field);
						if (!fieldValue.isString())
							throw new IOException("Requested-session response has no text " + field + ": " + responsePath);
						entry.put(field, fieldValue.asString());
					}
					entry.put("response", relative(base, responsePath, "response"));
				}
			}
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
	 * Reads and validates retained evidence using the explicitly supplied retained project directory as its base.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param source the receipt path
	 * @param retainedProjectRoot the copied or original {@code project} directory
	 * @return the validated retained evidence result
	 * @throws IOException if the receipt is malformed or names unavailable evidence
	 */
	public static HarnessEvaluationResult read(JsonMapper json, Path source, Path retainedProjectRoot) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(source, "source").isNotNull();
		requireThat(retainedProjectRoot, "retainedProjectRoot").isNotNull();
		if (!Files.isRegularFile(source))
			throw new IOException("Harness receipt is not a regular file: " + source);
		return fromReceipt(json.readTree(source.toFile()), source, retainedProjectRoot);
	}

	/**
	 * Converts one parsed receipt to its typed retained evidence using an explicit project path.
	 *
	 * @param value the parsed receipt value
	 * @param source the receipt source used in diagnostics
	 * @param retainedProjectRoot the copied or original {@code project} directory
	 * @return the validated retained evidence result
	 * @throws IOException if the receipt is malformed or names unavailable evidence
	 */
	public static HarnessEvaluationResult fromReceipt(JsonNode value, Path source, Path retainedProjectRoot)
		throws IOException
	{
		requireThat(value, "value").isNotNull();
		requireThat(source, "source").isNotNull();
		requireThat(retainedProjectRoot, "retainedProjectRoot").isNotNull();
		if (!retainedProjectRoot.isAbsolute() || !retainedProjectRoot.equals(retainedProjectRoot.normalize()))
			throw new IOException("Retained project root must be absolute and normalized: " + retainedProjectRoot);
		Path project = retainedProjectRoot.toAbsolutePath().normalize();
		if (!project.isAbsolute() || project.getFileName() == null ||
			!PROJECT_NAME.equals(project.getFileName().toString()))
			throw new IOException("Retained project root must be an absolute project directory: " + retainedProjectRoot);
		Path base = project.getParent();
		if (base == null)
			throw new IOException("Retained project root has no parent: " + retainedProjectRoot);
		if (!(value instanceof ObjectNode receipt))
			throw new IOException("Harness receipt is not a JSON object: " + source);
		Set<String> names = new HashSet<>(receipt.propertyNames());
		Set<String> required = Set.of(FORMAT_VERSION, PATH_BASE, RETAINED_PROJECT, RAW_RESULT);
		Set<String> supported = new HashSet<>(required);
		supported.add(SESSION_TRANSCRIPTS);
		supported.add(REQUESTED_SESSIONS);
		supported.add(SKIPPED_COPY_PATHS);
		supported.add(AuthenticationEvidence.FIELD);
		Set<String> unsupported = new HashSet<>(names);
		unsupported.removeAll(supported);
		if (!names.containsAll(required) || !unsupported.isEmpty())
			throw new IOException("Harness receipt has unsupported fields: " + source);
		if (!receipt.path(FORMAT_VERSION).isInt() || receipt.path(FORMAT_VERSION).asInt() != 1 ||
			!receipt.path(PATH_BASE).isString() || !BASE_NAME.equals(receipt.path(PATH_BASE).asString()))
			throw new IOException("Harness receipt has an unsupported format version or path base: " + source);
		Path projectPath = portablePath(receipt.path(RETAINED_PROJECT), RETAINED_PROJECT, base, source);
		if (!PROJECT_NAME.equals(receipt.path(RETAINED_PROJECT).asString()) || !projectPath.equals(project))
			throw new IOException("Harness receipt names a different retained project: " + source);
		validateRequestedSessions(receipt, source, base);
		List<Path> sessionTranscripts = new ArrayList<>();
		if (receipt.has(SESSION_TRANSCRIPTS))
		{
			JsonNode copies = receipt.path(SESSION_TRANSCRIPTS);
			if (!copies.isArray())
				throw new IOException("Harness receipt session_transcripts must be an array: " + source);
			for (JsonNode element : copies)
				sessionTranscripts.add(portablePath(element, SESSION_TRANSCRIPTS, base, source));
		}
		if (receipt.has(SKIPPED_COPY_PATHS))
		{
			JsonNode skipped = receipt.path(SKIPPED_COPY_PATHS);
			if (!skipped.isArray())
				throw new IOException("Harness receipt skipped_copy_paths must be an array: " + source);
			for (JsonNode element : skipped)
				portablePath(element, SKIPPED_COPY_PATHS, base, source);
		}
		Path rawResult = portablePath(receipt.path(RAW_RESULT), RAW_RESULT, base, source);
		return HarnessEvaluationResult.retained(project, rawResult, sessionTranscripts);
	}

	/**
	 * Validates the optional requested-session list without changing the evaluator's evidence scope.
	 *
	 * @param receipt the parsed receipt
	 * @param source the receipt path for diagnostics
	 * @param base the retained session folder
	 * @throws IOException if the list contains malformed or unsupported entries
	 */
	private static void validateRequestedSessions(ObjectNode receipt, Path source, Path base) throws IOException
	{
		if (!receipt.has(REQUESTED_SESSIONS))
			return;
		JsonNode sessions = receipt.path(REQUESTED_SESSIONS);
		if (!sessions.isArray())
			throw new IOException("Harness receipt requested_sessions must be an array: " + source);
		Set<String> fields = Set.of("id", "harness", "status", "session_id", "response");
		Set<String> statuses = Set.of("completed", "failed", "refused", "capacity", "stopped");
		for (JsonNode session : sessions)
		{
			if (!session.isObject() || !session.propertyNames().equals(fields))
				throw new IOException("Harness receipt has unsupported requested-session fields: " + source);
			for (String field : fields)
				if (!session.path(field).isString())
					throw new IOException("Harness receipt requested-session fields must be text: " + source);
			if (!statuses.contains(session.path("status").asString()))
				throw new IOException("Harness receipt has an unsupported requested-session status: " + source);
			portablePath(session.path("response"), "response", base, source);
		}
	}

	/**
	 * Returns a portable path under its base.
	 *
	 * @param base the retained session folder
	 * @param path the absolute path to encode
	 * @param field the field used in diagnostics
	 * @return the portable path
	 * @throws IOException if the path is outside the retained nested session
	 */
	private static String relative(Path base, Path path, String field) throws IOException
	{
		Path normalized = path.toAbsolutePath().normalize();
		if (!normalized.startsWith(base))
			throw new IOException("Harness receipt " + field + " is outside the retained nested session: " + path);
		return PortablePaths.text(base.relativize(normalized));
	}

	/**
	 * Resolves one portable receipt field against its retained session folder.
	 *
	 * @param value the JSON field
	 * @param field the field used in diagnostics
	 * @param base the retained session folder
	 * @param source the receipt path used in diagnostics
	 * @return the resolved path
	 * @throws IOException if the field is not a safe portable relative path
	 */
	private static Path portablePath(JsonNode value, String field, Path base, Path source) throws IOException
	{
		if (!value.isString())
			throw new IOException("Harness receipt has no text " + field + ": " + source);
		String text = value.asString();
		if (text.isBlank() || text.indexOf('\\') >= 0 || text.matches("^[A-Za-z]:.*"))
			throw new IOException("Harness receipt has an invalid " + field + ": " + source);
		String[] names = text.split("/", -1);
		for (String name : names)
			if (name.isEmpty() || name.equals(".") || name.equals(".."))
				throw new IOException("Harness receipt has an invalid " + field + ": " + source);
		try
		{
			Path relative = Path.of(text);
			if (PortablePaths.isRooted(relative) || relative.normalize().startsWith(".."))
				throw new IOException("Harness receipt has a rooted or escaping " + field + ": " + source);
			Path resolved = base.resolve(relative).normalize();
			if (!resolved.startsWith(base))
				throw new IOException("Harness receipt has an escaping " + field + ": " + source);
			return resolved;
		}
		catch (java.nio.file.InvalidPathException e)
		{
			throw new IOException("Harness receipt has an invalid " + field + ": " + source, e);
		}
	}

	/**
	 * Prevents utility-class construction.
	 */
	private NestedSessionReceipt()
	{
	}
}
