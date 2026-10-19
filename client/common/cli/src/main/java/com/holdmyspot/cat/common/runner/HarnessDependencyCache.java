package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Exposes the parent's explicitly writable dependency cache to isolated harness processes.
 */
public final class HarnessDependencyCache
{
	/**
	 * Configures npm to reuse the parent process' writable cache instead of a disposable isolated home cache.
	 *
	 * @param parentEnvironment the parent process environment before its home is isolated
	 *
	 * @param isolatedEnvironment the mutable environment passed to the nested harness
	 *
	 * @throws IOException if the parent cache cannot be used
	 */
	public static void configureNpm(Map<String, String> parentEnvironment, Map<String, String> isolatedEnvironment)
		throws IOException
	{
		requireThat(parentEnvironment, "parentEnvironment").isNotNull();
		requireThat(isolatedEnvironment, "isolatedEnvironment").isNotNull();
		isolatedEnvironment.put("NPM_CONFIG_CACHE", verifyNpm(parentEnvironment).toString());
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
		Path cache = Platform.current().homeDirectory(parentEnvironment).resolve(".npm").toAbsolutePath().normalize();
		Files.createDirectories(cache);
		if (!Files.isDirectory(cache) || !Files.isWritable(cache))
			throw new IOException("Shared npm cache is not writable: " + cache);
		return cache;
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private HarnessDependencyCache()
	{
	}
}
