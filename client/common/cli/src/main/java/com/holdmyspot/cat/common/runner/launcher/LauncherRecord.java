package com.holdmyspot.cat.common.runner.launcher;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The hook data of a session launcher, which CAT's SessionStart hook writes to
 * {@link SessionLauncherChannel#LAUNCHER_FILE} in the launcher's folder before it starts the launcher, and which the
 * launcher reads. Its file is a JSON object whose {@code format_version} is {@value #VERSION}, with the text fields
 * {@code harness}, {@code session_id}, {@code cwd}, {@code source}, {@code harness_home} and {@code cat_version}, the
 * number {@code pid}, the ISO-8601 instant {@code start}, and {@code transcript_path}, a path or {@code null}.
 *
 * @param harness     the identifier of the served harness
 * @param process     the served harness process
 * @param sessionId   the session's identifier, from the hook's input
 * @param cwd         the session's absolute working directory, from the hook's input
 * @param transcript  the session's absolute transcript file, from the hook's input, or empty if it names none
 * @param source      why the session started, from the hook's input, or an empty string if it names nothing
 * @param harnessHome the harness's absolute configuration folder
 * @param catVersion  the version of the CAT that started the launcher
 */
public record LauncherRecord(String harness, ProcessIdentity process, String sessionId, Path cwd,
	Optional<Path> transcript, String source, Path harnessHome, String catVersion)
{
	/**
	 * The version of the file's format.
	 */
	public static final int VERSION = 1;
	/**
	 * The size of the largest file that is read.
	 */
	public static final int MAXIMUM_BYTES = 65_536;
	private static final Set<String> FIELDS = Set.of("format_version", "harness", "pid", "start", "session_id",
		"cwd", "transcript_path", "source", "harness_home", "cat_version");

	/**
	 * Creates a record.
	 *
	 * @param harness     the identifier of the served harness
	 * @param process     the served harness process
	 * @param sessionId   the session's identifier, from the hook's input
	 * @param cwd         the session's absolute working directory, from the hook's input
	 * @param transcript  the session's absolute transcript file, from the hook's input, or empty if it names none
	 * @param source      why the session started, from the hook's input, or an empty string if it names nothing
	 * @param harnessHome the harness's absolute configuration folder
	 * @param catVersion  the version of the CAT that started the launcher
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code harness}, {@code sessionId} or {@code catVersion} are blank, or a
	 *                                  path is relative
	 */
	public LauncherRecord
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(process, "process").isNotNull();
		requireThat(sessionId, "sessionId").isNotBlank();
		requireThat(cwd, "cwd").isAbsolute();
		requireThat(transcript, "transcript").isNotNull();
		transcript.ifPresent(file -> requireThat(file, "transcript").isAbsolute());
		requireThat(source, "source").isNotNull();
		requireThat(harnessHome, "harnessHome").isAbsolute();
		requireThat(catVersion, "catVersion").isNotBlank();
	}

	/**
	 * Writes the record, replacing the file at once so that a reader never sees part of it.
	 *
	 * @param json        the document mapper
	 * @param destination the file
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the file cannot be written
	 */
	public void write(JsonMapper json, Path destination) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(destination, "destination").isNotNull();
		ObjectNode document = json.createObjectNode().
			put("format_version", VERSION).
			put("harness", harness).
			put("pid", process.pid()).
			put("start", process.start().toString()).
			put("session_id", sessionId).
			put("cwd", cwd.toString()).
			put("transcript_path", transcript.map(Path::toString).orElse(null)).
			put("source", source).
			put("harness_home", harnessHome.toString()).
			put("cat_version", catVersion);
		Path temporary = Files.createTempFile(destination.toAbsolutePath().getParent(),
			SessionLauncherChannel.TEMPORARY_PREFIX, ".json");
		try
		{
			Files.writeString(temporary, json.writeValueAsString(document));
			Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Reads a record.
	 *
	 * @param json   the document mapper
	 * @param source the file
	 * @return the record
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the file cannot be read, is larger than {@value #MAXIMUM_BYTES} bytes, or is
	 *                              not a version {@value #VERSION} document with exactly the fields above
	 */
	public static LauncherRecord read(JsonMapper json, Path source) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(source, "source").isNotNull();
		JsonNode document;
		try
		{
			document = json.readTree(BoundedInput.readUtf8(source, MAXIMUM_BYTES, "launcher record"));
		}
		catch (JacksonException e)
		{
			throw new IOException("The launcher record " + source + " is not valid JSON", e);
		}
		if (!document.isObject())
			throw new IOException("The launcher record " + source + " is not a JSON object");
		for (String field : document.propertyNames())
		{
			if (!FIELDS.contains(field))
				throw new IOException("The launcher record " + source + " has an unknown field: " + field);
		}
		JsonNode version = document.path("format_version");
		if (!version.isInt() || version.intValue() != VERSION)
			throw new IOException("The launcher record " + source + " has an unsupported format_version: " + version);
		JsonNode pid = document.path("pid");
		if (!pid.isIntegralNumber())
			throw new IOException("The launcher record " + source + " has a non-integer pid");
		JsonNode transcript = document.path("transcript_path");
		Optional<Path> transcriptPath;
		if (transcript.isNull())
			transcriptPath = Optional.empty();
		else if (transcript.isString())
			transcriptPath = Optional.of(Path.of(transcript.stringValue()));
		else
			throw new IOException("The launcher record " + source + " has a transcript_path that is not text");
		try
		{
			return new LauncherRecord(text(document, "harness", source),
				new ProcessIdentity(pid.longValue(), Instant.parse(text(document, "start", source))),
				text(document, "session_id", source), Path.of(text(document, "cwd", source)), transcriptPath,
				text(document, "source", source), Path.of(text(document, "harness_home", source)),
				text(document, "cat_version", source));
		}
		catch (IllegalArgumentException | DateTimeException e)
		{
			throw new IOException("The launcher record " + source + " is not valid: " + e.getMessage(), e);
		}
	}

	/**
	 * Reads a text field.
	 *
	 * @param document the document
	 * @param field    the field
	 * @param source   the document's file
	 * @return the text
	 * @throws IOException if the field is absent or is not text
	 */
	private static String text(JsonNode document, String field, Path source) throws IOException
	{
		JsonNode value = document.path(field);
		if (!value.isString())
			throw new IOException("The launcher record " + source + " lacks the text field " + field);
		return value.stringValue();
	}
}
