package com.holdmyspot.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.layout.ClaudeInstallation;
import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.release.OtherHarnessPlugin;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs {@code claude-artifact}, which finds the Claude CAT plugin for an agent that runs Claude Code from the Codex
 * plugin, and prints it with the plugin's {@code claude-runner} launcher. The Codex plugin holds no Claude classes or
 * files, so the artifact is the Claude CAT plugin that Claude Code has installed, which must be of this plugin's CAT
 * release. When Claude Code has none, the command stops and names the command that installs CAT into Claude Code, for
 * the user to approve.
 */
public final class ClaudeArtifact
{
	/**
	 * The Claude plugin manifest, relative to the plugin.
	 */
	private static final Path MANIFEST = Path.of(".claude-plugin/plugin.json");
	/**
	 * Claude Code's install records, relative to its configuration directory.
	 */
	private static final Path RECORDS = Path.of("plugins/installed_plugins.json");
	/**
	 * The format version of the install records that this command reads.
	 */
	private static final int RECORDS_VERSION = 2;
	/**
	 * The most bytes of install records that this command reads.
	 */
	private static final int MAXIMUM_RECORDS_BYTES = 1_048_576;
	/**
	 * The install records' key for CAT, which names the plugin and its marketplace.
	 */
	private static final String CAT_KEY = "cat@cat";

	/**
	 * Prints the artifact as {@code key=value} lines, or the reason that it cannot be found to standard error.
	 *
	 * @param args no arguments
	 */
	public static void main(String[] args)
	{
		if (args.length != 0)
		{
			System.err.println("Usage: claude-artifact");
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
	 * Finds the Claude CAT plugin for the Codex plugin that holds the running code.
	 *
	 * @param environment the process environment, as {@link #execute(Map, Path, Platform)} reads it
	 * @return the {@code artifact}, {@code runner} and {@code source} lines
	 * @throws NullPointerException if {@code environment} is null
	 * @throws IOException          if the running code is not loaded from a CAT plugin, or the plugin cannot be found
	 */
	public static String execute(Map<String, String> environment) throws IOException
	{
		Path plugin;
		try
		{
			plugin = PluginDirectory.of(ClaudeArtifact.class);
		}
		catch (IllegalStateException e)
		{
			throw new IOException(e.getMessage(), e);
		}
		return execute(environment, plugin, Platform.current());
	}

	/**
	 * Finds the Claude CAT plugin that Claude Code's install records name, in its version folder in CAT's Claude
	 * marketplace.
	 *
	 * @param environment the process environment: the user's home directory ({@code HOME}, or {@code USERPROFILE} on
	 *                    Windows) and {@code CLAUDE_CONFIG_DIR}, the Claude Code configuration directory, which
	 *                    defaults to {@code .claude} in the home directory
	 * @param plugin      the version folder of the Codex plugin that runs the command
	 * @param platform    the platform that the plugins were built for and run on
	 * @return the {@code artifact}, {@code runner} and {@code source} lines
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the home directory is not set, the install records cannot be read, Claude Code
	 *                              has no CAT plugin of this plugin's release, or that plugin is incomplete
	 */
	public static String execute(Map<String, String> environment, Path plugin, Platform platform) throws IOException
	{
		requireThat(environment, "environment").isNotNull();
		requireThat(plugin, "plugin").isNotNull();
		requireThat(platform, "platform").isNotNull();
		ClaudeInstallation claude;
		try
		{
			claude = ClaudeInstallation.fromEnvironment(environment, platform.homeDirectory(environment));
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException(e.getMessage(), e);
		}
		return OtherHarnessPlugin.select(plugin, claude, "claude", "Claude Code",
			installed(claude.home().resolve(RECORDS)), MANIFEST, "claude-runner", platform).format();
	}

	/**
	 * Returns the CAT plugin versions that Claude Code's install records name.
	 *
	 * @param records the install records
	 * @return the versions in record order, or none when the records do not exist
	 * @throws IOException if the records cannot be read, are not valid JSON or are of another format version
	 */
	private static List<String> installed(Path records) throws IOException
	{
		if (!Files.isRegularFile(records, LinkOption.NOFOLLOW_LINKS))
			return List.of();
		JsonNode root;
		try
		{
			root = JsonMapper.builder().build().readTree(BoundedInput.readUtf8(records, MAXIMUM_RECORDS_BYTES,
				"Claude Code install records"));
		}
		catch (JacksonException e)
		{
			throw new IOException("The install records " + records + " are not valid JSON", e);
		}
		int version = root.path("version").asInt(-1);
		if (version != RECORDS_VERSION)
		{
			throw new IOException("The install records " + records + " have format version " + version +
				", not " + RECORDS_VERSION);
		}
		List<String> versions = new ArrayList<>();
		for (JsonNode entry : root.path("plugins").path(CAT_KEY))
		{
			String text = entry.path("version").asString("");
			if (!text.isBlank() && !versions.contains(text))
				versions.add(text);
		}
		return versions;
	}

	/**
	 * Prevents instantiation.
	 */
	private ClaudeArtifact()
	{
	}
}
