package com.holdmyspot.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.BoundedInput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The settings of the files that Claude Code loads beside a session's own that decide whether the session's sandbox
 * starts: the bubblewrap file that {@code sandbox.bwrapPath} names and the {@code TMPDIR} that an {@code env} sets.
 *
 * @param bwrapPath       the {@code sandbox.bwrapPath} setting, or {@code null} if no file sets it
 * @param temporaryFolder the {@code env.TMPDIR} setting, or {@code null} if no file sets it
 */
public record ClaudeSandboxSettings(Setting bwrapPath, Setting temporaryFolder)
{
	/**
	 * The most bytes that CAT reads of a settings file.
	 */
	private static final int MAXIMUM_SETTINGS_BYTES = 1_048_576;

	/**
	 * One setting and the settings file that sets it.
	 *
	 * @param file  the settings file
	 * @param value the setting's value, as the file spells it
	 */
	public record Setting(Path file, String value)
	{
		/**
		 * Creates a setting.
		 *
		 * @param file  the settings file
		 * @param value the setting's value, as the file spells it
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if {@code value} is blank
		 */
		public Setting
		{
			requireThat(file, "file").isNotNull();
			requireThat(value, "value").isNotBlank();
		}
	}

	/**
	 * Reads the settings that decide whether a session's sandbox starts from the settings files that Claude Code loads.
	 * A file that is not a regular file, cannot be parsed or is not a JSON object is skipped: another check refuses
	 * the launch because of it.
	 *
	 * @param json  the JSON mapper
	 * @param files the settings files, from the one that Claude Code gives the lowest precedence to the one that it
	 *              gives the highest; a setting that several files set takes the value of the last of them
	 * @return the settings
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if a settings file cannot be read
	 */
	public static ClaudeSandboxSettings read(JsonMapper json, List<Path> files) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(files, "files").isNotNull();
		Setting bwrapPath = null;
		Setting temporaryFolder = null;
		for (Path file : files)
		{
			if (!Files.isRegularFile(file))
				continue;
			JsonNode settings;
			try
			{
				settings = json.readTree(BoundedInput.readUtf8(file, MAXIMUM_SETTINGS_BYTES, "Claude settings"));
			}
			catch (JacksonException _)
			{
				continue;
			}
			if (!settings.isObject())
				continue;
			Setting folder = setting(file, settings.path("env").path("TMPDIR"));
			if (folder != null)
				temporaryFolder = folder;
			Setting bwrap = setting(file, settings.path("sandbox").path("bwrapPath"));
			if (bwrap != null)
				bwrapPath = bwrap;
		}
		return new ClaudeSandboxSettings(bwrapPath, temporaryFolder);
	}

	/**
	 * Returns the setting that a value of a settings file gives.
	 *
	 * @param file  the settings file
	 * @param value the value
	 * @return the setting, or {@code null} if the value is not a non-blank string
	 */
	private static Setting setting(Path file, JsonNode value)
	{
		if (!value.isString() || value.stringValue().isBlank())
			return null;
		return new Setting(file, value.stringValue());
	}
}
