package com.holdmyspot.cat.claude.installer.test;

import com.holdmyspot.cat.claude.layout.ClaudeInstallation;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.activation.HarnessCli;
import com.holdmyspot.cat.common.installer.fakes.test.HarnessProgram;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stands in for Claude Code's {@code claude} program in one configuration directory, answering the commands that the
 * installer runs as Claude Code 2.1 does: {@code plugin install} and {@code plugin update} record the version that the
 * added marketplace's manifest names for {@code cat}, and {@code plugin uninstall} deletes the record, and the plugin's
 * data directory unless asked to keep it.
 */
final class ClaudeProgram implements HarnessProgram.Answers
{
	/**
	 * Reads the marketplace manifest.
	 */
	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	/**
	 * The configuration directory.
	 */
	private final Path home;
	/**
	 * The marketplace that {@code plugin marketplace add} added, or null before.
	 */
	private Path marketplace;

	/**
	 * Creates the program.
	 *
	 * @param home the configuration directory
	 */
	private ClaudeProgram(Path home)
	{
		this.home = home;
	}

	/**
	 * Returns a runner of the program in one configuration directory.
	 *
	 * @param home the configuration directory
	 * @return the runner
	 */
	static HarnessCli cli(Path home)
	{
		return HarnessProgram.cli(new ClaudeProgram(home));
	}

	/**
	 * Returns the answer of Claude Code's program to {@code --version}.
	 *
	 * @return the answer
	 */
	static CommandResult versionAnswer()
	{
		return new CommandResult(0, "2.1.0 (Claude Code)\n");
	}

	@Override
	public CommandResult answer(List<String> arguments) throws IOException
	{
		String command = String.join(" ", arguments);
		if (command.equals("--version"))
			return versionAnswer();
		if (arguments.size() == 4 && command.startsWith("plugin marketplace add "))
		{
			marketplace = Path.of(arguments.get(3));
			return new CommandResult(0, "✔ Successfully added marketplace: cat (declared in user settings)\n");
		}
		return switch (command)
		{
			case "plugin install cat@cat" ->
			{
				record();
				yield new CommandResult(0, "✔ Successfully installed plugin: cat@cat (scope: user)\n");
			}
			case "plugin update cat@cat" ->
			{
				record();
				yield new CommandResult(0, "✔ Plugin \"cat\" updated. Restart to apply changes.\n");
			}
			case "plugin uninstall cat@cat", "plugin uninstall --keep-data cat@cat" ->
			{
				ClaudeInstallation installation = new ClaudeInstallation(home);
				if (!arguments.contains("--keep-data"))
					TemporaryDirectories.deleteRecursively(installation.dataDirectory());
				Files.deleteIfExists(installation.installedPlugins());
				yield new CommandResult(0, "✔ Successfully uninstalled plugin: cat (scope: user)\n");
			}
			case "plugin marketplace remove cat" -> new CommandResult(0, "✔ Successfully removed marketplace: cat\n");
			default -> throw new AssertionError("Unexpected command: " + arguments);
		};
	}

	/**
	 * Records the version that the added marketplace names for {@code cat} as the installed version.
	 *
	 * @throws IOException if the manifest cannot be read or the record cannot be written
	 */
	private void record() throws IOException
	{
		if (marketplace == null)
			throw new AssertionError("No marketplace was added");
		String version = "";
		for (JsonNode entry : MAPPER.readTree(Files.readString(marketplace.resolve(
			".claude-plugin/marketplace.json"))).path("plugins"))
		{
			if (entry.path("name").asString().equals("cat"))
				version = entry.path("version").asString();
		}
		InstalledPluginRecord.write(home, version);
	}

	/**
	 * Writes a plugin version's folder as install copies it from the plugin archive: the plugin manifest and the
	 * marketplace metadata, each stamped with the version.
	 *
	 * @param home    the configuration directory
	 * @param version the plugin version
	 * @return the version folder
	 * @throws IOException if the files cannot be written
	 */
	static Path writeVersionFolder(Path home, PluginVersion version) throws IOException
	{
		Path versionFolder = new ClaudeInstallation(home).versionFolder(version);
		Path metadata = versionFolder.resolve("install/claude-marketplace.json");
		Files.createDirectories(metadata.getParent());
		Files.writeString(metadata, """
			{"name": "cat", "owner": {"name": "cowwoc"}, "plugins": [{"name": "cat", "source": "./plugins/cat",
			"version": "%s"}]}
			""".formatted(version));
		Path manifest = versionFolder.resolve(".claude-plugin/plugin.json");
		Files.createDirectories(manifest.getParent());
		Files.writeString(manifest, "{\"name\":\"cat\",\"version\":\"" + version + "\"}\n");
		return versionFolder;
	}
}
