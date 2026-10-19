package com.holdmyspot.cat.codex.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.runner.CodexConnectionConfiguration;
import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.runner.PluginManifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.UnaryOperator;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finds the hooks of a Codex plugin that the user has not approved. Codex runs a plugin's hook only when the user's
 * configuration records an approval of the hook's current declaration, and trials reuse only those approvals.
 */
public final class CodexHookApprovals
{
	/**
	 * The Codex plugin manifest, relative to the plugin directory.
	 */
	private static final Path MANIFEST = Path.of(".codex-plugin/plugin.json");
	/**
	 * The hooks file that Codex reads when the manifest declares no hooks, relative to the plugin directory.
	 */
	private static final Path DEFAULT_HOOKS = Path.of("hooks/hooks.json");
	/**
	 * The largest manifest or hooks file that CAT reads.
	 */
	private static final int MAXIMUM_FILE_BYTES = 1_048_576;

	/**
	 * Prevents construction of utility class instances.
	 */
	private CodexHookApprovals()
	{
	}

	/**
	 * Returns the hooks of a plugin that the user's Codex configuration does not approve. An approval counts whichever
	 * marketplace the user installed the plugin from. This does not compare an approval's hash with the hook's current
	 * declaration, so a hook whose declaration changed after the user approved it is not listed.
	 *
	 * @param plugin the plugin directory
	 * @param configuration the user's Codex home directory
	 * @param json the JSON mapper
	 * @return each unapproved hook as its event and its command in Markdown code, in declaration order
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException if the manifest is missing or invalid, or a file cannot be read
	 */
	public static List<String> unapproved(Path plugin, Path configuration, JsonMapper json) throws IOException
	{
		requireThat(plugin, "plugin").isNotNull();
		requireThat(configuration, "configuration").isNotNull();
		requireThat(json, "json").isNotNull();
		String name = PluginManifest.name(plugin, MANIFEST);
		Map<String, String> approvals = CodexConnectionConfiguration.pluginHookApprovals(
			configuration.resolve("config.toml"), name);

		List<String> unapproved = new ArrayList<>();
		for (HookSource source : sources(plugin::resolve, json))
		{
			for (Entry<String, JsonNode> event : source.declaration().path("hooks").properties())
			{
				JsonNode groups = event.getValue();
				for (int group = 0; group < groups.size(); ++group)
				{
					JsonNode handlers = groups.get(group).path("hooks");
					for (int handler = 0; handler < handlers.size(); ++handler)
					{
						String key = source.key() + ":" + snakeCase(event.getKey()) + ":" + group + ":" + handler;
						if (!approvals.containsKey(key))
							unapproved.add(event.getKey() + " `" + action(handlers.get(handler)) + "`");
					}
				}
			}
		}
		return unapproved;
	}

	/**
	 * Returns the hook declarations that Codex loads from a plugin: each hooks file that the manifest names, each
	 * inline declaration in the manifest, or the default hooks file when the manifest declares no hooks.
	 *
	 * @param resolve returns the file that the plugin holds at a path relative to its directory
	 * @param json the JSON mapper
	 * @return the declarations, in manifest order
	 * @throws IOException if a file cannot be read or the manifest is not valid JSON
	 */
	static List<HookSource> sources(UnaryOperator<Path> resolve, JsonMapper json) throws IOException
	{
		JsonNode hooks = read(resolve.apply(MANIFEST), json).path("hooks");
		List<HookSource> sources = new ArrayList<>();
		if (hooks.isMissingNode() || hooks.isNull())
		{
			addFile(resolve, DEFAULT_HOOKS, json, sources);
			return sources;
		}
		if (hooks.isString())
		{
			addFile(resolve, Path.of(hooks.asString()), json, sources);
			return sources;
		}
		if (hooks.isObject())
		{
			sources.add(new HookSource("plugin.json#hooks[0]", MANIFEST, hooks));
			return sources;
		}
		for (int index = 0; index < hooks.size(); ++index)
		{
			JsonNode entry = hooks.get(index);
			if (entry.isString())
				addFile(resolve, Path.of(entry.asString()), json, sources);
			else
				sources.add(new HookSource("plugin.json#hooks[" + index + "]", MANIFEST, entry));
		}
		return sources;
	}

	/**
	 * Adds the declarations of a hooks file. Codex skips a hooks file that is missing or is not valid JSON, so it runs
	 * none of its hooks and none needs an approval.
	 *
	 * @param resolve returns the file that the plugin holds at a path relative to its directory
	 * @param relative the hooks file, relative to the plugin directory
	 * @param json the JSON mapper
	 * @param sources receives the file's declarations
	 * @throws IOException if the file cannot be read
	 */
	private static void addFile(UnaryOperator<Path> resolve, Path relative, JsonMapper json, List<HookSource> sources)
		throws IOException
	{
		Path file = resolve.apply(relative);
		if (!Files.isRegularFile(file))
			return;
		JsonNode declaration;
		try
		{
			declaration = read(file, json);
		}
		catch (JacksonException _)
		{
			return;
		}
		Path normalized = relative.normalize();
		sources.add(new HookSource(PortablePaths.text(normalized), normalized, declaration));
	}

	/**
	 * Reads a JSON file.
	 *
	 * @param file the file
	 * @param json the JSON mapper
	 * @return the file's JSON
	 * @throws IOException if the file cannot be read
	 * @throws JacksonException if the file is not valid JSON
	 */
	private static JsonNode read(Path file, JsonMapper json) throws IOException
	{
		return json.readTree(BoundedInput.readUtf8(file, MAXIMUM_FILE_BYTES, "Codex plugin file"));
	}

	/**
	 * Returns the label that Codex gives an event in a hook's approval key, such as {@code pre_tool_use} for
	 * {@code PreToolUse}.
	 *
	 * @param event the event's name in a hooks declaration
	 * @return the label
	 */
	private static String snakeCase(String event)
	{
		return event.replaceAll("(?<=[a-z0-9])(?=[A-Z])", "_").toLowerCase(Locale.ROOT);
	}

	/**
	 * Returns what a hook handler runs: its command, or its type if it has no command.
	 *
	 * @param handler the handler's declaration
	 * @return the command or type
	 */
	private static String action(JsonNode handler)
	{
		JsonNode command = handler.path("command");
		if (command.isString())
			return command.asString();
		return handler.path("type").asString("");
	}

	/**
	 * A hook declaration that Codex loads from a plugin.
	 *
	 * @param key the source part of its hooks' approval keys: the hooks file relative to the plugin directory, or
	 *   {@code plugin.json#hooks[<index>]} for an inline declaration
	 * @param file the file that holds the declaration, relative to the plugin directory
	 * @param declaration the declaration, an object whose {@code hooks} maps each event to its matcher groups
	 */
	record HookSource(String key, Path file, JsonNode declaration)
	{
	}
}
