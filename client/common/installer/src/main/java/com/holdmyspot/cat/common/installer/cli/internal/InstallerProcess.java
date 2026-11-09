package com.holdmyspot.cat.common.installer.cli.internal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.secrets.InstallerProcessSecrets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs the installer launcher of a version folder in a new process, without a shell: on Linux and macOS the launcher
 * itself, and on Windows its PowerShell launcher through Windows PowerShell, the way the installer's permission entries
 * name it. The process gets the installer's environment with {@link #NO_QUESTIONS} set, so that it asks the user
 * nothing even where it could reach the terminal that the installer shares with it; the installer's working
 * directory; its standard input closed and the installer's standard error; and its standard output is read as its
 * result.
 * <p>
 * The Java runtime starts every process through its {@code lib/jspawnhelper} program, which a harness can delete
 * before the installer starts the launcher: Codex deletes the version folder that an update replaces while it
 * registers the new one, and the installer's bundled runtime is in that folder. The runner reads the program when it
 * is created, before any harness runs, and puts it back for the duration of a run that finds it missing, then removes
 * what it put back.
 */
public final class InstallerProcess
{
	static
	{
		InstallerProcessSecrets.setInstallerProcessAccess(InstallerProcess::new);
	}

	/**
	 * The environment variable that makes an installer ask the user no question, when it is set to a non-empty value.
	 * A change that needs the user's permission is then treated as one that the installer could not ask about.
	 */
	static final String NO_QUESTIONS = "CAT_INSTALLER_NO_QUESTIONS";

	/**
	 * The longest that the installer may run and keep its standard output open. It covers the ten-minute wait for the
	 * harness lock and the runs of the harness program, each of which may take up to five minutes.
	 */
	private static final Duration TIME_LIMIT = Duration.ofMinutes(30);
	/**
	 * The most output that is kept; further output is read and discarded so that the installer never blocks.
	 */
	private static final int MAXIMUM_OUTPUT_BYTES = 1024 * 1024;
	/**
	 * The name of the runtime's program that starts processes, in the runtime's {@code lib} folder.
	 */
	private static final String SPAWN_HELPER = "jspawnhelper";
	private final Platform platform;
	private final Map<String, String> environment;
	/**
	 * Starts the program that a process builder describes.
	 */
	private final ProcessHarnessCli.Starter starter;
	/**
	 * The runtime's program that starts processes, which may no longer exist when a run starts.
	 */
	private final Path spawnHelper;
	/**
	 * The program's contents when the runner was created, or none if there were none.
	 */
	private final byte[] spawnHelperContents;
	private final boolean spawnHelperExecutable;

	/**
	 * Creates the runner.
	 *
	 * @param platform    the running platform
	 * @param environment the installer's environment, which the launcher gets with {@link #NO_QUESTIONS} and which
	 *                    names the Windows folder in {@code SystemRoot} on Windows
	 * @throws NullPointerException if any of the arguments are null
	 */
	public InstallerProcess(Platform platform, Map<String, String> environment)
	{
		this(platform, environment, ProcessBuilder::start, Path.of(System.getProperty("java.home")));
	}

	/**
	 * Creates a runner that starts the launcher through the supplied starter instead of the operating system.
	 *
	 * @param platform    the running platform
	 * @param environment the installer's environment, which the launcher gets with {@link #NO_QUESTIONS} and which
	 *                    names the Windows folder in {@code SystemRoot} on Windows
	 * @param starter     starts the program that a process builder describes
	 * @param runtime     the Java runtime folder of the running installer
	 * @throws NullPointerException if any of the arguments are null
	 */
	private InstallerProcess(Platform platform, Map<String, String> environment, ProcessHarnessCli.Starter starter,
		Path runtime)
	{
		requireThat(platform, "platform").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(starter, "starter").isNotNull();
		requireThat(runtime, "runtime").isNotNull();
		this.platform = platform;
		this.environment = Map.copyOf(environment);
		this.starter = starter;
		this.spawnHelper = runtime.resolve("lib").resolve(SPAWN_HELPER);
		this.spawnHelperContents = readSpawnHelper(spawnHelper);
		this.spawnHelperExecutable = Files.isExecutable(spawnHelper);
	}

	/**
	 * Reads the runtime's program that starts processes.
	 *
	 * @param file the program
	 * @return the program's contents, or none if the runtime has none, such as on Windows, or it cannot be read, in
	 * 	which case the runner cannot restore it
	 */
	private static byte[] readSpawnHelper(Path file)
	{
		try
		{
			if (Files.isRegularFile(file))
				return Files.readAllBytes(file);
		}
		catch (IOException _)
		{
			// Restoring the program is only a safeguard, so a program that cannot be read is not an error
		}
		return new byte[0];
	}

	/**
	 * Puts back the runtime's program that starts processes if it was deleted.
	 *
	 * @return the file or folder that was created and is to be deleted after the run, or null if nothing was created
	 * @throws IOException if the program cannot be written
	 */
	private Path restoreSpawnHelper() throws IOException
	{
		if (spawnHelperContents.length == 0 || Files.exists(spawnHelper))
			return null;
		Path created = spawnHelper;
		for (Path parent = spawnHelper.getParent(); parent != null && Files.notExists(parent);
			parent = parent.getParent())
		{
			created = parent;
		}
		Files.createDirectories(spawnHelper.getParent());
		Files.write(spawnHelper, spawnHelperContents);
		if (spawnHelperExecutable && !spawnHelper.toFile().setExecutable(true))
			throw new IOException("Could not make " + spawnHelper + " executable");
		return created;
	}

	/**
	 * Deletes what {@link #restoreSpawnHelper()} created.
	 *
	 * @param created the file or folder, or null if nothing was created
	 * @throws IOException if it cannot be deleted
	 */
	private static void deleteRestored(Path created) throws IOException
	{
		if (created == null)
			return;
		if (Files.isDirectory(created))
			TemporaryDirectories.deleteRecursively(created);
		else
			Files.deleteIfExists(created);
	}

	/**
	 * Runs an installer launcher with arguments and waits for it to exit.
	 *
	 * @param launcher  the absolute path of the installer launcher of a version folder
	 * @param arguments the arguments after the launcher
	 * @return the exit status and the standard output
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code launcher} is relative
	 * @throws IOException              if the launcher cannot be started, does not finish in time or its output
	 *                                  cannot be read, the runtime's program that starts processes was deleted and
	 *                                  cannot be put back, or, on Windows, if the environment does not name the Windows
	 *                                  folder
	 */
	public CommandResult run(Path launcher, List<String> arguments) throws IOException
	{
		requireThat(launcher, "launcher").isAbsolute();
		requireThat(arguments, "arguments").isNotNull();
		List<String> line = command(launcher);
		line.addAll(arguments);
		ProcessBuilder builder = new ProcessBuilder(line).redirectError(ProcessBuilder.Redirect.INHERIT);
		builder.environment().clear();
		builder.environment().putAll(environment);
		builder.environment().put(NO_QUESTIONS, "1");
		Path restored = restoreSpawnHelper();
		try
		{
			return start(launcher, builder);
		}
		finally
		{
			deleteRestored(restored);
		}
	}

	/**
	 * Starts the launcher and waits for it to exit.
	 *
	 * @param launcher the launcher
	 * @param builder  describes the process
	 * @return the exit status and the standard output
	 * @throws IOException if the launcher cannot be started, does not finish in time or its output cannot be read
	 */
	private CommandResult start(Path launcher, ProcessBuilder builder) throws IOException
	{
		try (Process process = starter.start(builder))
		{
			process.getOutputStream().close();
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			List<IOException> failures = new ArrayList<>(1);
			Thread reader = Thread.ofPlatform().daemon().start(() -> read(process.getInputStream(), output, failures));
			long deadline = System.nanoTime() + TIME_LIMIT.toNanos();
			try
			{
				// A process that the installer started may keep its standard output open after the installer exited
				if (!process.waitFor(TIME_LIMIT.toMillis(), TimeUnit.MILLISECONDS) ||
					!reader.join(Duration.ofNanos(Math.max(0, deadline - System.nanoTime()))))
				{
					process.destroyForcibly();
					throw new IOException(launcher + " did not finish within " + TIME_LIMIT.toMinutes() + " minutes");
				}
			}
			catch (InterruptedException _)
			{
				process.destroyForcibly();
				Thread.currentThread().interrupt();
				throw new InterruptedIOException("Interrupted while " + launcher + " was running");
			}
			synchronized (output)
			{
				if (!failures.isEmpty())
					throw new IOException("The output of " + launcher + " could not be read", failures.getFirst());
				return new CommandResult(process.exitValue(), output.toString(UTF_8));
			}
		}
	}

	/**
	 * Returns the start of the command line that runs a launcher.
	 *
	 * @param launcher the launcher
	 * @return a modifiable list: on Windows, Windows PowerShell with the options that run the launcher as a script;
	 * 	otherwise the launcher alone
	 * @throws IOException if, on Windows, the environment does not name the Windows folder
	 */
	private List<String> command(Path launcher) throws IOException
	{
		List<String> command = new ArrayList<>();
		if (platform.isWindows())
		{
			String systemRoot = platform.environment(environment).get("SystemRoot");
			if (systemRoot == null || systemRoot.isBlank())
			{
				throw new IOException("SystemRoot does not name the Windows folder, so Windows PowerShell cannot be " +
					"found");
			}
			Path powershell = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
			command.addAll(List.of(powershell.toString(), "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
				"-File"));
		}
		command.add(launcher.toString());
		return command;
	}

	/**
	 * Reads the installer's standard output until it closes, keeping the first part of it.
	 *
	 * @param input    the standard output
	 * @param output   receives the kept part
	 * @param failures receives the failure if the output cannot be read
	 */
	private static void read(InputStream input, ByteArrayOutputStream output, List<IOException> failures)
	{
		byte[] buffer = new byte[8192];
		try (input)
		{
			for (int count = input.read(buffer); count >= 0; count = input.read(buffer))
			{
				synchronized (output)
				{
					int kept = Math.min(count, MAXIMUM_OUTPUT_BYTES - output.size());
					if (kept > 0)
						output.write(buffer, 0, kept);
				}
			}
		}
		catch (IOException e)
		{
			synchronized (output)
			{
				failures.add(e);
			}
		}
	}
}
