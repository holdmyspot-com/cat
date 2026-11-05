package com.holdmyspot.cat.common.installer.engine;

/**
 * A {@link ReleaseSource} that holds resources, such as an HTTP client, until it is closed.
 */
public interface ReleaseConnection extends ReleaseSource, AutoCloseable
{
	/**
	 * Releases the source's resources. Closing a source that is already closed has no effect.
	 */
	@Override
	void close();
}
