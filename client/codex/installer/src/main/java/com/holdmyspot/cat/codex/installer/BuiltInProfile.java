package com.holdmyspot.cat.codex.installer;

import static com.holdmyspot.cat.codex.installer.TomlText.lines;
import static com.holdmyspot.cat.codex.installer.TomlText.parse;
import static com.holdmyspot.cat.codex.installer.TomlText.quote;
import static com.holdmyspot.cat.codex.installer.TomlText.tryParse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Replaces a permission profile that is built into Codex, and that Codex's {@code config.toml} selects with
 * {@code default_permissions}, with a profile of CAT's that extends it, and undoes that change.
 * <p>
 * CAT cannot add an entry to a built-in profile, and Codex ignores {@code sandbox_workspace_write.writable_roots} while
 * one is selected. CAT's profile of the built-in profile {@code :<name>} is {@code cat-<name>}, which holds only
 * {@code extends = ":<name>"} when this class creates it; {@link PermissionProfile} then adds the write entry of the
 * capacity locks and the read entry of the session launcher's state to it. The editor changes only the
 * {@code default_permissions} line before the first table header and appends or removes the profile's tables, and it
 * accepts an edit only when the edited file parses to the expected document.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
final class BuiltInProfile
{
	/**
	 * The setting that selects the permission profile.
	 */
	static final String SELECTION = "default_permissions";
	/**
	 * The table of Codex's permission profiles.
	 */
	private static final String PROFILES = "permissions";
	/**
	 * The setting with which a profile extends another one.
	 */
	private static final String EXTENDS = "extends";
	/**
	 * The table of a profile's filesystem entries.
	 */
	private static final String FILESYSTEM = PermissionProfile.FILESYSTEM;
	/**
	 * The table of a profile's network settings.
	 */
	private static final String NETWORK = PermissionProfile.NETWORK;
	/**
	 * The built-in profiles that limit which files commands may write. {@code :danger-full-access} does not.
	 */
	static final Set<String> LIMITED = Set.of(":workspace", ":read-only");

	/**
	 * Prevents instantiation.
	 */
	private BuiltInProfile()
	{
	}

	/**
	 * Returns the built-in profile that limits which files commands may write and that the document selects.
	 *
	 * @param document the parsed {@code config.toml}
	 * @return the built-in profile's name, such as {@code :workspace}, or empty when the document selects none, or
	 * 	defines a profile of that name itself
	 */
	static Optional<String> selected(ObjectNode document)
	{
		JsonNode name = document.path(SELECTION);
		if (!name.isString() || !LIMITED.contains(name.stringValue()) ||
			document.path(PROFILES).has(name.stringValue()))
		{
			return Optional.empty();
		}
		return Optional.of(name.stringValue());
	}

	/**
	 * Returns the name of CAT's profile that extends a built-in profile.
	 *
	 * @param builtIn the built-in profile's name, such as {@code :workspace}
	 * @return {@code cat-<name>}, such as {@code cat-workspace}
	 */
	static String catProfile(String builtIn)
	{
		return "cat-" + builtIn.substring(1);
	}

	/**
	 * Returns the setting that {@link #create} writes into CAT's profile of a built-in profile.
	 *
	 * @param builtIn the built-in profile's name, such as {@code :workspace}
	 * @return the line that makes the profile extend {@code builtIn}, such as {@code extends = ":workspace"}
	 */
	static String definition(String builtIn)
	{
		return EXTENDS + " = " + quote(builtIn);
	}

	/**
	 * Returns the built-in profile that a profile of CAT's extends, for each profile that the document defines in the
	 * form that {@link #create} writes.
	 *
	 * @param document the parsed {@code config.toml}
	 * @param profile  a profile's name
	 * @return the built-in profile's name, or empty when {@code profile} is not CAT's profile of a built-in profile that
	 * 	it extends
	 */
	static Optional<String> extended(ObjectNode document, String profile)
	{
		for (String builtIn : LIMITED)
		{
			if (catProfile(builtIn).equals(profile) &&
				document.path(PROFILES).path(profile).path(EXTENDS).asString("").equals(builtIn))
			{
				return Optional.of(builtIn);
			}
		}
		return Optional.empty();
	}

	/**
	 * Selects CAT's profile of the selected built-in profile, and creates the profile unless the file already defines
	 * it.
	 *
	 * @param text    the file's content
	 * @param builtIn the selected built-in profile
	 * @param file    the file, named in the refusal
	 * @return the edited content
	 * @throws IOException if the content is not valid TOML, already defines a profile of that name that does not
	 *                     extend {@code builtIn}, or is in a form that the editor does not change
	 */
	static String create(String text, String builtIn, Path file) throws IOException
	{
		ObjectNode document = parse(text, file);
		String profile = catProfile(builtIn);
		JsonNode profiles = document.path(PROFILES);
		if (!profiles.isMissingNode() && !profiles.isObject())
			throw uneditable(builtIn, file);
		if (profiles.has(profile) && extended(document, profile).isEmpty())
		{
			throw new IOException("Codex's configuration " + file + " defines a permission profile " +
				quote(profile) + " that does not extend " + quote(builtIn) + ", so CAT cannot use that name for its " +
				"profile. Rename that profile, then rerun this command.");
		}
		Optional<String> selected = select(text, builtIn, profile, file);
		if (selected.isEmpty())
			throw uneditable(builtIn, file);
		if (profiles.has(profile))
			return selected.get();

		String newline = TomlText.newline(text);
		String edited = TomlText.appendTable(selected.get(), "[" + PROFILES + "." + profile + "]",
			definition(builtIn), newline);
		ObjectNode expected = document.deepCopy();
		expected.put(SELECTION, profile);
		ObjectNode expectedProfiles;
		if (expected.has(PROFILES))
			expectedProfiles = (ObjectNode) expected.get(PROFILES);
		else
			expectedProfiles = expected.putObject(PROFILES);
		expectedProfiles.putObject(profile).put(EXTENDS, builtIn);
		if (!expected.equals(tryParse(edited)))
			throw uneditable(builtIn, file);
		return edited;
	}

	/**
	 * Undoes {@link #create}: selects the built-in profile again while the file still selects CAT's profile, then
	 * removes CAT's profile while it holds only what CAT wrote and the file no longer selects it. Leaves each part in
	 * place that the user changed or that the editor does not change.
	 *
	 * @param text    the file's content
	 * @param builtIn the built-in profile that CAT's profile extends
	 * @param file    the file, named in the refusal
	 * @return the edited content, or {@code text} when nothing could be undone
	 * @throws IOException if the content is not valid TOML
	 */
	static String remove(String text, String builtIn, Path file) throws IOException
	{
		String profile = catProfile(builtIn);
		String edited = text;
		if (parse(text, file).path(SELECTION).asString("").equals(profile))
			edited = select(text, profile, builtIn, file).orElse(text);
		return removeProfile(edited, profile, builtIn, file);
	}

	/**
	 * Replaces the value of the {@code default_permissions} line before the first table header.
	 *
	 * @param text the file's content
	 * @param from the profile that the file selects
	 * @param to   the profile to select
	 * @param file the file, named in the refusal
	 * @return the edited content, or empty when the editor does not change the line's form
	 * @throws IOException if the content is not valid TOML
	 */
	private static Optional<String> select(String text, String from, String to, Path file) throws IOException
	{
		ObjectNode expected = parse(text, file).deepCopy();
		expected.put(SELECTION, to);
		List<String> lines = lines(text);
		for (int index = 0; index < lines.size(); ++index)
		{
			String line = lines.get(index);
			if (line.strip().startsWith("["))
				break;
			for (String token : List.of(quote(from), "'" + from + "'"))
			{
				int start = line.indexOf(token);
				if (start == -1)
					continue;
				List<String> edited = new ArrayList<>(lines);
				edited.set(index, line.substring(0, start) + quote(to) + line.substring(start + token.length()));
				String result = String.join("", edited);
				if (expected.equals(tryParse(result)))
					return Optional.of(result);
			}
		}
		return Optional.empty();
	}

	/**
	 * Removes a profile of CAT's that extends a built-in profile when it holds only {@code extends} and empty
	 * filesystem and network tables, and the file does not select it. A comment in the profile's tables counts as the
	 * user's content.
	 *
	 * @param text    the file's content
	 * @param profile the name of CAT's profile
	 * @param builtIn the built-in profile that it extends
	 * @param file    the file, named in the refusal
	 * @return the edited content, or {@code text} when the profile is absent, selected, holds other content or is in a
	 * 	form that the editor does not change
	 * @throws IOException if the content is not valid TOML
	 */
	static String removeProfile(String text, String profile, String builtIn, Path file) throws IOException
	{
		ObjectNode document = parse(text, file);
		if (!holdsOnlyCatContent(document, profile, builtIn) ||
			document.path(SELECTION).asString("").equals(profile))
		{
			return text;
		}
		List<String> lines = lines(text);
		ObjectNode extendsLine = TomlText.table();
		extendsLine.put(EXTENDS, builtIn);
		Set<Integer> removed = new TreeSet<>();
		ObjectNode header = TomlText.table();
		ObjectNode table = header.putObject(PROFILES).putObject(profile);
		if (!removeTable(lines, header, extendsLine, removed))
			return text;
		table.putObject(FILESYSTEM);
		if (!removeTable(lines, header, extendsLine, removed))
			return text;
		table.remove(FILESYSTEM);
		table.putObject(NETWORK);
		if (!removeTable(lines, header, extendsLine, removed))
			return text;
		if (removed.isEmpty())
			return text;
		int first = removed.iterator().next();
		if (removed.size() == lines.size() - first && first > 0 && lines.get(first - 1).isBlank())
			removed.add(first - 1);
		StringBuilder edited = new StringBuilder();
		for (int index = 0; index < lines.size(); ++index)
		{
			if (!removed.contains(index))
				edited.append(lines.get(index));
		}

		ObjectNode expected = document.deepCopy();
		ObjectNode profiles = (ObjectNode) expected.get(PROFILES);
		profiles.remove(profile);
		if (profiles.isEmpty())
			expected.remove(PROFILES);
		JsonNode actual = tryParse(edited.toString());
		if (actual == null || !expected.equals(actual))
			return text;
		return edited.toString();
	}

	/**
	 * Returns whether a profile holds only the content that CAT writes, once its filesystem and network entries are
	 * removed.
	 *
	 * @param document the parsed file
	 * @param profile  CAT's profile's name
	 * @param builtIn  the built-in profile that it extends
	 * @return true if the profile holds only {@code extends} of {@code builtIn} and empty filesystem and network tables
	 */
	private static boolean holdsOnlyCatContent(ObjectNode document, String profile, String builtIn)
	{
		JsonNode table = document.path(PROFILES).path(profile);
		if (!table.isObject() || !table.path(EXTENDS).asString("").equals(builtIn))
			return false;
		for (String key : table.propertyNames())
		{
			if (key.equals(EXTENDS))
				continue;
			if (!Set.of(FILESYSTEM, NETWORK).contains(key) || !table.get(key).isObject() || !table.get(key).isEmpty())
				return false;
		}
		return true;
	}

	/**
	 * Marks a table's header line, its blank lines and its {@code extends} line for removal.
	 *
	 * @param lines       the file's lines
	 * @param header      the document that the header line alone parses to
	 * @param extendsLine the document that the {@code extends} line alone parses to
	 * @param removed     the indexes of the lines to remove, to which this call adds
	 * @return false if the table holds another line, such as a comment; true if it holds none or the file has no such
	 * 	header
	 */
	private static boolean removeTable(List<String> lines, ObjectNode header, ObjectNode extendsLine,
		Set<Integer> removed)
	{
		int index = TomlText.findHeader(lines, header);
		if (index == -1)
			return true;
		List<Integer> table = new ArrayList<>();
		table.add(index);
		for (int next = index + 1; next < lines.size() && !lines.get(next).strip().startsWith("["); ++next)
		{
			String line = lines.get(next);
			if (!line.isBlank() && !extendsLine.equals(tryParse(line)))
				return false;
			table.add(next);
		}
		removed.addAll(table);
		return true;
	}

	/**
	 * Describes a form that the editor does not change.
	 *
	 * @param builtIn the selected built-in profile
	 * @param file    the file
	 * @return the refusal
	 */
	private static IOException uneditable(String builtIn, Path file)
	{
		return new IOException("CAT cannot select its permission profile " + quote(catProfile(builtIn)) + " in " +
			file + ". CAT edits only a line such as default_permissions = " + quote(builtIn) + " before the first " +
			"table header, and adds its profile as a [" + PROFILES + "." + catProfile(builtIn) + "] table. Write " +
			"the file that way, then rerun this command.");
	}
}
