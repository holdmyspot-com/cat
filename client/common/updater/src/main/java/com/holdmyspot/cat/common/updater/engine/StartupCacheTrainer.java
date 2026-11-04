package com.holdmyspot.cat.common.updater.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.StartupCache;
import com.holdmyspot.cat.common.updater.activation.ActivationStep;
import com.holdmyspot.cat.common.updater.activation.AddDirectory;
import com.holdmyspot.cat.common.updater.activation.PluginActivation;
import com.holdmyspot.cat.common.updater.activation.ReplaceDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Writes the {@link StartupCache startup cache} of each plugin directory that an operation activated, so that the
 * plugin's programs start faster.
 * <p>
 * Java accepts a cache only for the module path that it was trained with, so each plugin directory is trained where
 * it was placed: one run of its session-start program through the plugin's {@code client/bin/java} wrapper, which
 * passes the same Java options and module path as the plugin's launchers, records what the program loads, and a
 * second run writes the cache from that record. The program runs against a scratch home, temporary folder and project
 * that are removed afterwards, with an environment that holds nothing of the caller's except its program search path,
 * and its output is discarded. A plugin directory that cannot be trained keeps no cache and no partial file, and its
 * launchers start without one; training never fails the operation.
 */
public final class StartupCacheTrainer
{
	/**
	 * The members of the training description.
	 */
	private static final Set<String> MEMBERS = Set.of("format", "arguments", "plugin_root_variable", "input");
	/**
	 * The form of an environment variable's name.
	 */
	private static final Pattern VARIABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
	/**
	 * The variables that a Windows program needs to start, which the training copies from the caller.
	 */
	private static final List<String> WINDOWS_VARIABLES = List.of("SystemRoot", "SystemDrive", "ComSpec", "PATHEXT",
		"windir");
	private final Path scratch;
	private final Duration timeout;

	/**
	 * Creates a trainer.
	 *
	 * @param scratch the directory below which each training run gets its scratch folders, which is created when
	 *                absent
	 * @param timeout the longest time that each of a training's two Java runs may take before it is stopped
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code timeout} is not positive
	 */
	public StartupCacheTrainer(Path scratch, Duration timeout)
	{
		requireThat(scratch, "scratch").isNotNull();
		requireThat(timeout, "timeout").isGreaterThan(Duration.ZERO);
		this.scratch = scratch.toAbsolutePath().normalize();
		this.timeout = timeout;
	}

	/**
	 * Writes the startup cache of every plugin directory that an install, update or rollback activated: each directory
	 * that the harness's plan for the active version replaces or adds. Any other outcome is returned unchanged.
	 *
	 * @param result     the outcome of the operation
	 * @param activation the harness's activation provider
	 * @param home       the harness home that the operation acted on
	 * @return {@code result}, recording whether every activated plugin directory got its startup cache
	 * @throws NullPointerException if any of the arguments are null
	 */
	public UpdateResult afterActivation(UpdateResult result, PluginActivation activation, Path home)
	{
		requireThat(result, "result").isNotNull();
		requireThat(activation, "activation").isNotNull();
		requireThat(home, "home").isNotNull();
		switch (result.result())
		{
			case INSTALLED, UPDATED, ROLLED_BACK ->
			{
			}
			default ->
			{
				return result;
			}
		}
		if (result.version().isEmpty())
			return result.withAotCache(false);
		List<Path> plugins = new ArrayList<>();
		try
		{
			for (ActivationStep step : activation.plan(home, result.version().get()))
			{
				switch (step)
				{
					case ReplaceDirectory replace -> plugins.add(replace.live());
					case AddDirectory add -> plugins.add(add.target());
					default ->
					{
					}
				}
			}
		}
		catch (IOException | IllegalArgumentException _)
		{
			return result.withAotCache(false);
		}
		boolean written = !plugins.isEmpty();
		for (Path plugin : plugins)
			written &= train(plugin);
		return result.withAotCache(written);
	}

	/**
	 * Replaces a plugin directory's startup cache with one trained at the directory's location. Without a training
	 * description, or when either Java run fails or takes too long, the directory is left without a cache.
	 *
	 * @param plugin the plugin directory
	 * @return {@code true} if the cache was written
	 * @throws NullPointerException if {@code plugin} is null
	 */
	public boolean train(Path plugin)
	{
		requireThat(plugin, "plugin").isNotNull();
		Path cacheDirectory = plugin.resolve(StartupCache.DIRECTORY);
		boolean written = false;
		Path work = null;
		try
		{
			StateFiles.delete(cacheDirectory);
			Path description = plugin.resolve(StartupCache.TRAINING);
			if (!Files.exists(description, NOFOLLOW_LINKS))
				return false;
			Training training = Training.read(description);
			work = StateFiles.createTemporaryDirectory(scratch, "training-");
			Path project = Files.createDirectory(work.resolve("project"));
			Path temporary = Files.createDirectory(work.resolve("tmp"));
			Path input = Files.writeString(work.resolve("input"), training.input(), UTF_8);
			Map<String, String> environment = environment(work, temporary, training.rootVariable(), plugin);
			Path configuration = temporary.resolve("session-start.aotconf");
			if (!run(plugin, training, List.of("-XX:AOTMode=record", "-XX:AOTConfiguration=" + configuration),
				environment, project, input) || !Files.isRegularFile(configuration, NOFOLLOW_LINKS))
			{
				return false;
			}
			Files.createDirectories(cacheDirectory);
			Path partial = cacheDirectory.resolve(StartupCache.FILE.getFileName() + ".partial");
			if (!run(plugin, training, List.of("-XX:AOTMode=create", "-XX:AOTConfiguration=" + configuration,
				"-XX:AOTCache=" + partial), environment, project, input) ||
				!Files.isRegularFile(partial, NOFOLLOW_LINKS))
			{
				return false;
			}
			Files.move(partial, plugin.resolve(StartupCache.FILE), StandardCopyOption.ATOMIC_MOVE);
			written = true;
			return true;
		}
		catch (IOException | IllegalArgumentException _)
		{
			return false;
		}
		catch (InterruptedException _)
		{
			Thread.currentThread().interrupt();
			return false;
		}
		finally
		{
			if (!written)
				deleteQuietly(cacheDirectory);
			if (work != null)
				deleteQuietly(work);
		}
	}

	/**
	 * Returns the environment of a training run: the caller's program search path, the variables that Windows programs
	 * need to start, a scratch home and temporary folder, a Git ceiling at the scratch folder, and the plugin
	 * directory in the plugin's variable.
	 *
	 * @param work         the training's scratch folder
	 * @param temporary    the training's temporary folder
	 * @param rootVariable the variable that names the plugin directory
	 * @param plugin       the plugin directory
	 * @return the environment
	 * @throws IOException if the scratch home cannot be created
	 */
	private static Map<String, String> environment(Path work, Path temporary, String rootVariable, Path plugin)
		throws IOException
	{
		Platform platform = Platform.current();
		Map<String, String> environment = new HashMap<>();
		Map<String, String> caller = System.getenv();
		List<String> copied = new ArrayList<>(List.of("PATH"));
		if (platform.isWindows())
			copied.addAll(WINDOWS_VARIABLES);
		for (Map.Entry<String, String> entry : caller.entrySet())
		{
			for (String name : copied)
			{
				if (entry.getKey().equalsIgnoreCase(name))
					environment.put(entry.getKey(), entry.getValue());
			}
		}
		environment.putAll(HarnessEnvironment.homeVariables(Files.createDirectory(work.resolve("home")), platform));
		for (String name : List.of("TMPDIR", "TMP", "TEMP"))
			environment.put(name, temporary.toString());
		HarnessEnvironment.preventGitDiscoveryAbove(environment, work);
		environment.put(rootVariable, plugin.toString());
		return environment;
	}

	/**
	 * Runs the plugin's session-start program once through its {@code java} wrapper, discarding its output and
	 * stopping it, with every process that it started, when it takes too long.
	 *
	 * @param plugin      the plugin directory
	 * @param training    the plugin's training description
	 * @param options     the Java options of this run
	 * @param environment the run's environment
	 * @param project     the run's working directory
	 * @param input       the file that the program reads its standard input from
	 * @return {@code true} if the program ended in time with status 0
	 * @throws IOException          if the program cannot be started
	 * @throws InterruptedException if the thread is interrupted while waiting
	 */
	private boolean run(Path plugin, Training training, List<String> options, Map<String, String> environment,
		Path project, Path input) throws IOException, InterruptedException
	{
		String wrapper = "java";
		if (Platform.current().isWindows())
			wrapper = "java.cmd";
		List<String> command = new ArrayList<>();
		command.add(plugin.resolve("client/bin").resolve(wrapper).toString());
		command.addAll(options);
		command.addAll(training.arguments());
		ProcessBuilder builder = new ProcessBuilder(command).
			directory(project.toFile()).
			redirectInput(input.toFile()).
			redirectOutput(ProcessBuilder.Redirect.DISCARD).
			redirectError(ProcessBuilder.Redirect.DISCARD);
		builder.environment().clear();
		builder.environment().putAll(environment);
		try (Process process = builder.start())
		{
			try
			{
				return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS) && process.exitValue() == 0;
			}
			finally
			{
				if (process.isAlive())
				{
					process.descendants().forEach(ProcessHandle::destroyForcibly);
					process.destroyForcibly();
					process.waitFor();
				}
			}
		}
	}

	/**
	 * Deletes a directory that a training wrote, leaving it when it cannot be deleted.
	 *
	 * @param directory the directory, which may be absent
	 */
	private static void deleteQuietly(Path directory)
	{
		try
		{
			StateFiles.delete(directory);
		}
		catch (IOException _)
		{
			// A later training of the same plugin directory deletes it first
		}
	}

	/**
	 * A plugin's description of how to train its startup cache.
	 *
	 * @param arguments    the arguments that the {@code java} wrapper runs the session-start program with
	 * @param rootVariable the environment variable that names the plugin directory to the program
	 * @param input        the program's standard input
	 */
	private record Training(List<String> arguments, String rootVariable, String input)
	{
		/**
		 * Reads a training description.
		 *
		 * @param file the description
		 * @return the description
		 * @throws IOException if the description is missing, a link, too large or malformed
		 */
		static Training read(Path file) throws IOException
		{
			JsonNode root = RuntimeRequirement.readObject(file, MEMBERS, "startup cache training description");
			JsonNode argumentsNode = root.get("arguments");
			if (!argumentsNode.isArray() || argumentsNode.isEmpty())
				throw new IOException("The member arguments must be a non-empty array of strings: " + file);
			List<String> arguments = new ArrayList<>();
			for (JsonNode argument : argumentsNode)
			{
				if (!argument.isString() || argument.stringValue().isEmpty())
					throw new IOException("The member arguments must be a non-empty array of strings: " + file);
				arguments.add(argument.stringValue());
			}
			JsonNode variable = root.get("plugin_root_variable");
			if (!variable.isString() || !VARIABLE.matcher(variable.stringValue()).matches())
				throw new IOException("The member plugin_root_variable must name an environment variable: " + file);
			JsonNode input = root.get("input");
			if (!input.isString())
				throw new IOException("The member input must be a string: " + file);
			return new Training(List.copyOf(arguments), variable.stringValue(), input.stringValue());
		}
	}
}
