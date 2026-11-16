package com.holdmyspot.cat.codex.secrets;

import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.codex.runner.CodexCuratedPluginCache;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Exposes controlled partial-cache removal for {@link CodexCuratedPluginCache} tests.
 */
@FunctionalInterface
public interface CodexCuratedPluginCacheAccess
{
	/**
	 * Seeds a child snapshot using the supplied owned-cache removal operation.
	 *
	 * @param parentHome   the parent Codex home that may contain a curated snapshot
	 * @param isolatedHome the fresh child Codex home
	 * @param environment  the isolated command environment
	 * @param executables  runs Git
	 * @param cleanup      removes the owned partial cache after seeding fails
	 * @return {@code true} if seeded, or {@code false} when ordinary synchronization must proceed
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the child temporary directory cannot be created, or a failed seed leaves an owned
	 *                                  partial cache that cannot be removed; the cleanup failure is the cause and the
	 *                                  seeding failure is suppressed
	 */
	boolean seed(Path parentHome, Path isolatedHome, Map<String, String> environment, ExecutableFiles executables,
		Cleanup cleanup) throws IOException;

	/**
	 * Removes one owned partial cache, modeling the filesystem failure reported by recursive removal.
	 */
	@FunctionalInterface
	interface Cleanup
	{
		/**
		 * Removes the owned partial cache.
		 *
		 * @param directory the owned partial-cache directory
		 * @throws IOException if removal fails
		 */
		void delete(Path directory) throws IOException;
	}
}
