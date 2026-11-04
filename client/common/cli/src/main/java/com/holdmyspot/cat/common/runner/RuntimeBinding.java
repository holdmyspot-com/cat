package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Reads and writes the file that binds a CAT plugin directory to the Java runtime its launchers run.
 *
 * <p>
 * Release archives hold no binding. The installation that places a plugin directory writes it, so each directory
 * names the runtime of its own installation, and a copy of the directory keeps that runtime. The file holds exactly
 * one line, the runtime directory's absolute normalized path, followed by a line feed.
 */
public final class RuntimeBinding
{
	/**
	 * The binding's location, relative to the plugin directory.
	 */
	public static final Path PATH = PluginDirectory.BIN.resolve("runtime-binding");
	/**
	 * The largest binding that CAT reads.
	 */
	private static final int MAXIMUM_BINDING_BYTES = 65_536;

	/**
	 * Returns the runtime that a plugin directory is bound to.
	 *
	 * @param plugin the plugin directory
	 * @return the bound runtime directory, an absolute normalized path
	 * @throws NullPointerException if {@code plugin} is null
	 * @throws IOException          if the binding is missing, is not a regular file, or does not hold exactly one line
	 *                              with an absolute normalized path. The message names the binding file.
	 */
	public static Path read(Path plugin) throws IOException
	{
		requireThat(plugin, "plugin").isNotNull();
		Path binding = plugin.toAbsolutePath().normalize().resolve(PATH);
		if (!Files.isRegularFile(binding, NOFOLLOW_LINKS))
		{
			throw new IOException("The plugin has no runtime binding " + binding + ". Install the plugin through " +
				"the CAT updater, which binds it to its installation's Java runtime.");
		}
		String content = BoundedInput.readUtf8(binding, MAXIMUM_BINDING_BYTES, "Runtime binding");
		String line = content.substring(0, Math.max(content.length() - 1, 0));
		if (!content.endsWith("\n") || line.isEmpty() || line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0)
		{
			throw new IOException("The runtime binding " + binding + " must hold exactly one line, the runtime " +
				"directory's absolute path, followed by a line feed.");
		}
		Path runtime;
		try
		{
			runtime = Path.of(line);
		}
		catch (InvalidPathException e)
		{
			throw new IOException("The runtime binding " + binding + " names an invalid path: " + line, e);
		}
		if (!runtime.isAbsolute() || !runtime.equals(runtime.normalize()))
		{
			throw new IOException("The runtime binding " + binding + " names " + line + ", which is not an absolute " +
				"normalized path.");
		}
		return runtime;
	}

	/**
	 * Returns the runtime that a plugin directory is bound to, after checking that its launchers can run that
	 * runtime's {@code java} program.
	 *
	 * @param plugin   the plugin directory
	 * @param platform the platform that the plugin runs on, which names the program {@code bin/java.exe} on Windows
	 *                 and {@code bin/java} elsewhere
	 * @return the bound runtime directory, an absolute normalized path
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the binding does not satisfy {@link #read(Path)}, or the runtime's {@code java}
	 *                              program is not an executable regular file. The message names the binding file.
	 */
	public static Path readUsable(Path plugin, Platform platform) throws IOException
	{
		requireThat(platform, "platform").isNotNull();
		Path runtime = read(plugin);
		Path java;
		if (platform.isWindows())
			java = runtime.resolve("bin/java.exe");
		else
			java = runtime.resolve("bin/java");
		if (!Files.isRegularFile(java) || !Files.isExecutable(java))
		{
			throw new IOException("The runtime binding " + plugin.toAbsolutePath().normalize().resolve(PATH) +
				" names " + runtime + ", whose program " + java + " is not executable. Rerun the CAT entry command to " +
				"reinstall this harness's Java runtime.");
		}
		return runtime;
	}

	/**
	 * Binds a plugin directory to a runtime, replacing any earlier binding in one step, so that a launcher that starts
	 * meanwhile reads either binding completely.
	 *
	 * @param plugin  the plugin directory
	 * @param runtime the runtime directory, an absolute normalized path
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code runtime} is not an absolute normalized path
	 * @throws IOException              if the binding cannot be written
	 */
	public static void write(Path plugin, Path runtime) throws IOException
	{
		requireThat(plugin, "plugin").isNotNull();
		requireThat(runtime, "runtime").isNotNull();
		requireThat(runtime.isAbsolute(), "runtime.isAbsolute()").isTrue();
		requireThat(runtime, "runtime").isEqualTo(runtime.normalize(), "runtime.normalize()");
		Path binding = plugin.toAbsolutePath().normalize().resolve(PATH);
		Files.createDirectories(binding.getParent());
		Path staged = Files.createTempFile(binding.getParent(), ".runtime-binding-", ".tmp");
		try
		{
			Files.writeString(staged, runtime + "\n", UTF_8);
			Files.move(staged, binding, ATOMIC_MOVE, REPLACE_EXISTING);
		}
		finally
		{
			Files.deleteIfExists(staged);
		}
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private RuntimeBinding()
	{
	}
}
