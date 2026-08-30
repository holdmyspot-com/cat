package io.github.cowwoc.cat.claude;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.ToolScope;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deletes CAT rule-context files after Claude ends a session.
 */
public final class ClaudeSessionEndHook
{
	private static final int MAXIMUM_INPUT_BYTES = 1_048_576;

	/**
	 * Runs the native SessionEnd hook.
	 *
	 * @param args a non-null empty command-line argument array
	 * @throws NullPointerException if {@code args} is null
	 */
	public static void main(String[] args)
	{
		requireThat(args, "args").isNotNull();
		run(args, System.in, System.err, System.getenv(), Path.of(System.getProperty("user.dir")));
	}

	/**
	 * Processes one Claude SessionEnd event and deletes its owned context files.
	 *
	 * @param args a non-null empty command-line argument array
	 * @param input non-null native hook input
	 * @param error non-null diagnostic output
	 * @param environment a non-null process environment
	 * @param workingDirectory a non-null process working directory
	 * @throws NullPointerException if any argument is null
	 */
	public static void run(String[] args, InputStream input, PrintStream error, Map<String, String> environment,
		Path workingDirectory)
	{
		requireThat(args, "args").isNotNull();
		requireThat(input, "input").isNotNull();
		requireThat(error, "error").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(workingDirectory, "workingDirectory").isNotNull();
		try (ClaudeHookScope scope = new ClaudeHookScope(environment))
		{
			if (args.length != 0)
				throw new IllegalArgumentException("SessionEnd accepts no arguments");
			JsonNode event = event(scope.json(), input);
			Path projectDir = scope.projectDir(event, workingDirectory);
			try (ToolScope toolScope = ToolScope.forWorkingDirectory(projectDir))
			{
				ClaudeSessionContext.delete(toolScope.catWork(), event);
			}
		}
		catch (IOException | IllegalArgumentException | JacksonException e)
		{
			error.println("CAT SessionEnd: " + e.getMessage());
		}
	}

	/**
	 * Parses a bounded native hook event.
	 *
	 * @param json the hook JSON mapper
	 * @param input the native hook input
	 * @return the parsed event
	 * @throws IOException if the input is too large or not an object
	 */
	private static JsonNode event(JsonMapper json, InputStream input) throws IOException
	{
		assert json != null;
		assert input != null;
		byte[] bytes = input.readNBytes(MAXIMUM_INPUT_BYTES + 1);
		if (bytes.length > MAXIMUM_INPUT_BYTES)
			throw new IOException("SessionEnd input is too large");
		JsonNode event = json.createObjectNode();
		if (bytes.length != 0)
			event = json.readTree(bytes);
		if (event == null || !event.isObject())
			throw new IOException("SessionEnd input must be a JSON object");
		return event;
	}

	/**
	 * Prevents instantiation.
	 */
	private ClaudeSessionEndHook()
	{
	}
}
