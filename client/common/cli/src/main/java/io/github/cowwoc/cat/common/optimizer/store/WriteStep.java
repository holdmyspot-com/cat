package io.github.cowwoc.cat.common.optimizer.store;

/**
 * The steps of one atomic file replacement, in the order that {@link FileCampaignStore} performs them.
 */
public enum WriteStep
{
	/**
	 * The new content was written to a temporary file next to the target.
	 */
	WRITE_TEMPORARY,
	/**
	 * The temporary file's content was forced to the storage device.
	 */
	SYNC_TEMPORARY,
	/**
	 * The temporary file was renamed over the target.
	 */
	RENAME,
	/**
	 * The directory that holds the target was forced to the storage device.
	 */
	SYNC_DIRECTORY
}
