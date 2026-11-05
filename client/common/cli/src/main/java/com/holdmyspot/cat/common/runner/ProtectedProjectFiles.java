package com.holdmyspot.cat.common.runner;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * The files of a project copy that a session's sandbox keeps the session from changing: the project's Git
 * configuration and hooks, and the settings of the harnesses that run sessions.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class ProtectedProjectFiles
{
	/**
	 * The names of the entries directly inside a project's {@code .git} directory that a session may not change.
	 */
	private static final List<String> GIT_NAMES = List.of("config", "hooks");
	/**
	 * The harness settings files of a project, relative to the project, that a session's sandbox protects: Claude
	 * Code's project settings, the Git configuration of the project's own folder, and Codex's project configuration
	 * and hooks.
	 */
	private static final List<Path> SETTINGS = List.of(Path.of(".claude", "settings.json"),
		Path.of(".claude", "settings.local.json"), Path.of(".gitconfig"), Path.of(".codex", "config.toml"),
		Path.of(".codex", "hooks.json"));

	/**
	 * Returns the names of the entries directly inside a project's {@code .git} directory that a session may not
	 * change: the Git configuration and the hooks, which a later Git command would run.
	 *
	 * @return the names, each a file or a directory
	 */
	public static List<String> gitNames()
	{
		return GIT_NAMES;
	}

	/**
	 * Returns every file or folder of a project that a session's sandbox keeps the session from changing.
	 *
	 * @return the paths relative to the project, each a file or a folder whose whole subtree is protected
	 */
	public static List<Path> paths()
	{
		return Stream.concat(GIT_NAMES.stream().map(name -> Path.of(".git", name)), SETTINGS.stream()).toList();
	}

	/**
	 * Prevents instantiation.
	 */
	private ProtectedProjectFiles()
	{
	}
}
