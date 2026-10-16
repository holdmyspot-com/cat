package io.github.cowwoc.cat.distribution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_16LE;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.Platform;
import io.github.cowwoc.cat.common.PortableFiles;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests that each hook of the Codex plugin starts its launcher when Codex runs the hook's command. Codex writes the
 * plugin's root directory into the command in place of {@code ${PLUGIN_ROOT}} and sets the environment variable
 * {@code PLUGIN_ROOT} to it, then runs {@code commandWindows} in PowerShell on Windows, and {@code command} in a POSIX
 * shell elsewhere.
 */
public final class CodexPluginHooksTest
{
	/**
	 * The name of the launcher that each hook event starts.
	 */
	private static final Map<String, String> LAUNCHERS = Map.of("SessionStart", "session-start",
		"PostToolUse", "post-tool-use", "SubagentStart", "subagent-start");
	/**
	 * The start of the name of the plugin's root directory, which holds a space, a single quote and a dollar sign, as a
	 * user's name can.
	 */
	private static final String PLUGIN_ROOT_PREFIX = "cat plugin's $HOME hooks-";
	/**
	 * An event of a shell command that runs {@code run-part}, with text that is not ASCII.
	 */
	private static final String PART_EVENT = "{\"tool_input\":{\"command\":\"run-part caf\u00e9 \u2014\"}}";

	/** Creates a test instance. */
	public CodexPluginHooksTest()
	{
	}

	/**
	 * Verifies that each hook's command for this platform starts the launcher of its event, from a plugin root
	 * directory whose name holds a space, a single quote and a dollar sign, and passes the event to the launcher
	 * unchanged.
	 *
	 * @throws IOException          if the hooks cannot be read or run
	 * @throws InterruptedException if the thread is interrupted while a hook runs
	 */
	@Test
	public void hooksStartTheirLaunchers() throws IOException, InterruptedException
	{
		JsonNode hooks = readHooks();
		Platform platform = Platform.current();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path pluginRoot = cleanup.add(Files.createTempDirectory(PLUGIN_ROOT_PREFIX));
			Path launchers = Files.createDirectories(pluginRoot.resolve("hooks"));
			int commands = 0;
			for (Entry<String, JsonNode> event : hooks.get("hooks").properties())
			{
				String launcher = LAUNCHERS.get(event.getKey());
				requireThat(launcher, event.getKey()).isNotNull();
				writeLauncher(launchers, launcher, platform);
				for (JsonNode group : event.getValue())
				{
					for (JsonNode hook : group.get("hooks"))
					{
						String output = run(hook, pluginRoot, platform, PART_EVENT);
						requireThat(output, event.getKey()).isEqualTo("started " + launcher + "\n" + PART_EVENT);
						++commands;
					}
				}
			}
			requireThat(commands, "commands").isEqualTo(LAUNCHERS.size());
		}
	}

	/**
	 * Verifies that the {@code PostToolUse} hook starts CAT's Java hook for an event that mentions {@code run-part} or
	 * {@code read-part}, passing it the event unchanged, and passes every other event over without starting it.
	 *
	 * @throws IOException          if the hooks cannot be read or run
	 * @throws InterruptedException if the thread is interrupted while a hook runs
	 */
	@Test
	public void postToolUseStartsJavaOnlyForPartCommands() throws IOException, InterruptedException
	{
		JsonNode hook = readHooks().get("hooks").get("PostToolUse").get(0).get("hooks").get(0);
		Platform platform = Platform.current();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path pluginRoot = cleanup.add(Files.createTempDirectory(PLUGIN_ROOT_PREFIX));
			Path launchers = Files.createDirectories(pluginRoot.resolve("hooks"));
			Path client = Files.createDirectories(pluginRoot.resolve("client").resolve("bin"));
			if (platform.isWindows())
			{
				copyResource("hooks/post-tool-use.cmd", launchers.resolve("post-tool-use.cmd"));
				Files.writeString(client.resolve("post-tool-use.bat"), "@echo off\r\necho started\r\nfindstr \"^\"\r\n" +
					"exit /b 0\r\n");
			}
			else
			{
				makeExecutable(copyResource("hooks/post-tool-use.sh", launchers.resolve("post-tool-use.sh")));
				makeExecutable(Files.writeString(client.resolve("post-tool-use"), "#!/bin/sh\necho started\ncat\n"));
			}
			String readPartEvent = PART_EVENT.replace("run-part", "read-part");

			requireThat(run(hook, pluginRoot, platform, PART_EVENT), "runPart").
				isEqualTo("started\n" + PART_EVENT);
			requireThat(run(hook, pluginRoot, platform, readPartEvent), "readPart").
				isEqualTo("started\n" + readPartEvent);
			requireThat(run(hook, pluginRoot, platform, "{\"tool_input\":{\"command\":\"ls\"}}"), "other").isEmpty();
		}
	}

	/**
	 * Returns the Codex plugin's hook declarations.
	 *
	 * @return the parsed {@code hooks/hooks.json}
	 * @throws IOException if the file cannot be read
	 */
	private static JsonNode readHooks() throws IOException
	{
		try (InputStream in = pluginResource("hooks/hooks.json").openStream())
		{
			return JsonMapper.builder().build().readTree(in);
		}
	}

	/**
	 * Returns a file of the Codex plugin.
	 *
	 * @param path the file's path relative to the plugin's root directory
	 * @return the file's location
	 * @throws IOException if the class path cannot be read
	 */
	private static URL pluginResource(String path) throws IOException
	{
		ClassLoader loader = CodexPluginHooksTest.class.getClassLoader();
		URL manifest = loader.getResource(".codex-plugin/plugin.json");
		requireThat(manifest, "manifest").isNotNull();
		String root = manifest.toString().substring(0,
			manifest.toString().length() - ".codex-plugin/plugin.json".length());
		// Other plugins on the class path have hooks too; the Codex plugin's file shares the manifest's root
		for (URL resource : Collections.list(loader.getResources(path)))
		{
			if (resource.toString().equals(root + path))
				return resource;
		}
		throw new AssertionError("The Codex plugin has no " + path + " beside " + manifest);
	}

	/**
	 * Copies a file of the Codex plugin.
	 *
	 * @param path   the file's path relative to the plugin's root directory
	 * @param target the copy's path
	 * @return {@code target}
	 * @throws IOException if the file cannot be copied
	 */
	private static Path copyResource(String path, Path target) throws IOException
	{
		try (InputStream in = pluginResource(path).openStream())
		{
			Files.copy(in, target);
		}
		return target;
	}

	/**
	 * Lets every user run a POSIX script, on a file system that has permission modes.
	 *
	 * @param script the script
	 * @throws IOException if the permissions cannot be changed
	 */
	private static void makeExecutable(Path script) throws IOException
	{
		if (PortableFiles.hasModes(script))
			Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
	}

	/**
	 * Writes a launcher that prints that it started, then copies its standard input to its standard output, in the
	 * form that the platform runs.
	 *
	 * @param directory the directory of the launchers
	 * @param name      the launcher's name without its extension
	 * @param platform  the platform
	 * @throws IOException if the launcher cannot be written
	 */
	private static void writeLauncher(Path directory, String name, Platform platform) throws IOException
	{
		if (platform.isWindows())
		{
			Files.writeString(directory.resolve(name + ".cmd"), "@echo off\r\necho started " + name + "\r\n" +
				"findstr \"^\"\r\nexit /b 0\r\n");
			return;
		}
		makeExecutable(Files.writeString(directory.resolve(name + ".sh"), "#!/bin/sh\necho started " + name + "\ncat\n"));
	}

	/**
	 * Runs a hook's command for a platform the way that Codex runs it.
	 *
	 * @param hook       the hook's declaration
	 * @param pluginRoot the plugin's root directory
	 * @param platform   the platform
	 * @param event      the event that the command reads from its standard input
	 * @return the command's standard output, with Windows line breaks as {@code \n}, without leading or trailing white
	 *   space
	 * @throws IOException          if the command cannot be run or fails
	 * @throws InterruptedException if the thread is interrupted while the command runs
	 */
	private static String run(JsonNode hook, Path pluginRoot, Platform platform, String event)
		throws IOException, InterruptedException
	{
		List<String> arguments;
		if (platform.isWindows())
		{
			String command = hook.get("commandWindows").asString().replace("${PLUGIN_ROOT}", pluginRoot.toString());
			// An encoded command reaches PowerShell unchanged, whatever quotes it holds
			arguments = List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand",
				Base64.getEncoder().encodeToString(command.getBytes(UTF_16LE)));
		}
		else
		{
			String command = hook.get("command").asString().replace("${PLUGIN_ROOT}", pluginRoot.toString());
			arguments = List.of("sh", "-c", command);
		}
		ProcessBuilder builder = new ProcessBuilder(arguments);
		builder.environment().put("PLUGIN_ROOT", pluginRoot.toString());
		try (Process process = builder.start())
		{
			try (OutputStream in = process.getOutputStream())
			{
				in.write((event + "\n").getBytes(UTF_8));
			}
			catch (IOException _)
			{
				// A command that exits without reading the event fails the checks of its output and exit status below
			}
			// The command writes too little to fill the pipe of its standard error while its standard output is read
			String output = new String(process.getInputStream().readAllBytes(), UTF_8);
			String error = new String(process.getErrorStream().readAllBytes(), UTF_8);
			int status = process.waitFor();
			requireThat(status, "status").withContext(output, "output").withContext(error, "error").isEqualTo(0);
			return output.replace("\r\n", "\n").strip();
		}
	}
}
