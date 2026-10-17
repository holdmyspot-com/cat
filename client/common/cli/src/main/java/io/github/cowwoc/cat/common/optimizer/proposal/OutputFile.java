package io.github.cowwoc.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.InvalidProposalException;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The file in a session's project to which the session writes its JSON output, in place of its final response.
 * <p>
 * A large output in a final response must be sent again whole to correct any part of it, and it fills the session's
 * context; a file lets the session edit only the parts that a correction concerns and read back only the parts that
 * it needs.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class OutputFile
{
	/**
	 * The name of the output file when the session's project does not already hold a file by that name.
	 */
	public static final String NAME = "cat-output.json";
	/**
	 * The start of the name of the output file when the project already holds {@link #NAME}.
	 */
	private static final String STEM = "cat-output-";
	private final JsonMapper json;

	/**
	 * Creates an output file reader.
	 *
	 * @param json the operation-owned JSON mapper
	 * @throws NullPointerException if {@code json} is null
	 */
	public OutputFile(JsonMapper json)
	{
		requireThat(json, "json").isNotNull();
		this.json = json;
	}

	/**
	 * Returns the name of a session's output file: {@link #NAME}, or the first of {@code cat-output-2.json},
	 * {@code cat-output-3.json} and so on that the project does not hold, so that the session never overwrites a
	 * project file and CAT never reads one as the output.
	 *
	 * @param project the session's project as it was before the session started
	 * @return the file name, relative to the project
	 * @throws NullPointerException if {@code project} is null
	 */
	public static String name(Path project)
	{
		requireThat(project, "project").isNotNull();
		String name = NAME;
		for (int suffix = 2; Files.exists(project.resolve(name), LinkOption.NOFOLLOW_LINKS); ++suffix)
			name = STEM + suffix + ".json";
		return name;
	}

	/**
	 * Reads the JSON object that a session wrote to its output file.
	 *
	 * @param result the session's result
	 * @return the object
	 * @throws NullPointerException     if {@code result} is null
	 * @throws InvalidProposalException if the session's project holds no regular file by the output file's name, or
	 *                                  the file is not UTF-8 text that is exactly one JSON object without repeated
	 *                                  property names. The problem names the file and, for invalid JSON, the line and
	 *                                  column at which it stops parsing.
	 * @throws UncheckedIOException     if the file cannot be read
	 */
	public ObjectNode read(SessionResult.Completed result) throws InvalidProposalException
	{
		requireThat(result, "result").isNotNull();
		return read(result.effects(), name(result.initialProject()));
	}

	/**
	 * Reads the object that a session wrote to a file of its project.
	 *
	 * @param project the session's project
	 * @param name    the file's name, relative to {@code project}
	 * @return the object
	 * @throws InvalidProposalException if the project holds no regular file of that name, or the file is not UTF-8
	 *                                  text that is exactly one JSON object without repeated property names
	 * @throws UncheckedIOException     if the file cannot be read
	 */
	ObjectNode read(Path project, String name) throws InvalidProposalException
	{
		Path file = project.resolve(name);
		// A link could make CAT read a file outside the session's project
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
		{
			throw new InvalidProposalException(List.of("$: the working directory holds no regular file named `" +
				name + "`. Write the output to that file."));
		}
		String text;
		try
		{
			text = Files.readString(file, StandardCharsets.UTF_8);
		}
		catch (CharacterCodingException _)
		{
			throw new InvalidProposalException(List.of("$: `" + name + "` is not UTF-8 text"));
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
		return parse(text, "`" + name + "`");
	}

	/**
	 * Parses text that must be exactly one JSON object.
	 *
	 * @param text        the text
	 * @param description what holds the text, for the problem
	 * @return the object
	 * @throws InvalidProposalException if the text is not exactly one JSON object without repeated property names. The
	 *                                  problem states the line and column at which the text stops parsing.
	 */
	ObjectNode parse(String text, String description) throws InvalidProposalException
	{
		JsonNode node;
		try
		{
			node = json.readerFor(JsonNode.class).
				with(StreamReadFeature.STRICT_DUPLICATE_DETECTION).
				with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).
				readValue(text);
		}
		catch (JacksonException e)
		{
			String problem = "$: " + description + " is not one JSON object: " + e.getOriginalMessage();
			TokenStreamLocation location = e.getLocation();
			if (location != null && location.getLineNr() > 0)
				problem += ", at line " + location.getLineNr() + ", column " + location.getColumnNr();
			throw new InvalidProposalException(List.of(problem));
		}
		if (node == null || node.isMissingNode())
			throw new InvalidProposalException(List.of("$: " + description + " is empty"));
		if (node instanceof ObjectNode object)
			return object;
		throw new InvalidProposalException(List.of("$: " + description + " holds a JSON " +
			node.getNodeType().name().toLowerCase(Locale.ROOT) + " rather than one object"));
	}
}
