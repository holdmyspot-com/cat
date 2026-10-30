package com.holdmyspot.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents the model and effort declared by one CAT Claude custom agent.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param name the CAT agent name
 * @param model the selected Claude model
 * @param effort the selected reasoning effort
 */
public record ClaudeAgentDefinition(String name, String model, String effort)
{
	private static final Pattern FRONTMATTER = Pattern.compile("(?s)\\A---\\R(.*?)\\R---");
	private static final Pattern MODEL = field("model");
	private static final Pattern EFFORT = field("effort");

	/**
	 * Loads one named CAT Claude custom agent from the selected artifact.
	 *
	 * @param artifact the complete Claude artifact
	 * @param agent the CAT agent filename without the {@code .md} suffix
	 * @return the parsed agent definition
	 * @throws IOException if the named agent is absent or malformed
	 */
	public static ClaudeAgentDefinition load(Path artifact, String agent) throws IOException
	{
		requireThat(artifact, "artifact").isNotNull();
		requireThat(agent, "agent").isNotBlank();
		if (!agent.matches("[a-z0-9]+(?:-[a-z0-9]+)*"))
			throw new IOException("Invalid CAT Claude agent name: " + agent);
		Path directory = artifact.resolve("agents/claude");
		Path definition = directory.resolve(agent + ".md").normalize();
		if (!definition.startsWith(directory) || !Files.isRegularFile(definition, NOFOLLOW_LINKS))
			throw new IOException("CAT Claude agent does not exist: " + agent);
		Matcher frontmatter = FRONTMATTER.matcher(Files.readString(definition));
		if (!frontmatter.find())
			throw new IOException("CAT Claude agent has no frontmatter: " + definition);
		String metadata = frontmatter.group(1);
		return new ClaudeAgentDefinition(agent, value(MODEL, "model", metadata, definition),
			value(EFFORT, "effort", metadata, definition));
	}

	/**
	 * Creates the field pattern for a quoted or unquoted YAML scalar.
	 *
	 * @param name the YAML field name
	 * @return the field pattern
	 */
	private static Pattern field(String name)
	{
		assert name != null;
		return Pattern.compile("(?m)^" + name + ":\\s*[\\\"']?([^\\s\\\"']+)[\\\"']?\\s*$");
	}

	/**
	 * Returns one required non-blank metadata value.
	 *
	 * @param pattern the field pattern
	 * @param name the field's name, for the error message
	 * @param content the frontmatter content
	 * @param definition the source file
	 * @return the field value
	 * @throws IOException if the value is absent or blank
	 */
	private static String value(Pattern pattern, String name, String content, Path definition)
		throws IOException
	{
		assert pattern != null;
		assert content != null;
		assert definition != null;
		Matcher matcher = pattern.matcher(content);
		if (!matcher.find() || matcher.group(1).isBlank())
			throw new IOException("CAT Claude agent has no " + name + " in " + definition);
		return matcher.group(1);
	}
}
