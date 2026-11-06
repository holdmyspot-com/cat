package com.holdmyspot.cat.distribution.bundle.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.distribution.bundle.ModuleDescriptorPatcher;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import org.testng.annotations.Test;

/**
 * Tests {@link ModuleDescriptorPatcher}.
 */
public final class ModuleDescriptorPatcherTest
{
	/** Creates a test instance. */
	public ModuleDescriptorPatcherTest()
	{
	}

	/**
	 * Verifies that the descriptor's source names the module and exports the packages in sorted order.
	 */
	@Test
	public void descriptorExportsSortedPackages()
	{
		List<String> lines = ModuleDescriptorPatcher.descriptorLines("example.module",
			Set.of("example.two", "example.one"));

		requireThat(lines, "lines").isEqualTo(List.of("module example.module", "{", "\texports example.one;",
			"\texports example.two;", "}"));
	}

	/**
	 * Verifies that the compiler is told to compile the descriptor as part of the patched JAR's module, into the
	 * classes folder.
	 */
	@Test
	public void compilerPatchesTheModule()
	{
		Path jar = Path.of("modules/dependency.jar");
		Path classes = Path.of("workspace/classes");
		Path descriptor = Path.of("workspace/module-info.java");

		List<String> arguments = ModuleDescriptorPatcher.javacArguments(jar, "example.module", classes, descriptor);

		requireThat(arguments, "arguments").isEqualTo(List.of("--patch-module", "example.module=" + jar, "-d",
			classes.toString(), descriptor.toString()));
	}

	/**
	 * Verifies that the archiver is told to add the compiled descriptor to the patched JAR.
	 */
	@Test
	public void archiverAddsTheDescriptor()
	{
		Path jar = Path.of("modules/dependency.jar");
		Path classes = Path.of("workspace/classes");

		List<String> arguments = ModuleDescriptorPatcher.jarArguments(jar, classes);

		requireThat(arguments, "arguments").isEqualTo(List.of("--update", "--file", jar.toString(), "-C",
			classes.toString(), "module-info.class"));
	}

	/**
	 * Verifies that the real JDK compiler and archiver give a JAR an explicit module descriptor that names the module
	 * and exports the packages, and that the source JAR stays as it was.
	 *
	 * @throws IOException if the fixture cannot be created or read
	 */
	@Test
	public void patchedJarHoldsModuleDescriptor() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-module-patch-"));
			Path source = jarOfThisTest(temporary.resolve("source.jar"));
			Path destination = temporary.resolve("modules/dependency.jar");

			Path result = new ModuleDescriptorPatcher().patch(source, destination, "example.module",
				Set.of(ModuleDescriptorPatcherTest.class.getPackageName()));

			requireThat(result, "result").isEqualTo(destination.toAbsolutePath().normalize());
			ModuleDescriptor descriptor = descriptorOf(result);
			requireThat(descriptor.name(), "name").isEqualTo("example.module");
			Set<String> exported = descriptor.exports().stream().map(ModuleDescriptor.Exports::source).
				collect(Collectors.toSet());
			requireThat(exported, "exports").isEqualTo(Set.of(ModuleDescriptorPatcherTest.class.getPackageName()));
			requireThat(entries(source), "sourceEntries").doesNotContain("module-info.class");
		}
	}

	/**
	 * Verifies that a descriptor which the real JDK compiler rejects stops the update of the JAR, and that the failure
	 * holds the compiler's diagnostics.
	 *
	 * @throws IOException if the fixture cannot be created or read
	 */
	@Test
	public void compilerFailureStopsJarUpdate() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-module-patch-failure-"));
			Path source = jarOfThisTest(temporary.resolve("source.jar"));
			Path destination = temporary.resolve("dependency.jar");
			try
			{
				new ModuleDescriptorPatcher().patch(source, destination, "example.module",
					Set.of("example.missing"));
				throw new AssertionError("Expected javac failure");
			}
			catch (IOException failure)
			{
				requireThat(failure.getMessage(), "failureMessage").contains("javac failed with exit code").
					contains("example.missing");
			}

			requireThat(entries(destination), "destinationEntries").doesNotContain("module-info.class");
		}
	}

	/**
	 * Writes a JAR that holds this test's class, so that its package exists.
	 *
	 * @param jar the JAR to write
	 * @return the JAR
	 * @throws IOException if the JAR cannot be written
	 */
	private static Path jarOfThisTest(Path jar) throws IOException
	{
		Class<?> type = ModuleDescriptorPatcherTest.class;
		try (InputStream in = type.getResourceAsStream(type.getSimpleName() + ".class");
			JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar)))
		{
			requireThat(in, "testClass").isNotNull();
			out.putNextEntry(new JarEntry(type.getName().replace('.', '/') + ".class"));
			in.transferTo(out);
			out.closeEntry();
		}
		return jar;
	}

	/**
	 * Reads the module descriptor of a JAR.
	 *
	 * @param jar the JAR
	 * @return the descriptor
	 * @throws IOException if the JAR cannot be read or holds no descriptor
	 */
	private static ModuleDescriptor descriptorOf(Path jar) throws IOException
	{
		try (JarFile file = new JarFile(jar.toFile());
			InputStream in = file.getInputStream(file.getEntry("module-info.class")))
		{
			return ModuleDescriptor.read(in);
		}
	}

	/**
	 * Lists the entries of a JAR.
	 *
	 * @param jar the JAR
	 * @return the names of its entries
	 * @throws IOException if the JAR cannot be read
	 */
	private static Set<String> entries(Path jar) throws IOException
	{
		try (JarFile file = new JarFile(jar.toFile()))
		{
			return file.stream().map(JarEntry::getName).collect(Collectors.toCollection(TreeSet::new));
		}
	}
}
