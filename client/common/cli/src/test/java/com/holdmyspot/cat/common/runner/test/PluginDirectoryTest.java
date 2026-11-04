package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Tests {@link PluginDirectory}.
 */
public final class PluginDirectoryTest
{
	/**
	 * Creates the test fixture.
	 */
	public PluginDirectoryTest()
	{
	}

	/**
	 * Verifies that a class loaded from a module file in a plugin's {@code bin/runtime/modules} directory locates that
	 * plugin.
	 *
	 * @throws Exception if the fixture cannot be written or loaded
	 */
	@Test
	public void locatesPluginOfModule() throws Exception
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-")).toAbsolutePath().normalize();
			Path module = moduleWithProbe(plugin.resolve("bin/runtime/modules/application.jar"));
			try (URLClassLoader loader = loaderOf(module))
			{
				Class<?> probe = loader.loadClass(Probe.class.getName());

				requireThat(PluginDirectory.of(probe), "plugin").isEqualTo(plugin);
			}
		}
	}

	/**
	 * Verifies that a class loaded from a module file outside a plugin's {@code bin/runtime/modules} directory is
	 * rejected, naming where it was loaded from.
	 *
	 * @throws Exception if the fixture cannot be written or loaded
	 */
	@Test
	public void rejectsModuleOutsidePlugin() throws Exception
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-not-plugin-")).toAbsolutePath().normalize();
			Path module = moduleWithProbe(root.resolve("lib/application.jar"));
			try (URLClassLoader loader = loaderOf(module))
			{
				Class<?> probe = loader.loadClass(Probe.class.getName());
				try
				{
					PluginDirectory.of(probe);
				}
				catch (IllegalStateException e)
				{
					requireThat(e.getMessage(), "message").contains(module.toString());
					return;
				}
				throw new AssertionError("The class's plugin directory was found");
			}
		}
	}

	/**
	 * Verifies that a class loaded from a module file in a {@code client/modules} directory, where plugins kept their
	 * modules before {@code bin/}, is rejected.
	 *
	 * @throws Exception if the fixture cannot be written or loaded
	 */
	@Test(expectedExceptions = IllegalStateException.class)
	public void rejectsModuleInClientFolder() throws Exception
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-")).toAbsolutePath().normalize();
			Path module = moduleWithProbe(plugin.resolve("client/modules/application.jar"));
			try (URLClassLoader loader = loaderOf(module))
			{
				PluginDirectory.of(loader.loadClass(Probe.class.getName()));
			}
		}
	}

	/**
	 * Verifies that a class loaded from a module file in a {@code bin/modules} directory, where plugins kept their
	 * modules before the Java runtime moved into {@code bin/runtime}, is rejected.
	 *
	 * @throws Exception if the fixture cannot be written or loaded
	 */
	@Test(expectedExceptions = IllegalStateException.class)
	public void rejectsModuleBesideRuntime() throws Exception
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-")).toAbsolutePath().normalize();
			Path module = moduleWithProbe(plugin.resolve("bin/modules/application.jar"));
			try (URLClassLoader loader = loaderOf(module))
			{
				PluginDirectory.of(loader.loadClass(Probe.class.getName()));
			}
		}
	}

	/**
	 * Verifies that a class of the Java runtime, which no plugin module holds, is rejected.
	 */
	@Test(expectedExceptions = IllegalStateException.class)
	public void rejectsRuntimeClass()
	{
		PluginDirectory.of(String.class);
	}

	/**
	 * Writes a module file that holds {@link Probe}.
	 *
	 * @param module the module file to write
	 * @return {@code module}
	 * @throws IOException if the file cannot be written
	 */
	private static Path moduleWithProbe(Path module) throws IOException
	{
		Files.createDirectories(module.getParent());
		String entry = Probe.class.getName().replace('.', '/') + ".class";
		try (InputStream in = Probe.class.getClassLoader().getResourceAsStream(entry);
			OutputStream out = Files.newOutputStream(module);
			JarOutputStream jar = new JarOutputStream(out))
		{
			requireThat(in, "probeClass").isNotNull();
			jar.putNextEntry(new JarEntry(entry));
			in.transferTo(jar);
			jar.closeEntry();
		}
		return module;
	}

	/**
	 * Returns a class loader that loads classes only from a module file and the bootstrap modules of the Java runtime.
	 * The platform class loader would find {@link Probe} in this test's own module instead.
	 *
	 * @param module the module file
	 * @return the class loader
	 * @throws IOException if the module's location cannot be converted to a URL
	 */
	private static URLClassLoader loaderOf(Path module) throws IOException
	{
		URL location = module.toUri().toURL();
		return new URLClassLoader(new URL[]{location}, null);
	}

	/**
	 * A class that the tests load from a module file.
	 */
	public static final class Probe
	{
		/**
		 * Creates a probe.
		 */
		public Probe()
		{
		}
	}
}
