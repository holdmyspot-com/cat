package com.holdmyspot.cat.codex.installer;

import static com.holdmyspot.cat.codex.installer.TomlText.lines;
import static com.holdmyspot.cat.codex.installer.TomlText.parse;
import static com.holdmyspot.cat.codex.installer.TomlText.quote;
import static com.holdmyspot.cat.codex.installer.TomlText.tryParse;

import com.holdmyspot.cat.common.BoundedInput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Computes the hashes with which Codex trusts the hooks of a plugin, and adds a hook's trust to, and removes it from,
 * Codex's {@code config.toml}, changing no other part of the file.
 * <p>
 * Codex runs a plugin's hook only while
 * {@code [hooks.state."<plugin>:<hooks file>:<event>:<group>:<handler>"]} holds {@code trusted_hash} equal to the hash
 * that Codex computes for the hook. Codex hashes the hook's normalized identity: a JSON object with sorted keys that
 * holds the event's name in snake case, the matcher group's matcher and the handler as Codex normalizes it, written
 * without whitespace. The normalized command handler states the command that
 * the platform runs, its timeout in seconds (600 when unset), whether it is asynchronous, its status message and its
 * {@code additionalContextLimit} unless that is unset or Codex's default of 2500. The hash is {@code sha256:} and the
 * SHA-256 of that JSON in hexadecimal.
 * <p>
 * The hash covers command handlers of the events {@code PreToolUse}, {@code PostToolUse}, {@code SessionStart} and
 * {@code SubagentStart}. Codex normalizes the other events and handlers differently, so this class gives them no trust
 * and Codex asks the user to review them.
 * <p>
 * The editor changes the text of two forms only:
 * <ul>
 *   <li>a hook without a {@code [hooks.state."<key>"]} table, to which it appends the table; and</li>
 *   <li>a {@code [hooks.state."<key>"]} table header, below which it inserts or replaces the {@code trusted_hash}
 *   line.</li>
 * </ul>
 * It refuses every other form, such as an inline table or dotted keys, with a message that names the form it edits.
 * Jackson's TOML parser decides whether the file and each line are valid TOML, and the editor accepts its edit only
 * when the edited file parses to the original document with only that entry added, replaced or removed.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
final class HookTrust
{
	/**
	 * The hooks file that Codex reads when the plugin's manifest declares none, relative to the plugin directory.
	 */
	private static final String DEFAULT_HOOKS = "hooks/hooks.json";
	/**
	 * The plugin's manifest, relative to the plugin directory.
	 */
	private static final String MANIFEST = ".codex-plugin/plugin.json";
	/**
	 * The largest manifest or hooks file that the installer reads.
	 */
	private static final int MAXIMUM_FILE_BYTES = 1_048_576;
	/**
	 * The events whose command handlers Codex normalizes only by defaulting the timeout, which keep their matcher and
	 * their {@code additionalContextLimit}, mapped to the label that Codex gives them in a hook's key.
	 */
	private static final Map<String, String> EVENTS = Map.of("PreToolUse", "pre_tool_use",
		"PostToolUse", "post_tool_use", "SessionStart", "session_start", "SubagentStart", "subagent_start");
	/**
	 * The timeout that Codex gives a command hook that declares none, in seconds.
	 */
	private static final long DEFAULT_TIMEOUT_SECONDS = 600;
	/**
	 * The {@code additionalContextLimit} that Codex leaves out of a hook's identity because it is the default.
	 */
	private static final long DEFAULT_CONTEXT_LIMIT = 2_500;
	/**
	 * The table of Codex's per-hook state.
	 */
	private static final String HOOKS = "hooks";
	/**
	 * The setting that records the hash that the user trusted.
	 */
	private static final String TRUSTED_HASH = "trusted_hash";
	/**
	 * Reads hooks files and writes the JSON that Codex hashes.
	 */
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Prevents instantiation.
	 */
	private HookTrust()
	{
	}

	/**
	 * Returns the hash of each hook that a plugin declares.
	 *
	 * @param plugin   the plugin directory
	 * @param pluginId the plugin as Codex identifies it, such as {@code cat@cat}
	 * @param windows  true if the hooks run on Windows, where Codex runs a handler's Windows command
	 * @return each hook's key in {@code config.toml}, without the table's quotes, mapped to the hash that Codex
	 * 	computes for it, in the order of the hooks file; empty when the plugin declares no hooks file or Codex skips
	 * 	it because it is missing or not valid JSON
	 * @throws IOException if the manifest or the hooks file cannot be read
	 */
	static Map<String, String> hashes(Path plugin, String pluginId, boolean windows) throws IOException
	{
		Map<String, String> hashes = new LinkedHashMap<>();
		String relative = hooksFile(plugin);
		Path file = plugin.resolve(relative);
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			return hashes;
		JsonNode declaration;
		try
		{
			declaration = JSON.readTree(BoundedInput.readUtf8(file, MAXIMUM_FILE_BYTES, "Codex hooks file"));
		}
		catch (JacksonException _)
		{
			return hashes;
		}
		for (Entry<String, JsonNode> event : declaration.path("hooks").properties())
		{
			String label = EVENTS.get(event.getKey());
			if (label == null)
				continue;
			JsonNode groups = event.getValue();
			for (int group = 0; group < groups.size(); ++group)
			{
				JsonNode handlers = groups.get(group).path("hooks");
				for (int handler = 0; handler < handlers.size(); ++handler)
				{
					Optional<String> hash = hash(label, groups.get(group).path("matcher"), handlers.get(handler),
						windows);
					if (hash.isPresent())
						hashes.put(pluginId + ":" + relative + ":" + label + ":" + group + ":" + handler, hash.get());
				}
			}
		}
		return hashes;
	}

	/**
	 * Returns the hooks file that a plugin's manifest names.
	 *
	 * @param plugin the plugin directory
	 * @return the file, relative to the plugin directory and written as Codex writes it in a hook's key; the default
	 * 	file when the manifest is missing, names none or does not name one file
	 * @throws IOException if the manifest cannot be read
	 */
	private static String hooksFile(Path plugin) throws IOException
	{
		Path manifest = plugin.resolve(MANIFEST);
		if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS))
			return DEFAULT_HOOKS;
		JsonNode hooks;
		try
		{
			hooks = JSON.readTree(BoundedInput.readUtf8(manifest, MAXIMUM_FILE_BYTES, "Codex plugin manifest")).
				path("hooks");
		}
		catch (JacksonException _)
		{
			return DEFAULT_HOOKS;
		}
		if (!hooks.isString())
			return DEFAULT_HOOKS;
		return Path.of(hooks.stringValue()).normalize().toString().replace('\\', '/');
	}

	/**
	 * Returns the hash that Codex computes for a command hook.
	 *
	 * @param label   the event's label in a hook's key
	 * @param matcher the matcher group's matcher
	 * @param handler the handler's declaration
	 * @param windows true if the hook runs on Windows
	 * @return the hash, or empty if the handler is not a command that Codex runs
	 */
	private static Optional<String> hash(String label, JsonNode matcher, JsonNode handler, boolean windows)
	{
		if (!handler.path("type").asString("").equals("command"))
			return Optional.empty();
		JsonNode command = handler.path("command");
		if (windows && handler.path("commandWindows").isString())
			command = handler.path("commandWindows");
		if (!command.isString() || command.stringValue().isBlank())
			return Optional.empty();

		Map<String, Object> normalized = new TreeMap<>();
		normalized.put("type", "command");
		normalized.put("command", command.stringValue());
		normalized.put("timeout", Math.max(1, handler.path("timeout").asLong(DEFAULT_TIMEOUT_SECONDS)));
		normalized.put("async", handler.path("async").asBoolean(false));
		if (handler.path("statusMessage").isString())
			normalized.put("statusMessage", handler.path("statusMessage").stringValue());
		JsonNode limit = handler.path("additionalContextLimit");
		if (limit.isIntegralNumber() && limit.asLong() != DEFAULT_CONTEXT_LIMIT)
			normalized.put("additionalContextLimit", limit.asLong());
		Map<String, Object> identity = new TreeMap<>();
		identity.put("event_name", label);
		identity.put("hooks", List.of(normalized));
		if (matcher.isString())
			identity.put("matcher", matcher.stringValue());
		try
		{
			byte[] digest = MessageDigest.getInstance("SHA-256").
				digest(JSON.writeValueAsString(identity).getBytes(StandardCharsets.UTF_8));
			return Optional.of("sha256:" + HexFormat.of().formatHex(digest));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new AssertionError("Every Java runtime implements SHA-256", e);
		}
	}

	/**
	 * Returns the key under which an install records the hash that it trusted for a hook.
	 *
	 * @param key the hook's key in {@code config.toml}
	 * @return {@code config.toml#hooks.state."<key>".trusted_hash}
	 */
	static String recordKey(String key)
	{
		return "config.toml#" + HOOKS + ".state." + quote(key) + "." + TRUSTED_HASH;
	}

	/**
	 * Returns the hook that a record key names.
	 *
	 * @param recordKey a key of the install's record
	 * @return the hook's key in {@code config.toml}, or empty if the record key does not record a hook's trust
	 */
	static Optional<String> hookOf(String recordKey)
	{
		String prefix = "config.toml#" + HOOKS + ".state.";
		String suffix = "." + TRUSTED_HASH;
		if (!recordKey.startsWith(prefix) || !recordKey.endsWith(suffix))
			return Optional.empty();
		return Optional.of(TomlText.unquote(recordKey.substring(prefix.length(), recordKey.length() - suffix.length())));
	}

	/**
	 * Returns the hash that the file trusts for a hook.
	 *
	 * @param document the parsed {@code config.toml}
	 * @param key      the hook's key
	 * @return the hash, or empty if the file records none
	 */
	static Optional<String> trusted(ObjectNode document, String key)
	{
		JsonNode hash = document.path(HOOKS).path("state").path(key).path(TRUSTED_HASH);
		if (hash.isString())
			return Optional.of(hash.stringValue());
		return Optional.empty();
	}

	/**
	 * Makes the file trust a hook's hash, appending the hook's table, or inserting the hash into it, or replacing a
	 * hash that differs.
	 *
	 * @param text the file's content
	 * @param key  the hook's key
	 * @param hash the hash to trust
	 * @param file the file, named in the refusal
	 * @return the edited content, or {@code text} when the file already trusts the hash
	 * @throws IOException if the content is not valid TOML or holds the hook's state in a form that the editor does
	 *                     not change
	 */
	static String add(String text, String key, String hash, Path file) throws IOException
	{
		ObjectNode document = parse(text, file);
		requireTables(document, key, file);
		Optional<String> current = trusted(document, key);
		if (current.isPresent() && current.get().equals(hash))
			return text;

		ObjectNode expected = document.deepCopy();
		stateOf(expected, key).put(TRUSTED_HASH, hash);
		String newline = TomlText.newline(text);
		String entry = TRUSTED_HASH + " = " + quote(hash);
		List<String> lines = lines(text);
		int header = TomlText.findHeader(lines, headerOf(key));
		String edited;
		if (header == -1)
			edited = TomlText.appendTable(text, headerLine(key), entry, newline);
		else
		{
			int existing = trustedLine(lines, header, current);
			if (existing == -1)
			{
				TomlText.terminate(lines, header, newline);
				lines.add(header + 1, entry + newline);
			}
			else
				lines.set(existing, entry + lineEnd(lines.get(existing)));
			edited = String.join("", lines);
		}
		if (!expected.equals(tryParse(edited)))
			throw uneditable(key, file);
		return edited;
	}

	/**
	 * Removes the trust of a hook's hash, and the hook's table when the trust was all that it held. Leaves the file
	 * unchanged when it trusts another hash for the hook.
	 *
	 * @param text the file's content
	 * @param key  the hook's key
	 * @param hash the hash that an install recorded
	 * @param file the file, named in the refusal
	 * @return the edited content, or {@code text} when the file trusts no hash, or another hash, for the hook
	 * @throws IOException if the content is not valid TOML or holds the hook's state in a form that the editor does
	 *                     not change
	 */
	static String remove(String text, String key, String hash, Path file) throws IOException
	{
		ObjectNode document = parse(text, file);
		requireTables(document, key, file);
		Optional<String> current = trusted(document, key);
		if (current.isEmpty() || !current.get().equals(hash))
			return text;

		List<String> lines = lines(text);
		int header = TomlText.findHeader(lines, headerOf(key));
		int existing = -1;
		if (header != -1)
			existing = trustedLine(lines, header, current);
		if (existing == -1)
			throw uneditable(key, file);

		ObjectNode withoutHash = document.deepCopy();
		ObjectNode state = (ObjectNode) withoutHash.get(HOOKS).get("state").get(key);
		state.remove(TRUSTED_HASH);
		List<String> edited = new ArrayList<>(lines);
		edited.remove(existing);
		String hashRemoved = String.join("", edited);
		if (!state.isEmpty())
		{
			if (withoutHash.equals(tryParse(hashRemoved)))
				return hashRemoved;
			throw uneditable(key, file);
		}

		// The table held nothing else, so its header goes too, with the blank line that separates it from its neighbor
		edited.remove(header);
		if (header > 0 && lines.get(header - 1).isBlank())
			edited.remove(header - 1);
		else if (header == 0 && !edited.isEmpty() && edited.get(0).isBlank())
			edited.remove(0);
		String tableRemoved = String.join("", edited);
		ObjectNode parsed = (ObjectNode) tryParse(tableRemoved);
		ObjectNode kept = withoutTable(document, key);
		if (kept.equals(parsed) || withoutEmptyTables(kept).equals(parsed))
			return tableRemoved;
		throw uneditable(key, file);
	}

	/**
	 * Returns the document without a hook's table.
	 *
	 * @param document the document
	 * @param key      the hook's key
	 * @return the document without the hook's table
	 */
	private static ObjectNode withoutTable(ObjectNode document, String key)
	{
		ObjectNode result = document.deepCopy();
		((ObjectNode) result.get(HOOKS).get("state")).remove(key);
		return result;
	}

	/**
	 * Returns the document without the {@code hooks.state} and {@code hooks} tables that are empty, which a document
	 * parsed from text that has no header for them does not hold.
	 *
	 * @param document the document
	 * @return the document without the empty tables
	 */
	private static ObjectNode withoutEmptyTables(ObjectNode document)
	{
		ObjectNode result = document.deepCopy();
		ObjectNode hooks = (ObjectNode) result.get(HOOKS);
		if (hooks.get("state").isEmpty())
			hooks.remove("state");
		if (hooks.isEmpty())
			result.remove(HOOKS);
		return result;
	}

	/**
	 * Requires the tables that hold a hook's state to be tables, if the document has them.
	 *
	 * @param document the document
	 * @param key      the hook's key
	 * @param file     the file, named in the refusal
	 * @throws IOException if the document holds a setting that is not a table where the hook's state belongs
	 */
	private static void requireTables(ObjectNode document, String key, Path file) throws IOException
	{
		JsonNode hooks = document.path(HOOKS);
		JsonNode states = hooks.path("state");
		JsonNode state = states.path(key);
		boolean tables = (hooks.isMissingNode() || hooks.isObject()) && (states.isMissingNode() || states.isObject()) &&
			(state.isMissingNode() || state.isObject());
		if (!tables)
			throw uneditable(key, file);
	}

	/**
	 * Returns the table of a hook's state in a document, creating it and the tables above it when absent.
	 *
	 * @param document the document
	 * @param key      the hook's key
	 * @return the hook's table
	 */
	private static ObjectNode stateOf(ObjectNode document, String key)
	{
		ObjectNode hooks = (ObjectNode) document.get(HOOKS);
		if (hooks == null)
			hooks = document.putObject(HOOKS);
		ObjectNode states = (ObjectNode) hooks.get("state");
		if (states == null)
			states = hooks.putObject("state");
		ObjectNode state = (ObjectNode) states.get(key);
		if (state == null)
			state = states.putObject(key);
		return state;
	}

	/**
	 * Finds the line of a hook's table that sets {@code trusted_hash}.
	 *
	 * @param lines   the file's lines
	 * @param header  the index of the table's header line
	 * @param current the hash that the file trusts for the hook
	 * @return the line's index, or -1 when the table has no line of its own for the setting, such as when it is empty
	 */
	private static int trustedLine(List<String> lines, int header, Optional<String> current)
	{
		if (current.isEmpty())
			return -1;
		for (int index = header + 1; index < lines.size() && !lines.get(index).strip().startsWith("["); ++index)
		{
			JsonNode line = tryParse(lines.get(index));
			if (line != null && line.size() == 1 && line.has(TRUSTED_HASH))
				return index;
		}
		return -1;
	}

	/**
	 * Returns the line terminator that a line ends with.
	 *
	 * @param line a line with its terminator
	 * @return the terminator, empty when the line has none
	 */
	private static String lineEnd(String line)
	{
		if (line.endsWith("\r\n"))
			return "\r\n";
		if (line.endsWith("\n"))
			return "\n";
		return "";
	}

	/**
	 * Returns the document that the header line of a hook's table alone parses to.
	 *
	 * @param key the hook's key
	 * @return the document
	 */
	private static ObjectNode headerOf(String key)
	{
		ObjectNode header = TomlText.table();
		stateOf(header, key);
		return header;
	}

	/**
	 * Returns the header line of a hook's table.
	 *
	 * @param key the hook's key
	 * @return {@code [hooks.state."<key>"]}
	 */
	private static String headerLine(String key)
	{
		return "[" + HOOKS + ".state." + quote(key) + "]";
	}

	/**
	 * Describes a form that the editor does not change.
	 *
	 * @param key  the hook's key
	 * @param file the file
	 * @return the refusal
	 */
	private static IOException uneditable(String key, Path file)
	{
		return new IOException("CAT cannot edit the state of the Codex hook " + quote(key) + " in " + file +
			". CAT edits it only in a " + headerLine(key) + " table, with one setting per line. Write the table that " +
			"way, or remove hooks.state, then rerun this command.");
	}
}
