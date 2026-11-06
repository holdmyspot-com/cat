package com.holdmyspot.cat.distribution.bundle;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.spi.ToolProvider;

/**
 * Adds an explicit module descriptor to a dependency without launching external processes.
 */
public final class ModuleDescriptorPatcher
{
	private final ToolProvider javac;
	private final ToolProvider jar;

	/**
	 * Creates a patcher backed by the current JDK's javac and jar tools.
	 *
	 * @throws UnsupportedOperationException if the current Java runtime does not provide javac or jar
	 */
	public ModuleDescriptorPatcher()
	{
		this.javac = findTool("javac");
		this.jar = findTool("jar");
	}

	/**
	 * Replaces the destination JAR with a copy of the source JAR that has an added module descriptor.
	 *
	 * @param source the original JAR
	 * @param destination the patched JAR
	 * @param moduleName the module name
	 * @param exportedPackages the packages to export
	 * @return the patched JAR
	 * @throws NullPointerException if {@code source}, {@code destination}, {@code moduleName}, or
	 *         {@code exportedPackages} are null
	 * @throws IllegalArgumentException if {@code moduleName} is blank or {@code exportedPackages} is empty
	 * @throws IOException if the descriptor cannot be compiled or added
	 */
	public Path patch(Path source, Path destination, String moduleName, Set<String> exportedPackages) throws IOException
	{
		requireThat(source, "source").isNotNull();
		requireThat(moduleName, "moduleName").isNotBlank();
		requireThat(exportedPackages, "exportedPackages").isNotEmpty();

		destination = destination.toAbsolutePath().normalize();
		Files.createDirectories(destination.getParent());
		Files.copy(source, destination, REPLACE_EXISTING);
		Path workspace = destination.getParent().resolve(".module-info").resolve(moduleName);
		Path classes = Files.createDirectories(workspace.resolve("classes"));
		Path descriptor = workspace.resolve("module-info.java");
		Files.write(descriptor, descriptorLines(moduleName, exportedPackages));

		ToolInvocation.run(javac, "javac", javacArguments(destination, moduleName, classes, descriptor));
		ToolInvocation.run(jar, "jar", jarArguments(destination, classes));
		return destination;
	}

	/**
	 * Returns the lines of the module descriptor's source: the module, exporting the packages in sorted order.
	 *
	 * @param moduleName       the module name
	 * @param exportedPackages the packages to export
	 * @return the lines
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code moduleName} is blank or {@code exportedPackages} is empty
	 */
	public static List<String> descriptorLines(String moduleName, Set<String> exportedPackages)
	{
		requireThat(moduleName, "moduleName").isNotBlank();
		requireThat(exportedPackages, "exportedPackages").isNotEmpty();
		List<String> lines = new ArrayList<>();
		lines.add("module " + moduleName);
		lines.add("{");
		exportedPackages.stream().sorted().forEach(packageName -> lines.add("\texports " + packageName + ";"));
		lines.add("}");
		return lines;
	}

	/**
	 * Returns the arguments that compile the module descriptor against the classes of the JAR that is patched.
	 *
	 * @param patchedJar the JAR that the descriptor is compiled against
	 * @param moduleName the module name
	 * @param classes    the directory to write the compiled descriptor to
	 * @param descriptor the descriptor's source file
	 * @return the arguments of {@code javac}
	 * @throws NullPointerException if any argument is null
	 */
	public static List<String> javacArguments(Path patchedJar, String moduleName, Path classes, Path descriptor)
	{
		requireThat(patchedJar, "patchedJar").isNotNull();
		requireThat(moduleName, "moduleName").isNotNull();
		requireThat(classes, "classes").isNotNull();
		requireThat(descriptor, "descriptor").isNotNull();
		return List.of("--patch-module", moduleName + "=" + patchedJar, "-d", classes.toString(),
			descriptor.toString());
	}

	/**
	 * Returns the arguments that add the compiled module descriptor to a JAR.
	 *
	 * @param patchedJar the JAR to update
	 * @param classes    the directory that holds the compiled descriptor
	 * @return the arguments of {@code jar}
	 * @throws NullPointerException if any argument is null
	 */
	public static List<String> jarArguments(Path patchedJar, Path classes)
	{
		requireThat(patchedJar, "patchedJar").isNotNull();
		requireThat(classes, "classes").isNotNull();
		return List.of("--update", "--file", patchedJar.toString(), "-C", classes.toString(), "module-info.class");
	}

	/**
	 * Finds a JDK tool.
	 *
	 * @param name the tool name
	 * @return the tool provider
	 * @throws UnsupportedOperationException if the current Java runtime does not provide the tool
	 */
	private static ToolProvider findTool(String name)
	{
		return ToolProvider.findFirst(name).
			orElseThrow(() -> new UnsupportedOperationException("The current Java runtime does not provide " + name));
	}
}
