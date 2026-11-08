package com.holdmyspot.cat.codex.installer;

import static com.holdmyspot.cat.codex.installer.TomlText.lines;
import static com.holdmyspot.cat.codex.installer.TomlText.parse;
import static com.holdmyspot.cat.codex.installer.TomlText.quote;
import static com.holdmyspot.cat.codex.installer.TomlText.tryParse;

import com.holdmyspot.cat.codex.installer.PermissionProfile.Access;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Selects a profile of CAT's in place of the {@code sandbox_mode} that Codex's {@code config.toml} sets when it selects
 * no permission profile, and undoes that change.
 * <p>
 * Only a selected profile can hold the read entries that keep sandboxed commands from writing the shell startup files.
 * CAT's profile of {@code sandbox_mode = "workspace-write"} is {@code cat-sandbox-workspace-write}, which extends
 * {@code :workspace}, and CAT's profile of {@code sandbox_mode = "read-only"} is {@code cat-sandbox-read-only}, which
 * extends {@code :read-only}. This class creates the profile with only its {@code extends} line, and lists the entries
 * that copy the user's {@code [sandbox_workspace_write]} settings into the first profile, which
 * {@link PermissionProfile} then adds. The editor inserts the {@code default_permissions} line below the
 * {@code sandbox_mode} line before the first table header and appends or removes the profile's tables, and it accepts
 * an edit only when the edited file parses to the expected document.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
final class SandboxModeProfile
{
	/**
	 * The setting that selects the sandbox when no permission profile is selected.
	 */
	static final String MODE = "sandbox_mode";
	/**
	 * The sandbox mode that lets commands write the project and the writable folders.
	 */
	static final String WORKSPACE_WRITE = "workspace-write";
	/**
	 * The built-in profile that CAT's profile of each sandbox mode extends, by sandbox mode.
	 */
	private static final Map<String, String> EXTENDED = Map.of(WORKSPACE_WRITE, ":workspace",
		"read-only", ":read-only");
	/**
	 * The sandbox modes of which CAT creates a profile.
	 */
	static final Set<String> MODES = EXTENDED.keySet();
	/**
	 * The table of the settings of the {@code workspace-write} sandbox mode.
	 */
	private static final String SETTINGS = "sandbox_workspace_write";
	/**
	 * The table of Codex's permission profiles.
	 */
	private static final String PROFILES = "permissions";
	/**
	 * The setting with which a profile extends another one.
	 */
	private static final String EXTENDS = "extends";

	/**
	 * Prevents instantiation.
	 */
	private SandboxModeProfile()
	{
	}

	/**
	 * Returns the sandbox mode of which CAT offers to select a profile.
	 *
	 * @param document the parsed {@code config.toml}
	 * @return {@code workspace-write} or {@code read-only}, or empty when the document sets
	 * 	{@code default_permissions}, sets no {@code sandbox_mode}, or sets another one
	 */
	static Optional<String> unselected(ObjectNode document)
	{
		JsonNode mode = document.path(MODE);
		if (document.has(BuiltInProfile.SELECTION) || !mode.isString() || !MODES.contains(mode.stringValue()))
			return Optional.empty();
		return Optional.of(mode.stringValue());
	}

	/**
	 * Returns the name of CAT's profile of a sandbox mode.
	 *
	 * @param mode the sandbox mode
	 * @return {@code cat-sandbox-<mode>}, such as {@code cat-sandbox-workspace-write}
	 */
	static String catProfile(String mode)
	{
		return "cat-sandbox-" + mode;
	}

	/**
	 * Returns the built-in profile that CAT's profile of a sandbox mode extends.
	 *
	 * @param mode the sandbox mode
	 * @return {@code :workspace} for {@code workspace-write}, {@code :read-only} for {@code read-only}
	 */
	static String builtIn(String mode)
	{
		return EXTENDED.get(mode);
	}

	/**
	 * Returns the sandbox mode of which a profile is CAT's profile, when the document defines it in the form that
	 * {@link #create} writes.
	 *
	 * @param document the parsed {@code config.toml}
	 * @param profile  a profile's name
	 * @return the sandbox mode, or empty when {@code profile} is not CAT's profile of a sandbox mode that extends the
	 * 	mode's built-in profile
	 */
	static Optional<String> extended(ObjectNode document, String profile)
	{
		for (String mode : MODES)
		{
			if (catProfile(mode).equals(profile) &&
				document.path(PROFILES).path(profile).path(EXTENDS).asString("").equals(builtIn(mode)))
			{
				return Optional.of(mode);
			}
		}
		return Optional.empty();
	}

	/**
	 * Returns the filesystem entries that copy the user's {@code [sandbox_workspace_write]} settings into CAT's profile
	 * of {@code workspace-write}: a write entry for each writable folder, and a read entry for {@code :tmpdir} and
	 * {@code :slash_tmp} when the settings exclude them. A writable folder that is neither absolute nor starts with
	 * {@code ~} is resolved against the Codex home, as Codex resolves it.
	 *
	 * @param document the parsed {@code config.toml}
	 * @param mode     the sandbox mode of CAT's profile
	 * @param home     the Codex home
	 * @return each entry's path and access, in the settings' order; empty for {@code read-only}
	 */
	static Map<String, Access> filesystemEntries(ObjectNode document, String mode, Path home)
	{
		Map<String, Access> entries = new LinkedHashMap<>();
		if (!mode.equals(WORKSPACE_WRITE))
			return entries;
		JsonNode settings = document.path(SETTINGS);
		for (JsonNode root : settings.path("writable_roots"))
		{
			if (!root.isString())
				continue;
			String path = root.stringValue();
			if (!path.equals("~") && !path.startsWith("~/") && !path.startsWith("~\\") && !isAbsolute(path))
				path = home.resolve(path).normalize().toString();
			entries.putIfAbsent(path, Access.WRITE);
		}
		if (settings.path("exclude_tmpdir_env_var").asBoolean(false))
			entries.putIfAbsent(":tmpdir", Access.READ);
		if (settings.path("exclude_slash_tmp").asBoolean(false))
			entries.putIfAbsent(":slash_tmp", Access.READ);
		return entries;
	}

	/**
	 * Returns whether a writable folder is absolute.
	 *
	 * @param path the folder as the settings name it
	 * @return true if it is absolute, or is not a path of this platform, which Codex then refuses itself
	 */
	private static boolean isAbsolute(String path)
	{
		try
		{
			return Path.of(path).isAbsolute();
		}
		catch (InvalidPathException _)
		{
			return true;
		}
	}

	/**
	 * Returns whether the user's {@code [sandbox_workspace_write]} settings let commands reach the network.
	 *
	 * @param document the parsed {@code config.toml}
	 * @param mode     the sandbox mode of CAT's profile
	 * @return true if {@code mode} is {@code workspace-write} and the settings set {@code network_access = true}
	 */
	static boolean networkAccess(ObjectNode document, String mode)
	{
		return mode.equals(WORKSPACE_WRITE) && document.path(SETTINGS).path("network_access").asBoolean(false);
	}

	/**
	 * Selects CAT's profile of the sandbox mode, and creates the profile unless the file already defines it.
	 *
	 * @param text the file's content
	 * @param mode the sandbox mode that the file sets
	 * @param file the file, named in the refusal
	 * @return the edited content
	 * @throws IOException if the content is not valid TOML, already defines a profile of that name that does not
	 *                     extend the mode's built-in profile, or is in a form that the editor does not change
	 */
	static String create(String text, String mode, Path file) throws IOException
	{
		ObjectNode document = parse(text, file);
		String profile = catProfile(mode);
		JsonNode profiles = document.path(PROFILES);
		if (!profiles.isMissingNode() && !profiles.isObject())
			throw uneditable(mode, file);
		if (profiles.has(profile) && extended(document, profile).isEmpty())
		{
			throw new IOException("Codex's configuration " + file + " defines a permission profile " +
				quote(profile) + " that does not extend " + quote(builtIn(mode)) + ", so CAT cannot use that name for " +
				"its profile. Rename that profile, then rerun this command.");
		}
		String newline = TomlText.newline(text);
		ObjectNode modeLine = TomlText.table();
		modeLine.put(MODE, mode);
		List<String> lines = lines(text);
		int index = 0;
		while (index < lines.size() && !lines.get(index).strip().startsWith("[") &&
			!modeLine.equals(tryParse(lines.get(index))))
		{
			++index;
		}
		if (index == lines.size() || lines.get(index).strip().startsWith("["))
			throw uneditable(mode, file);
		TomlText.terminate(lines, index, newline);
		lines.add(index + 1, BuiltInProfile.SELECTION + " = " + quote(profile) + newline);
		String edited = String.join("", lines);

		ObjectNode expected = document.deepCopy();
		expected.put(BuiltInProfile.SELECTION, profile);
		if (!profiles.has(profile))
		{
			edited = TomlText.appendTable(edited, "[" + PROFILES + "." + profile + "]",
				BuiltInProfile.definition(builtIn(mode)), newline);
			ObjectNode expectedProfiles;
			if (expected.has(PROFILES))
				expectedProfiles = (ObjectNode) expected.get(PROFILES);
			else
				expectedProfiles = expected.putObject(PROFILES);
			expectedProfiles.putObject(profile).put(EXTENDS, builtIn(mode));
		}
		if (!expected.equals(tryParse(edited)))
			throw uneditable(mode, file);
		return edited;
	}

	/**
	 * Undoes {@link #create}: removes the {@code default_permissions} line while it still names CAT's profile, then
	 * removes CAT's profile while it holds only what CAT wrote and the file no longer selects it. Leaves each part in
	 * place that the user changed or that the editor does not change.
	 *
	 * @param text the file's content
	 * @param mode the sandbox mode of CAT's profile
	 * @param file the file, named in the refusal
	 * @return the edited content, or {@code text} when nothing could be undone
	 * @throws IOException if the content is not valid TOML
	 */
	static String remove(String text, String mode, Path file) throws IOException
	{
		String profile = catProfile(mode);
		ObjectNode document = parse(text, file);
		String edited = text;
		if (document.path(BuiltInProfile.SELECTION).asString("").equals(profile))
			edited = unselect(text, document, profile).orElse(text);
		return BuiltInProfile.removeProfile(edited, profile, builtIn(mode), file);
	}

	/**
	 * Removes the {@code default_permissions} line that names CAT's profile before the first table header.
	 *
	 * @param text     the file's content
	 * @param document the parsed file
	 * @param profile  CAT's profile
	 * @return the edited content, or empty when the editor does not change the line's form
	 */
	private static Optional<String> unselect(String text, ObjectNode document, String profile)
	{
		ObjectNode selection = TomlText.table();
		selection.put(BuiltInProfile.SELECTION, profile);
		ObjectNode expected = document.deepCopy();
		expected.remove(BuiltInProfile.SELECTION);
		List<String> lines = lines(text);
		for (int index = 0; index < lines.size() && !lines.get(index).strip().startsWith("["); ++index)
		{
			if (!selection.equals(tryParse(lines.get(index))))
				continue;
			List<String> edited = new ArrayList<>(lines);
			edited.remove(index);
			String result = String.join("", edited);
			if (expected.equals(tryParse(result)))
				return Optional.of(result);
		}
		return Optional.empty();
	}

	/**
	 * Describes a form that the editor does not change.
	 *
	 * @param mode the sandbox mode that the file sets
	 * @param file the file
	 * @return the refusal
	 */
	private static IOException uneditable(String mode, Path file)
	{
		return new IOException("CAT cannot select its permission profile " + quote(catProfile(mode)) + " in " +
			file + ". CAT adds the line " + BuiltInProfile.SELECTION + " = " + quote(catProfile(mode)) + " below a " +
			"line such as " + MODE + " = " + quote(mode) + " before the first table header, and adds its profile as " +
			"a [" + PROFILES + "." + catProfile(mode) + "] table. Write the file that way, then rerun this command.");
	}
}
