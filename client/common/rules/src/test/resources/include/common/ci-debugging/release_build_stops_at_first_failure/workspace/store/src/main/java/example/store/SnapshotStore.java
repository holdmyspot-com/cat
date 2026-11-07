package example.store;

import example.core.Entry;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entries that are appended to one snapshot file.
 */
public final class SnapshotStore implements AutoCloseable
{
	private final Path file;
	private RandomAccessFile output;

	/**
	 * Opens the snapshot file, creating it if it does not exist.
	 *
	 * @param file the snapshot file
	 * @throws IOException if the file cannot be opened
	 */
	public SnapshotStore(Path file) throws IOException
	{
		this.file = file;
		this.output = new RandomAccessFile(file.toFile(), "rw");
	}

	/**
	 * Appends an entry.
	 *
	 * @param entry the entry
	 * @throws IOException if the entry cannot be written
	 */
	public void append(Entry entry) throws IOException
	{
		output.seek(output.length());
		output.writeUTF(entry.name());
		output.writeLong(entry.value());
	}

	/**
	 * Removes every entry.
	 *
	 * @throws IOException if the snapshot file cannot be replaced
	 */
	public void clear() throws IOException
	{
		Files.delete(file);
		output.close();
		output = new RandomAccessFile(file.toFile(), "rw");
	}

	/**
	 * Returns the size of the snapshot file.
	 *
	 * @return the number of bytes
	 * @throws IOException if the size cannot be read
	 */
	public long size() throws IOException
	{
		return output.length();
	}

	@Override
	public void close() throws IOException
	{
		output.close();
	}
}
