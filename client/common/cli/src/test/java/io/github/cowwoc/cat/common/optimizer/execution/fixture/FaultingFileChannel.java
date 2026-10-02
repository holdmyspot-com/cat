package io.github.cowwoc.cat.common.optimizer.execution.fixture;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/**
 * A campaign lock channel that faults during acquisition or release, and during channel cleanup.
 * <p>
 * Models the failures permitted by {@link FileChannel#tryLock(long, long, boolean)},
 * {@link FileLock#release()} and {@link FileChannel#close()}. It supplies failures so tests can verify the
 * caller's choice of primary and suppressed exceptions; it does not establish filesystem behavior.
 */
public final class FaultingFileChannel extends FileChannel
{
	private final Exception acquisitionFailure;
	private final IOException releaseFailure;
	private final Exception cleanupFailure;

	/**
	 * Creates a channel with independently controlled operation and cleanup failures.
	 *
	 * @param acquisitionFailure the acquisition failure, or null to return a held lock
	 * @param releaseFailure the failure thrown when releasing a held lock
	 * @param cleanupFailure the failure thrown when closing the channel
	 */
	public FaultingFileChannel(Exception acquisitionFailure, IOException releaseFailure, Exception cleanupFailure)
	{
		this.acquisitionFailure = acquisitionFailure;
		this.releaseFailure = releaseFailure;
		this.cleanupFailure = cleanupFailure;
	}

	@Override
	public FileLock tryLock(long position, long size, boolean shared) throws IOException
	{
		if (acquisitionFailure != null)
			throwFailure(acquisitionFailure);
		return new FileLock(this, position, size, shared)
		{
			@Override
			public boolean isValid()
			{
				return isOpen();
			}

			@Override
			public void release() throws IOException
			{
				throw releaseFailure;
			}
		};
	}

	@Override
	protected void implCloseChannel() throws IOException
	{
		throwFailure(cleanupFailure);
	}

	/**
	 * Throws an I/O or runtime failure without wrapping it.
	 *
	 * @param failure the failure to throw
	 * @throws IOException if the supplied failure is an I/O failure
	 */
	private static void throwFailure(Exception failure) throws IOException
	{
		if (failure instanceof IOException io)
			throw io;
		throw (RuntimeException) failure;
	}

	@Override
	public int read(ByteBuffer destination)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public long read(ByteBuffer[] destinations, int offset, int length)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public int read(ByteBuffer destination, long position)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public int write(ByteBuffer source)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public long write(ByteBuffer[] sources, int offset, int length)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public int write(ByteBuffer source, long position)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public long position()
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public FileChannel position(long position)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public long size()
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public FileChannel truncate(long size)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public void force(boolean metadata)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public long transferTo(long position, long count, WritableByteChannel target)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public long transferFrom(ReadableByteChannel source, long position, long count)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public MappedByteBuffer map(MapMode mode, long position, long size)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public FileLock lock(long position, long size, boolean shared)
	{
		throw new UnsupportedOperationException();
	}
}
