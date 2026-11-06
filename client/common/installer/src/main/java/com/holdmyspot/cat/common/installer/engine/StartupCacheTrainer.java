package com.holdmyspot.cat.common.installer.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import com.holdmyspot.cat.common.runner.RuntimeRequirement;
import com.holdmyspot.cat.common.runner.StartupCache;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Writes the {@link StartupCache startup cache} of each plugin directory that an operation activated, so that the
 * plugin's programs start faster.
 * <p>
 * Java accepts a cache only for the module path that it was trained with, so each plugin directory is trained where
 * it was placed: one run of its session-start program through the plugin's {@code bin/java} wrapper, which
 * passes the same Java options and module path as the plugin's launchers, records what the program loads, and a
 * second run writes the cache from that record. Java also accepts a cache only for the runtime image that trained
 * it, and the wrapper runs the runtime that the directory's runtime binding names, so the caller binds the directory to
 * the runtime that is to run it before training. The program runs against a scratch home, temporary folder and project
 * that are removed afterwards, with an environment that holds nothing of the caller's except its program search path,
 * and its output is discarded. A plugin directory that cannot be trained keeps no cache and no partial file, and its
 * launchers start without one; training never fails the operation. A process with administrative rights trains
 * nothing, so that the plugin's program never runs with those rights.
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
	private final BooleanSupplier elevated;

	/**
	 * Creates a trainer.
	 *
	 * @param scratch  the directory below which each training run gets its scratch folders, which is created when
	 *                 absent
	 * @param timeout  the longest time that each of a training's two Java runs may take before it is stopped
	 * @param elevated tells whether this process runs with administrative rights, such as
	 *                 {@link ProcessElevation#isElevated(Path)}
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code timeout} is not positive
	 */
	public StartupCacheTrainer(Path scratch, Duration timeout, BooleanSupplier elevated)
	{
		requireThat(scratch, "scratch").isNotNull();
		requireThat(timeout, "timeout").isGreaterThan(Duration.ZERO);
		requireThat(elevated, "elevated").isNotNull();
		this.scratch = scratch.toAbsolutePath().normalize();
		this.timeout = timeout;
		this.elevated = elevated;
	}

	/**
	 * Replaces a plugin directory's startup cache with one trained at the directory's location. When this process runs
	 * with administrative rights, the plugin has no training description, or either Java run fails or takes too long,
	 * the directory is left without a cache.
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
			if (elevated.getAsBoolean())
				return false;
			Path description = plugin.resolve(StartupCache.TRAINING);
			if (!Files.exists(description, NOFOLLOW_LINKS))
				return false;
			Training training = Training.read(description);
			work = StateFiles.createTemporaryDirectory(scratch, "training-");
			Path configuration = work.resolve("tmp").resolve("session-start.aotconf");
			TrainingRun recording = plan(plugin, training, work,
				List.of("-XX:AOTMode=record", "-XX:AOTConfiguration=" + configuration), System.getenv(),
				Platform.current());
			for (Path directory : recording.directories())
				Files.createDirectory(directory);
			Files.writeString(recording.input(), training.input(), UTF_8);
			OptionalInt recorded = run(recording);
			if (!succeeded(recorded, Files.isRegularFile(configuration, NOFOLLOW_LINKS)))
				return false;
			Files.createDirectories(cacheDirectory);
			Path partial = cacheDirectory.resolve(StartupCache.FILE.getFileName() + ".partial");
			TrainingRun writing = plan(plugin, training, work, List.of("-XX:AOTMode=create",
				"-XX:AOTConfiguration=" + configuration, "-XX:AOTCache=" + partial), System.getenv(),
				Platform.current());
			OptionalInt created = run(writing);
			if (!succeeded(created, Files.isRegularFile(partial, NOFOLLOW_LINKS)))
				return false;
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
			cleanUp(written, cacheDirectory, work);
		}
	}

	/**
	 * Plans one Java run of a training: the plugin's {@code java} wrapper with the run's options and the training's
	 * arguments, in an environment that holds the caller's program search path, the variables that Windows programs need
	 * to start, a scratch home and temporary folder, a Git ceiling at the scratch folder, and the plugin directory in the
	 * plugin's variable.
	 * <p>
	 * The scratch folders lie below {@code work}: {@code home}, {@code tmp} and {@code project}, which is the run's
	 * working directory, and the file {@code input}, which the run reads its standard input from.
	 *
	 * @param plugin   the plugin directory
	 * @param training the plugin's training description
	 * @param work     the training's scratch folder
	 * @param options  the Java options of this run
	 * @param caller   the caller's environment
	 * @param platform the platform that the run is on
	 * @return the run
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static TrainingRun plan(Path plugin, Training training, Path work, List<String> options,
		Map<String, String> caller, Platform platform)
	{
		requireThat(plugin, "plugin").isNotNull();
		requireThat(training, "training").isNotNull();
		requireThat(work, "work").isNotNull();
		requireThat(options, "options").isNotNull();
		requireThat(caller, "caller").isNotNull();
		requireThat(platform, "platform").isNotNull();

		Path home = work.resolve("home");
		Path temporary = work.resolve("tmp");
		Path project = work.resolve("project");
		Map<String, String> environment = new HashMap<>();
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
		environment.putAll(HarnessEnvironment.homeVariables(home, platform));
		for (String name : List.of("TMPDIR", "TMP", "TEMP"))
			environment.put(name, temporary.toString());
		HarnessEnvironment.preventGitDiscoveryAbove(environment, work);
		environment.put(training.rootVariable(), plugin.toString());

		String wrapper = "java";
		if (platform.isWindows())
			wrapper = "java.cmd";
		List<String> command = new ArrayList<>();
		command.add(plugin.resolve(PluginDirectory.BIN).resolve(wrapper).toString());
		command.addAll(options);
		command.addAll(training.arguments());
		return new TrainingRun(List.copyOf(command), Map.copyOf(environment), project, work.resolve("input"),
			List.of(home, temporary, project));
	}

	/**
	 * Decides whether a Java run of a training did what the training needs.
	 *
	 * @param status        the status that the run ended with, or empty if it did not end within the time limit
	 * @param outputWritten {@code true} if the run wrote the file that it is to write
	 * @return {@code true} if the run ended in time with status 0 and wrote its file
	 * @throws NullPointerException if {@code status} is null
	 */
	public static boolean succeeded(OptionalInt status, boolean outputWritten)
	{
		requireThat(status, "status").isNotNull();
		return status.isPresent() && status.getAsInt() == 0 && outputWritten;
	}

	/**
	 * Runs a planned run once, discarding its output and stopping it, with every process that it started, when it takes
	 * too long.
	 *
	 * @param run the run
	 * @return the status that the program ended with, or empty if it did not end within the time limit
	 * @throws IOException          if the program cannot be started
	 * @throws InterruptedException if the thread is interrupted while waiting
	 */
	private OptionalInt run(TrainingRun run) throws IOException, InterruptedException
	{
		ProcessBuilder builder = new ProcessBuilder(run.command()).
			directory(run.directory().toFile()).
			redirectInput(run.input().toFile()).
			redirectOutput(ProcessBuilder.Redirect.DISCARD).
			redirectError(ProcessBuilder.Redirect.DISCARD);
		builder.environment().clear();
		builder.environment().putAll(run.environment());
		try (Process process = builder.start())
		{
			try
			{
				if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS))
					return OptionalInt.empty();
				return OptionalInt.of(process.exitValue());
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
	 * Ends a training: removes its scratch folder and, unless it wrote the cache, the plugin's cache folder, which may
	 * hold a partial file. A folder that cannot be deleted is left, because a later training of the same plugin directory
	 * deletes the cache folder first and the scratch folder is below a directory that the caller owns.
	 *
	 * @param written        {@code true} if the training wrote the cache
	 * @param cacheDirectory the plugin's cache folder, which may be absent
	 * @param work           the training's scratch folder, or {@code null} if the training made none
	 * @throws NullPointerException if {@code cacheDirectory} is null
	 */
	public static void cleanUp(boolean written, Path cacheDirectory, Path work)
	{
		requireThat(cacheDirectory, "cacheDirectory").isNotNull();
		if (!written)
			deleteQuietly(cacheDirectory);
		if (work != null)
			deleteQuietly(work);
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
	public record Training(List<String> arguments, String rootVariable, String input)
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

	/**
	 * One Java run of a training, as it is started.
	 *
	 * @param command     the program and its arguments
	 * @param environment the whole environment of the run
	 * @param directory   the run's working directory
	 * @param input       the file that the run reads its standard input from
	 * @param directories the scratch folders that the run needs, which are created before it starts
	 */
	public record TrainingRun(List<String> command, Map<String, String> environment, Path directory, Path input,
		List<Path> directories)
	{
	}
}
