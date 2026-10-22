package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The harness that started a campaign and is the only one allowed to advance it, in the session that started it.
 */
public enum Harness
{
	/**
	 * Claude Code.
	 */
	CLAUDE(List.of("CLAUDE.md", "CLAUDE.local.md"), List.of(".claude")),
	/**
	 * Codex.
	 */
	CODEX(List.of("AGENTS.md", "AGENTS.override.md"), List.of(".codex", ".agents"));

	/**
	 * The names of the instruction files that the harness reads from the project, in any directory.
	 */
	private final List<String> instructionFiles;
	/**
	 * The top-level project directories whose files the harness reads from the project, such as its skills.
	 */
	private final List<String> projectDirectories;

	/**
	 * Creates a harness.
	 *
	 * @param instructionFiles the names of the instruction files that the harness reads from the project, in any
	 *   directory
	 * @param projectDirectories the top-level project directories whose files the harness reads from the project
	 */
	Harness(List<String> instructionFiles, List<String> projectDirectories)
	{
		this.instructionFiles = instructionFiles;
		this.projectDirectories = projectDirectories;
	}

	/**
	 * Returns the harness name used in commands and display text.
	 *
	 * @return the enum name in lower case using the root locale
	 */
	public String commandName()
	{
		return name().toLowerCase(Locale.ROOT);
	}

	/**
	 * Returns the harness's product name, as its users know it.
	 *
	 * @return {@code Claude Code} or {@code Codex}
	 */
	public String productName()
	{
		return switch (this)
		{
			case CLAUDE -> "Claude Code";
			case CODEX -> "Codex";
		};
	}

	/**
	 * Returns the harness of a command name.
	 *
	 * @param commandName the harness's command name, such as {@code codex}
	 * @return the harness, or empty if no harness has the command name
	 * @throws NullPointerException if {@code commandName} is null
	 */
	public static Optional<Harness> fromCommandName(String commandName)
	{
		requireThat(commandName, "commandName").isNotNull();
		for (Harness harness : values())
		{
			if (harness.commandName().equals(commandName))
				return Optional.of(harness);
		}
		return Optional.empty();
	}

	/**
	 * Returns the terminal command that resumes a session of the harness.
	 *
	 * @param sessionId the session's ID
	 * @return the command, such as {@code claude --resume <session-id>} or {@code codex resume <session-id>}
	 * @throws NullPointerException if {@code sessionId} is null
	 * @throws IllegalArgumentException if {@code sessionId} is blank
	 */
	public String resumeCommand(String sessionId)
	{
		requireThat(sessionId, "sessionId").isNotBlank();
		return switch (this)
		{
			case CLAUDE -> "claude --resume " + sessionId;
			case CODEX -> "codex resume " + sessionId;
		};
	}

	/**
	 * Returns where a plugin directory holds the harness's plugin manifest.
	 *
	 * @return the path relative to the plugin directory, such as {@code .codex-plugin/plugin.json}
	 */
	public Path pluginManifest()
	{
		return Path.of("." + commandName() + "-plugin", "plugin.json");
	}

	/**
	 * Indicates whether the harness reads a project file from the project itself, whatever plugins a session installs:
	 * an instruction file in any directory, or a file below one of the harness's project directories.
	 *
	 * @param file a file relative to the project
	 * @return {@code true} if the harness reads the file from the project
	 * @throws NullPointerException if {@code file} is null
	 */
	public boolean readsFromProject(Path file)
	{
		Path name = file.getFileName();
		return (name != null && instructionFiles.contains(name.toString())) ||
			(file.getNameCount() > 1 && projectDirectories.contains(file.getName(0).toString()));
	}

	/**
	 * Returns the names of the instruction files that the harness reads from the project, in any directory.
	 *
	 * @return file names without a directory, such as {@code AGENTS.md}
	 */
	public List<String> instructionFiles()
	{
		return instructionFiles;
	}

	/**
	 * Returns the top-level project directories whose files the harness reads from the project, such as its skills.
	 *
	 * @return directory names relative to the project, such as {@code .codex}
	 */
	public List<String> projectDirectories()
	{
		return projectDirectories;
	}
}
