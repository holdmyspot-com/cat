package com.holdmyspot.cat.common.optimizer.inventory;

/**
 * The state of one path that a {@link DeliveryChange} requires or leaves: a file entry, or a directory.
 */
public sealed interface PathState permits FileEntry, PathState.Directory
{
	/**
	 * A directory, whatever it holds.
	 */
	record Directory() implements PathState
	{
	}
}
