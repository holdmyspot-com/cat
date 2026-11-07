package example;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A key-to-number index that is stored in one file of a cache directory.
 */
public final class IndexCache implements AutoCloseable
{
	private final RandomAccessFile file;

	/**
	 * Opens the index in a directory, creating the directory and the index file if they do not exist.
	 *
	 * @param directory the cache directory
	 * @throws IOException if the index cannot be opened
	 */
	public IndexCache(Path directory) throws IOException
	{
		Files.createDirectories(directory);
		file = new RandomAccessFile(directory.resolve("index.db").toFile(), "rw");
	}

	/**
	 * Stores a number under a key.
	 *
	 * @param key   the key
	 * @param value the number
	 * @throws IOException if the index cannot be written
	 */
	public void put(String key, int value) throws IOException
	{
		file.seek(file.length());
		file.writeUTF(key);
		file.writeInt(value);
	}

	@Override
	public void close() throws IOException
	{
		file.close();
	}
}
