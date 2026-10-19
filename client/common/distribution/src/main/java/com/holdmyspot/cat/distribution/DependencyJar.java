package com.holdmyspot.cat.distribution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;

/**
 * A third-party dependency JAR that a distribution ships, named {@code <artifactId>-<version>.jar}.
 *
 * @param groupId the Maven group identifier
 * @param artifactId the Maven artifact identifier
 * @param path the JAR
 */
record DependencyJar(String groupId, String artifactId, Path path)
{
	private static final String SUFFIX = ".jar";

	/**
	 * Creates a dependency JAR.
	 *
	 * @param groupId the Maven group identifier
	 * @param artifactId the Maven artifact identifier
	 * @param path the JAR
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if:
	 *   <ul>
	 *     <li>{@code groupId} or {@code artifactId} are blank</li>
	 *     <li>the file name of {@code path} is not {@code <artifactId>-<version>.jar}</li>
	 *   </ul>
	 */
	DependencyJar
	{
		requireThat(groupId, "groupId").isNotBlank();
		requireThat(artifactId, "artifactId").isNotBlank();
		requireThat(path, "path").isNotNull();
		String name = path.getFileName().toString();
		if (!name.startsWith(artifactId + "-") || !name.endsWith(SUFFIX) ||
			name.length() <= artifactId.length() + 1 + SUFFIX.length())
		{
			throw new IllegalArgumentException("The file name of " + path + " is not " + artifactId +
				"-<version>.jar");
		}
	}

	/**
	 * Returns the Maven coordinates of the JAR that ships.
	 *
	 * @return {@code <groupId>:<artifactId>:<version>}, with the version taken from the file name
	 */
	String coordinates()
	{
		String name = path.getFileName().toString();
		String version = name.substring(artifactId.length() + 1, name.length() - SUFFIX.length());
		return groupId + ":" + artifactId + ":" + version;
	}
}
