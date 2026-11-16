package com.holdmyspot.cat.common.installer.secrets;

import com.holdmyspot.cat.common.installer.engine.StartupCacheTrainer;
import com.holdmyspot.cat.common.installer.engine.StartupCacheTrainer.TreeDeleter;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Gives the installer tests a trainer whose cache and scratch tree removal can report controlled failures.
 */
public interface StartupCacheTrainerAccess
{
	/**
	 * Creates a trainer that uses the supplied tree-removal operation.
	 *
	 * @param scratch    the directory below which training creates its scratch folders
	 * @param timeout    the longest time allowed for each Java run
	 * @param elevated   tells whether this process runs with administrative rights
	 * @param deleteTree removes a cache or scratch tree
	 * @return the trainer
	 */
	StartupCacheTrainer create(Path scratch, Duration timeout, BooleanSupplier elevated, TreeDeleter deleteTree);

	/**
	 * Ends a training using the supplied tree-removal operation.
	 *
	 * @param written        {@code true} if training wrote the cache
	 * @param cacheDirectory the cache folder, which may be absent
	 * @param work           the training scratch folder, or {@code null} if no folder was created
	 * @param deleteTree     removes a cache or scratch tree
	 * @throws IOException if a required folder cannot be removed
	 */
	void cleanUp(boolean written, Path cacheDirectory, Path work, TreeDeleter deleteTree) throws IOException;
}
