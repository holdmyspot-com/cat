package io.github.cowwoc.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents the model, effort, and developer contract declared by one CAT Codex custom agent.
 *
 * @param model the selected Codex model
 * @param effort the selected reasoning effort
 * @param developerInstructions the complete developer contract
 */
record CodexAgentDefinition(String model, String effort, String developerInstructions)
{
	private static final Pattern MODEL = field("model");
	private static final Pattern EFFORT = field("model_reasoning_effort");
	private static final Pattern INSTRUCTIONS = Pattern.compile(
		"(?ms)^developer_instructions\\s*=\\s*'''\\R(.*?)\\R'''\\s*$");

	/**
	 * Loads one named CAT custom agent from the selected artifact.
	 *
	 * @param artifact the complete Codex artifact
	 * @param agent the CAT agent filename without the {@code .toml} suffix
	 * @return the parsed agent definition
	 * @throws IOException if the named agent is absent or malformed
	 */
	static CodexAgentDefinition load(Path artifact, String agent) throws IOException
	{
		requireThat(artifact, "artifact").isNotNull();
		requireThat(agent, "agent").isNotBlank();
		if (!agent.matches("[a-z0-9]+(?:-[a-z0-9]+)*"))
			throw new IOException("Invalid CAT Codex agent name: " + agent);
		Path directory = artifact.resolve("agents/codex");
		Path definition = directory.resolve(agent + ".toml").normalize();
		if (!definition.startsWith(directory) || !Files.isRegularFile(definition, NOFOLLOW_LINKS))
			throw new IOException("CAT Codex agent does not exist: " + agent);
		String content = Files.readString(definition);
		return new CodexAgentDefinition(value(MODEL, content, definition), value(EFFORT, content, definition),
			value(INSTRUCTIONS, content, definition));
	}

	/**
	 * Creates the field pattern for a quoted scalar value.
	 *
	 * @param name the TOML field name
	 * @return the matching pattern
	 */
	private static Pattern field(String name)
	{
		assert name != null;
		return Pattern.compile("(?m)^" + name + "\\s*=\\s*\\\"([^\\\"]+)\\\"\\s*$");
	}

	/**
	 * Returns one required TOML value.
	 *
	 * @param pattern the field pattern
	 * @param content the definition content
	 * @param definition the source file
	 * @return the captured non-blank value
	 * @throws IOException if the value is absent or blank
	 */
	private static String value(Pattern pattern, String content, Path definition) throws IOException
	{
		assert pattern != null;
		assert content != null;
		assert definition != null;
		Matcher matcher = pattern.matcher(content);
		if (!matcher.find() || matcher.group(1).isBlank())
			throw new IOException("CAT Codex agent has no required field in " + definition);
		return matcher.group(1);
	}
}
