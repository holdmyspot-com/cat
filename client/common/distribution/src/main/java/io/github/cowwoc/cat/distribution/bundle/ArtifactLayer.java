package io.github.cowwoc.cat.distribution.bundle;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.util.Set;

/**
 * One resource JAR input to {@link PluginArtifactBuilder}. The builder processes its supplied layers sequentially and
 * extracts each JAR's resources into one plugin-artifact directory. A JAR processed later may overwrite a path that an
 * earlier JAR extracted only when the later layer declares that path in {@code overrides}; otherwise, the builder
 * rejects the artifact assembly with a resource-collision error.
 *
 * @param name identifier used in resource-collision diagnostics for this JAR layer
 * @param jar JAR containing resources copied into the assembled plugin directory
 * @param overrides artifact-relative resource paths that this layer may replace when an earlier layer supplied them
 * @param executables artifact-relative resource paths that must receive execute permissions after extraction
 */
public record ArtifactLayer(String name, Path jar, Set<String> overrides, Set<String> executables)
{
	/**
	 * Validates and copies the layer configuration.
	 *
	 * @param name identifier used in resource-collision diagnostics for this JAR layer
	 * @param jar JAR containing resources copied into the assembled plugin directory
	 * @param overrides artifact-relative resource paths that this layer may replace when an earlier layer supplied them
	 * @param executables artifact-relative resource paths that must receive execute permissions after extraction
	 * @throws NullPointerException if {@code name}, {@code jar}, {@code overrides}, or {@code executables} are null
	 * @throws IllegalArgumentException if {@code name} is blank
	 */
	public ArtifactLayer
	{
		requireThat(name, "name").isNotBlank();
		requireThat(jar, "jar").isNotNull();
		requireThat(overrides, "overrides").isNotNull();
		requireThat(executables, "executables").isNotNull();
		overrides = Set.copyOf(overrides);
		executables = Set.copyOf(executables);
	}

	/**
	 * Creates a layer with declared resource replacements but no executable resources.
	 *
	 * @param name identifier used in resource-collision diagnostics for this JAR layer
	 * @param jar JAR containing resources copied into the assembled plugin directory
	 * @param overrides artifact-relative resource paths that this layer may replace when an earlier layer supplied them
	 * @throws NullPointerException if {@code name}, {@code jar}, or {@code overrides} are null
	 * @throws IllegalArgumentException if {@code name} is blank
	 */
	public ArtifactLayer(String name, Path jar, Set<String> overrides)
	{
		this(name, jar, overrides, Set.of());
	}

	/**
	 * Creates a layer that cannot replace existing paths.
	 *
	 * @param name identifier used in resource-collision diagnostics for this JAR layer
	 * @param jar JAR containing resources copied into the assembled plugin directory
	 * @throws NullPointerException if {@code name} or {@code jar} are null
	 * @throws IllegalArgumentException if {@code name} is blank
	 */
	public ArtifactLayer(String name, Path jar)
	{
		this(name, jar, Set.of(), Set.of());
	}
}
