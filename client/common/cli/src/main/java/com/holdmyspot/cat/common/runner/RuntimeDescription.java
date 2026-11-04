package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/**
 * Describes the shared Java runtime, from the {@code cat-runtime.json} file that the build writes into it.
 *
 * @param javaVersion the runtime's complete Java version, such as 27.0.1
 * @param javaFeature the runtime's Java feature release, such as 27
 * @param modules     every module in the runtime image
 */
public record RuntimeDescription(String javaVersion, int javaFeature, SortedSet<String> modules)
{
	/**
	 * The runtime-relative description file.
	 */
	public static final Path FILE = Path.of("cat-runtime.json");

	/**
	 * Creates a description.
	 *
	 * @param javaVersion the runtime's complete Java version, such as 27.0.1
	 * @param javaFeature the runtime's Java feature release, such as 27
	 * @param modules     every module in the runtime image
	 * @throws NullPointerException     if {@code javaVersion} or {@code modules} are null
	 * @throws IllegalArgumentException if {@code javaVersion} is blank or {@code javaFeature} is not positive
	 */
	public RuntimeDescription
	{
		requireThat(javaVersion, "javaVersion").isNotBlank();
		requireThat(javaFeature, "javaFeature").isPositive();
		modules = new TreeSet<>(modules);
	}

	/**
	 * Reads the description of a runtime directory, of format 1:
	 * {@code {"format": 1, "java_version": "...", "java_feature": N, "modules": [...]}}.
	 *
	 * @param runtime the runtime directory
	 * @return the description
	 * @throws NullPointerException if {@code runtime} is null
	 * @throws IOException          if the description is absent, a symbolic link, unreadable or malformed
	 */
	public static RuntimeDescription read(Path runtime) throws IOException
	{
		requireThat(runtime, "runtime").isNotNull();
		Path file = runtime.resolve(FILE);
		JsonNode root = RuntimeRequirement.readObject(file, Set.of("format", "java_version", "java_feature", "modules"),
			"runtime description");
		JsonNode version = root.get("java_version");
		if (!version.isString() || version.stringValue().isBlank())
			throw new IOException("The member java_version must be non-blank text: " + file);
		return new RuntimeDescription(version.stringValue(), RuntimeRequirement.feature(root, file),
			RuntimeRequirement.modules(root, file));
	}

	/**
	 * Returns whether this runtime can run an application with the given requirement: its feature release is at
	 * least the required one and it contains every required module.
	 *
	 * @param requirement the application's requirement
	 * @return true if the runtime satisfies it
	 * @throws NullPointerException if {@code requirement} is null
	 */
	public boolean satisfies(RuntimeRequirement requirement)
	{
		requireThat(requirement, "requirement").isNotNull();
		return javaFeature >= requirement.javaFeature() && modules.containsAll(requirement.modules());
	}
}
