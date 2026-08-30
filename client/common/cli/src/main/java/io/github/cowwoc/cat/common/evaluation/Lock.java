package io.github.cowwoc.cat.common.evaluation;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Owns an exclusive file-system claim for one evaluator lifecycle.
 */
public final class Lock implements AutoCloseable
{
	private final FileChannel channel;
	private final FileLock fileLock;

	/**
	 * Claims one evaluator-owned marker.
	 *
	 * @param marker the exclusive ownership marker
	 * @return the held lifecycle claim
	 * @throws IOException if another evaluator owns the marker
	 */
	public static Lock claim(Path marker) throws IOException
	{
		FileChannel channel = FileChannel.open(marker, CREATE, WRITE);
		try
		{
			FileLock lock = channel.tryLock();
			if (lock == null)
			{
				channel.close();
				throw ownedElsewhere(marker);
			}
			return new Lock(channel, lock);
		}
		catch (OverlappingFileLockException e)
		{
			channel.close();
			throw new IOException(ownedElsewhere(marker).getMessage(), e);
		}
	}

	/**
	 * Creates a held lifecycle claim.
	 *
	 * @param channel the channel that owns the operating-system lock
	 * @param fileLock the held exclusive lock
	 */
	private Lock(FileChannel channel, FileLock fileLock)
	{
		this.channel = channel;
		this.fileLock = fileLock;
	}

	/**
	 * Verifies the caller still owns this lifecycle claim.
	 *
	 * @throws IOException if the ownership lock was released
	 */
	public void verifyHeld() throws IOException
	{
		if (!fileLock.isValid())
			throw new IOException("Prompt evaluation workspace lock was released unexpectedly: " + channel);
	}

	/**
	 * Releases the operating-system claim.
	 *
	 * @throws IOException if either lock resource cannot close
	 */
	@Override
	public void close() throws IOException
	{
		try (channel; fileLock)
		{
			// Closing the lock first releases the workspace claim before its channel closes.
		}
	}

	/**
	 * Creates the diagnostic for a marker already held by another evaluator.
	 *
	 * @param marker the claimed marker
	 * @return the ownership-conflict failure
	 */
	private static IOException ownedElsewhere(Path marker)
	{
		return new IOException("Prompt evaluation workspace is already owned by another evaluator: " + marker);
	}
}
