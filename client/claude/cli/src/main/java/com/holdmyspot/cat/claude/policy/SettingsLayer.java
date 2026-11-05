package com.holdmyspot.cat.claude.policy;

import java.nio.file.Path;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The settings that one source gives a Claude Code session.
 *
 * @param scope    the scope of the settings
 * @param source   the source as the user knows it, such as the path of the settings file
 * @param file     the settings file, or empty if the settings are given as JSON on the command line
 * @param settings the settings, a JSON object
 * @param root     the absolute folder that relative paths of the settings' sandbox section are relative to
 */
record SettingsLayer(SettingsScope scope, String source, Optional<Path> file, JsonNode settings, Path root)
{
	/**
	 * Creates a layer.
	 *
	 * @param scope    the scope of the settings
	 * @param source   the source as the user knows it, such as the path of the settings file
	 * @param file     the settings file, or empty if the settings are given as JSON on the command line
	 * @param settings the settings, a JSON object
	 * @param root     the absolute folder that relative paths of the settings' sandbox section are relative to
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code settings} is not an object, or {@code root} is not absolute
	 */
	SettingsLayer
	{
		requireThat(scope, "scope").isNotNull();
		requireThat(source, "source").isNotBlank();
		requireThat(file, "file").isNotNull();
		requireThat(settings, "settings").isNotNull();
		if (!settings.isObject())
			throw new IllegalArgumentException("settings must be an object: " + settings);
		requireThat(root, "root").isNotNull();
		if (!root.isAbsolute())
			throw new IllegalArgumentException("root must be absolute: " + root);
	}
}
