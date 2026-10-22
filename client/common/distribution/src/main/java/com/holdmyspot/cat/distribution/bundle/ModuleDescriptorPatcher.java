package com.holdmyspot.cat.distribution.bundle;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.that;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import com.holdmyspot.cat.distribution.bundle.secrets.ModuleDescriptorPatcherSecrets;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
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
	static
	{
		ModuleDescriptorPatcherSecrets.setModuleDescriptorPatcherAccess((javac, jar, source, destination, moduleName,
		exportedPackages) -> new ModuleDescriptorPatcher(javac, jar).patch(source, destination, moduleName,
			exportedPackages));
	}
	private final ToolProvider javac;
	private final ToolProvider jar;

	/**
	 * Creates a patcher backed by the current JDK's javac and jar tools.
	 *
	 * @throws UnsupportedOperationException if the current Java runtime does not provide javac or jar
	 */
	public ModuleDescriptorPatcher()
	{
		this(findTool("javac"), findTool("jar"));
	}

	/**
	 * Creates a patcher backed by tool providers.
	 *
	 * @param javac the javac tool provider
	 * @param jar the jar tool provider
	 * @throws NullPointerException if {@code javac} or {@code jar} are null
	 */
	private ModuleDescriptorPatcher(ToolProvider javac, ToolProvider jar)
	{
		requireThat(javac, "javac").isNotNull();
		requireThat(jar, "jar").isNotNull();
		this.javac = javac;
		this.jar = jar;
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
		List<String> lines = new ArrayList<>();
		lines.add("module " + moduleName);
		lines.add("{");
		exportedPackages.stream().sorted().forEach(packageName -> lines.add("\texports " + packageName + ";"));
		lines.add("}");
		Files.write(descriptor, lines);

		run(javac, "javac", List.of("--patch-module", moduleName + "=" + destination, "-d", classes.toString(),
			descriptor.toString()));
		run(jar, "jar", List.of("--update", "--file", destination.toString(), "-C", classes.toString(),
			"module-info.class"));
		return destination;
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

	/**
	 * Runs a JDK tool and converts failures to {@link IOException}.
	 *
	 * @param tool the tool provider
	 * @param name the tool name
	 * @param arguments the tool arguments
	 * @throws IOException if the tool exits unsuccessfully
	 */
	private static void run(ToolProvider tool, String name, List<String> arguments) throws IOException
	{
		assert that(name, "name").isNotBlank().elseThrow();
		StringWriter standardOutput = new StringWriter();
		StringWriter standardError = new StringWriter();
		int exitCode;
		try (PrintWriter out = new PrintWriter(standardOutput);
			PrintWriter err = new PrintWriter(standardError))
		{
			exitCode = tool.run(out, err, arguments.toArray(String[]::new));
		}
		if (exitCode != 0)
		{
			String diagnostics = (standardError + System.lineSeparator() + standardOutput).trim();
			String details = "";
			if (!diagnostics.isEmpty())
				details = ": " + diagnostics;
			throw new IOException(name + " failed with exit code " + exitCode + details);
		}
	}
}
