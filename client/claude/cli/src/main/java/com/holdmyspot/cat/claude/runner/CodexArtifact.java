package com.holdmyspot.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.layout.CodexInstallation;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.release.EntryCommand;
import com.holdmyspot.cat.common.release.OtherHarnessPlugin;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Runs {@code codex-artifact}, which finds the Codex CAT plugin for an agent that runs Codex from the Claude plugin,
 * and prints it with the plugin's {@code codex-runner} launcher. The Claude plugin holds no Codex classes or files, so
 * the artifact is the Codex CAT plugin that Codex has installed, which must be of this plugin's CAT release. When Codex
 * has none, the command stops and names the command that installs CAT into Codex, for the user to approve.
 */
public final class CodexArtifact
{
	/**
	 * The Codex plugin manifest, relative to the plugin.
	 */
	private static final Path MANIFEST = Path.of(".codex-plugin/plugin.json");

	/**
	 * Prints the artifact as {@code key=value} lines, or the reason that it cannot be found to standard error.
	 *
	 * @param args no arguments
	 */
	public static void main(String[] args)
	{
		if (args.length != 0)
		{
			System.err.println("Usage: codex-artifact");
			System.exit(2);
		}
		try
		{
			System.out.print(execute(Platform.current().environment(System.getenv())));
		}
		catch (IOException e)
		{
			System.err.println(e.getMessage());
			System.exit(1);
		}
	}

	/**
	 * Finds the Codex CAT plugin for the Claude plugin that holds the running code.
	 *
	 * @param environment the process environment, as {@link #execute(Map, Path, Platform, Path)} reads it
	 * @return the {@code artifact}, {@code runner} and {@code source} lines
	 * @throws NullPointerException if {@code environment} is null
	 * @throws IOException          if the running code is not loaded from a CAT plugin, or the plugin cannot be found
	 */
	public static String execute(Map<String, String> environment) throws IOException
	{
		Path plugin;
		try
		{
			plugin = PluginDirectory.of(CodexArtifact.class);
		}
		catch (IllegalStateException e)
		{
			throw new IOException(e.getMessage(), e);
		}
		return execute(environment, plugin, Platform.current(), EntryCommand.currentAccountHome());
	}

	/**
	 * Finds the Codex CAT plugin that Codex has installed in its plugin cache.
	 *
	 * @param environment the process environment: the user's home directory ({@code HOME}, or {@code USERPROFILE} on
	 *                    Windows) and {@code CODEX_HOME}, the Codex home, which defaults to {@code .codex} in the home
	 *                    directory
	 * @param plugin      the version folder of the Claude plugin that runs the command
	 * @param platform    the platform that the plugins were built for and run on
	 * @param accountHome the home directory that the user database records for the account that runs the process, or
	 *                    {@code null} if it cannot be determined; the message that asks the user to run the CAT entry
	 *                    command sets {@code HOME} when the environment's differs
	 * @return the {@code artifact}, {@code runner} and {@code source} lines
	 * @throws NullPointerException if {@code environment}, {@code plugin} or {@code platform} are null
	 * @throws IOException          if the home directory is not set, Codex has no CAT plugin of this plugin's release, or
	 *                              that plugin is incomplete
	 */
	public static String execute(Map<String, String> environment, Path plugin, Platform platform, Path accountHome)
		throws IOException
	{
		requireThat(environment, "environment").isNotNull();
		requireThat(plugin, "plugin").isNotNull();
		requireThat(platform, "platform").isNotNull();
		CodexInstallation codex;
		try
		{
			codex = CodexInstallation.fromEnvironment(environment, platform.homeDirectory(environment));
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException(e.getMessage(), e);
		}
		return OtherHarnessPlugin.select(plugin, codex, "codex", "Codex", installed(codex.versionFolders()), MANIFEST,
			"codex-runner", platform, new EntryCommand(platform, environment, accountHome)).format();
	}

	/**
	 * Returns the plugin versions that Codex has installed: the names of the version folders in its plugin cache.
	 *
	 * @param versionFolders the folder that holds the version folders
	 * @return the names, sorted, or none when the folder does not exist
	 * @throws IOException if the folder cannot be listed
	 */
	private static List<String> installed(Path versionFolders) throws IOException
	{
		if (!Files.isDirectory(versionFolders, LinkOption.NOFOLLOW_LINKS))
			return List.of();
		try (Stream<Path> children = Files.list(versionFolders))
		{
			return children.filter(child -> Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)).
				map(child -> child.getFileName().toString()).sorted().toList();
		}
	}

	/**
	 * Prevents instantiation.
	 */
	private CodexArtifact()
	{
	}
}
