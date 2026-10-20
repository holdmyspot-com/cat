package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Exposes the parent's explicitly writable dependency caches to isolated harness processes.
 */
public final class HarnessDependencyCache
{
	/**
	 * Verifies the parent's npm and Maven caches, so that isolated harness processes reuse them instead of disposable
	 * caches in their isolated homes.
	 * <p>
	 * npm finds its cache through {@code NPM_CONFIG_CACHE}. Maven finds its repository below the Java {@code user.home}
	 * property, which comes from the user's account rather than from {@code HOME}, or below the location that the
	 * parent's {@code MAVEN_OPTS} names, so the parent's {@code MAVEN_OPTS} is kept.
	 *
	 * @param parentEnvironment the parent process environment before its home is isolated
	 * @return the verified caches
	 * @throws NullPointerException if {@code parentEnvironment} is null
	 * @throws IOException          if a cache cannot be used
	 */
	public static HarnessDependencyCaches verify(Map<String, String> parentEnvironment) throws IOException
	{
		Path npm = verifyNpm(parentEnvironment);
		Path maven = verifyWritable(Platform.current().homeDirectory(parentEnvironment).resolve(".m2"), "Maven");
		Map<String, String> environment = new HashMap<>();
		environment.put("NPM_CONFIG_CACHE", npm.toString());
		String mavenOptions = parentEnvironment.get("MAVEN_OPTS");
		if (mavenOptions != null)
			environment.put("MAVEN_OPTS", mavenOptions);
		return new HarnessDependencyCaches(List.of(npm, maven), environment);
	}

	/**
	 * Verifies the parent npm cache before a workflow spends work preparing or starting a nested harness.
	 *
	 * @param parentEnvironment the parent process environment before its home is isolated
	 *
	 * @return the verified shared npm cache
	 *
	 * @throws IOException if the parent cache cannot be used
	 */
	public static Path verifyNpm(Map<String, String> parentEnvironment) throws IOException
	{
		requireThat(parentEnvironment, "parentEnvironment").isNotNull();
		return verifyWritable(Platform.current().homeDirectory(parentEnvironment).resolve(".npm"), "npm");
	}

	/**
	 * Creates a shared cache directory if it is absent and verifies that it is writable.
	 *
	 * @param directory the cache directory
	 * @param tool      the tool that uses the cache
	 * @return the absolute, normalized cache directory
	 * @throws IOException if the cache cannot be used
	 */
	private static Path verifyWritable(Path directory, String tool) throws IOException
	{
		Path cache = directory.toAbsolutePath().normalize();
		Files.createDirectories(cache);
		if (!Files.isDirectory(cache) || !Files.isWritable(cache))
			throw new IOException("Shared " + tool + " cache is not writable: " + cache);
		return cache;
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private HarnessDependencyCache()
	{
	}
}
