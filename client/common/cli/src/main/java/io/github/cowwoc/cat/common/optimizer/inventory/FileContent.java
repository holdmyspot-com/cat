package io.github.cowwoc.cat.common.optimizer.inventory;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.Arrays;

/**
 * The bytes of one file that a candidate writes.
 * <p>
 * Two instances are equal when they hold the same bytes.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class FileContent
{
	private final byte[] bytes;
	private final Sha256 hash;

	/**
	 * Creates file content.
	 *
	 * @param bytes the file's bytes; later changes to the array do not affect this content
	 * @throws NullPointerException if {@code bytes} is null
	 */
	public FileContent(byte[] bytes)
	{
		requireThat(bytes, "bytes").isNotNull();
		this.bytes = bytes.clone();
		this.hash = Sha256.of(this.bytes);
	}

	/**
	 * Returns the file's bytes.
	 *
	 * @return a copy of the bytes
	 */
	public byte[] bytes()
	{
		return bytes.clone();
	}

	/**
	 * Returns the digest of the file's bytes.
	 *
	 * @return the digest
	 */
	public Sha256 hash()
	{
		return hash;
	}

	@Override
	public boolean equals(Object obj)
	{
		return obj instanceof FileContent other && Arrays.equals(bytes, other.bytes);
	}

	@Override
	public int hashCode()
	{
		return hash.hashCode();
	}

	@Override
	public String toString()
	{
		return "FileContent[size=" + bytes.length + ", hash=" + hash.hex() + "]";
	}
}
