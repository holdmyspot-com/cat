package com.holdmyspot.cat.common.runner;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * The parent's dependency caches that an isolated harness process shares, and the environment that points its tools at
 * them.
 *
 * @param directories the writable cache directories that the nested sandbox must let the process write
 * @param environment the variables that point the process's tools at the caches, by name
 */
public record HarnessDependencyCaches(List<Path> directories, Map<String, String> environment)
{
	/**
	 * No shared cache.
	 */
	public static final HarnessDependencyCaches NONE = new HarnessDependencyCaches(List.of(), Map.of());

	/**
	 * Creates the caches.
	 *
	 * @param directories the writable cache directories that the nested sandbox must let the process write
	 * @param environment the variables that point the process's tools at the caches, by name
	 * @throws NullPointerException if any of the arguments are null
	 */
	public HarnessDependencyCaches
	{
		requireThat(directories, "directories").isNotNull();
		requireThat(environment, "environment").isNotNull();
		directories = List.copyOf(directories);
		environment = Map.copyOf(environment);
	}
}
