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
 * A packaged selector binds the plugin to the Java runtime inside its own directory. Earlier packages use an
 * installation-written binding holding one absolute normalized runtime path followed by a line feed.
 */
public final class RuntimeBinding
{
	/**
	 * The binding's location, relative to the plugin directory.
	 */
	public static final Path PATH = PluginDirectory.BIN.resolve("runtime-binding");

	/**
	 * The packaged file whose presence selects the runtime in the plugin's own {@code bin} directory. The selector
	 * takes precedence over the installation-written binding, which an older installer can still add.
	 */
	public static final Path BUNDLED_PATH = PluginDirectory.BIN.resolve("bundled-runtime");

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
	 * @throws IOException          if the bundled selector is invalid, or the legacy binding is missing, is not a
	 *                              regular file, or does not name an absolute normalized path
	 */
	public static Path read(Path plugin) throws IOException
	{
		requireThat(plugin, "plugin").isNotNull();
		Path root = plugin.toAbsolutePath().normalize();
		if (selectsBundled(root))
			return root.resolve(PluginDirectory.RUNTIME);
		Path binding = root.resolve(PATH);
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
				"directory's absolute path followed by a line feed.");
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
	 * Checks whether the plugin declares that it uses its bundled Java runtime.
	 *
	 * @param plugin the plugin directory
	 * @return whether the packaged selector exists
	 * @throws NullPointerException if {@code plugin} is null
	 * @throws IOException if the selector exists but is not a regular file
	 */
	public static boolean selectsBundled(Path plugin) throws IOException
	{
		requireThat(plugin, "plugin").isNotNull();
		Path selector = plugin.resolve(BUNDLED_PATH);
		if (Files.notExists(selector, NOFOLLOW_LINKS))
			return false;
		if (!Files.isRegularFile(selector, NOFOLLOW_LINKS))
			throw new IOException("The bundled runtime selector is not a regular file: " + selector);
		return true;
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
			Path root = plugin.toAbsolutePath().normalize();
			Path binding = root.resolve(PATH);
			if (selectsBundled(root))
				binding = root.resolve(BUNDLED_PATH);
			throw new IOException("The runtime binding " + binding + " names " + runtime + ", whose program " + java +
				" is not executable. Rerun the CAT entry command to reinstall this harness's Java runtime.");
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
