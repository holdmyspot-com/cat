package io.github.cowwoc.cat.common.optimizer.workspace;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.cowwoc.cat.common.PortablePaths;
import io.github.cowwoc.cat.common.optimizer.contract.Dependent;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Removes a dependent of the target prompt file from the content of the file that holds it.
 * <p>
 * <b>Thread Safety:</b> This class is stateless and thread-safe.
 */
public final class DependentRemoval
{
	/**
	 * Prevents instantiation.
	 */
	private DependentRemoval()
	{
	}

	/**
	 * Removes a dependent from its file's content.
	 *
	 * @param dependent the dependent
	 * @param content the content of the file that holds it
	 * @param json reads and writes a hook configuration
	 * @return the file's content without the dependent, or empty if the whole file is removed
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if the file does not hold the dependent: a span that is not UTF-8 text or that
	 *   does not occur exactly once, or a hook registration in a file that is not a hook configuration or that
	 *   registers no hook with the event and command
	 */
	public static Optional<byte[]> apply(Dependent dependent, byte[] content, JsonMapper json)
	{
		requireThat(dependent, "dependent").isNotNull();
		requireThat(content, "content").isNotNull();
		requireThat(json, "json").isNotNull();
		return switch (dependent.removal())
		{
			case Dependent.WholeFile _ -> Optional.empty();
			case Dependent.Span span -> Optional.of(deleteSpan(dependent, span, content));
			case Dependent.HookRegistration hook -> Optional.of(unregister(dependent, hook, content, json));
		};
	}

	/**
	 * Lists the dependents that a directory does not hold, without changing it.
	 *
	 * @param dependents the dependents, each relative to {@code directory}
	 * @param directory the directory that holds their files
	 * @param json reads hook configurations
	 * @return one problem per dependent that the directory does not hold, in dependent order
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException if a file cannot be read
	 */
	public static List<String> problems(List<Dependent> dependents, Path directory, JsonMapper json)
		throws IOException
	{
		requireThat(dependents, "dependents").isNotNull();
		requireThat(directory, "directory").isNotNull();
		requireThat(json, "json").isNotNull();
		List<String> problems = new ArrayList<>();
		for (Dependent dependent : dependents)
		{
			Path file = directory.resolve(dependent.path());
			if (!Files.isRegularFile(file, NOFOLLOW_LINKS))
			{
				problems.add(describe(dependent) + ", which a dependent names, is not a regular file");
				continue;
			}
			try
			{
				apply(dependent, Files.readAllBytes(file), json);
			}
			catch (IllegalArgumentException e)
			{
				problems.add(e.getMessage());
			}
		}
		return problems;
	}

	/**
	 * Removes dependents from a directory.
	 *
	 * @param dependents the dependents, each relative to {@code directory}
	 * @param directory the directory that holds their files
	 * @param json reads and writes hook configurations
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException if a file cannot be read or written, or if the directory does not hold a dependent
	 */
	static void removeAll(List<Dependent> dependents, Path directory, JsonMapper json) throws IOException
	{
		List<String> problems = problems(dependents, directory, json);
		if (!problems.isEmpty())
		{
			throw new WorkspaceException(directory + " does not hold the dependents that the contract lists: " +
				String.join("; ", problems) + ". Run revise so that the contract lists each dependent as it is.");
		}
		for (Dependent dependent : dependents)
		{
			Path file = directory.resolve(dependent.path());
			Optional<byte[]> content = apply(dependent, Files.readAllBytes(file), json);
			if (content.isEmpty())
				Files.delete(file);
			else
				Files.write(file, content.get());
		}
	}

	/**
	 * Deletes a span from a file's content.
	 *
	 * @param dependent the dependent
	 * @param span the span
	 * @param content the file's content
	 * @return the content without the span
	 * @throws IllegalArgumentException if the content is not UTF-8 text or the span does not occur in it exactly once
	 */
	private static byte[] deleteSpan(Dependent dependent, Dependent.Span span, byte[] content)
	{
		String text = decode(dependent, content);
		String spanText = span.text();
		int first = text.indexOf(spanText);
		int occurrences = 0;
		for (int index = first; index >= 0; index = text.indexOf(spanText, index + 1))
			++occurrences;
		if (occurrences != 1)
		{
			throw new IllegalArgumentException("The span of the dependent in " + describe(dependent) + " occurs " +
				occurrences + " times; a span must occur exactly once. Span: " + spanText);
		}
		return (text.substring(0, first) + text.substring(first + spanText.length())).getBytes(UTF_8);
	}

	/**
	 * Unregisters a hook from a hook configuration.
	 *
	 * @param dependent the dependent
	 * @param hook the hook registration
	 * @param content the configuration's content
	 * @param json reads and writes the configuration
	 * @return the configuration without the hook
	 * @throws IllegalArgumentException if the content is not a hook configuration or registers no hook with the
	 *   event and command
	 */
	private static byte[] unregister(Dependent dependent, Dependent.HookRegistration hook, byte[] content,
		JsonMapper json)
	{
		JsonNode root;
		try
		{
			root = json.readTree(decode(dependent, content));
		}
		catch (JacksonException e)
		{
			throw new IllegalArgumentException(describe(dependent) + " is not a hook configuration: " +
				e.getOriginalMessage(), e);
		}
		if (root == null || !(root.get("hooks") instanceof ObjectNode hooks))
		{
			throw new IllegalArgumentException(describe(dependent) + " is not a hook configuration: it has no " +
				"\"hooks\" object");
		}
		int removed = 0;
		if (hooks.get(hook.event()) instanceof ArrayNode groups)
		{
			for (int groupIndex = groups.size() - 1; groupIndex >= 0; --groupIndex)
			{
				if (!(groups.get(groupIndex).get("hooks") instanceof ArrayNode entries))
					continue;
				for (int entryIndex = entries.size() - 1; entryIndex >= 0; --entryIndex)
				{
					JsonNode command = entries.get(entryIndex).get("command");
					if (command != null && command.isString() && command.asString().equals(hook.command()))
					{
						entries.remove(entryIndex);
						++removed;
					}
				}
				if (entries.isEmpty())
					groups.remove(groupIndex);
			}
			if (groups.isEmpty())
				hooks.remove(hook.event());
		}
		if (removed == 0)
		{
			throw new IllegalArgumentException(describe(dependent) + " registers no " + hook.event() + " hook with " +
				"the command " + hook.command());
		}
		return json.writeValueAsString(root).getBytes(UTF_8);
	}

	/**
	 * Decodes a file's content as UTF-8 text.
	 *
	 * @param dependent the dependent that the file holds
	 * @param content the content
	 * @return the text
	 * @throws IllegalArgumentException if the content is not UTF-8 text
	 */
	private static String decode(Dependent dependent, byte[] content)
	{
		try
		{
			return UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).
				onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content)).toString();
		}
		catch (CharacterCodingException e)
		{
			throw new IllegalArgumentException(describe(dependent) + " is not UTF-8 text", e);
		}
	}

	/**
	 * Describes where a dependent lies, for a message.
	 *
	 * @param dependent the dependent
	 * @return the description
	 */
	static String describe(Dependent dependent)
	{
		Path path = dependent.path();
		return switch (dependent.location())
		{
			case PROJECT -> "the project file " + PortablePaths.text(path);
			case PLUGIN -> "the plugin file " + PortablePaths.text(path);
		};
	}
}
