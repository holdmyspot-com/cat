package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.optimizer.workspace.ForkTree;

import java.nio.file.Path;

/**
 * Exposes where a fork tree stores its recorded files to the module's tests.
 */
@FunctionalInterface
public interface ForkTreeAccess
{
	/**
	 * Returns where a tree stores a recorded file.
	 *
	 * @param tree the tree
	 * @param key a key of {@link ForkTree#inventory()}
	 * @return the file that holds the recorded bytes
	 */
	Path physical(ForkTree tree, Path key);
}
