package com.holdmyspot.cat.common.release;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.PortableFiles;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Extracts a CAT release archive: a gzip-compressed POSIX tar archive whose entries all lie in one top-level
 * directory. Java has no tar reader, and the archive must be extracted on every release platform, Windows included,
 * without a {@code tar} program.
 * <p>
 * It reads the ustar entries that CAT's release packager writes: directories, regular files and symbolic links, with
 * a PAX extended header holding a name that the ustar header cannot. It rejects every other entry type, every entry
 * outside the top-level directory and every symbolic link whose target lies outside it, before writing the entry.
 */
final class TarGzExtractor
{
	private static final int BLOCK = 512;
	/**
	 * The largest PAX extended header that an archive may hold.
	 */
	private static final int MAXIMUM_PAX_BYTES = 1_048_576;
	/**
	 * The permission of each POSIX mode bit, from the owner's read bit down to the others' execute bit.
	 */
	private static final PosixFilePermission[] PERMISSIONS =
	{
		PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
		PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
		PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE
	};

	/**
	 * Prevents instantiation.
	 */
	private TarGzExtractor()
	{
	}

	/**
	 * Extracts an archive into an empty directory.
	 *
	 * @param archive  the compressed archive
	 * @param target   the empty directory that receives the top-level directory
	 * @param topLevel the name of the top-level directory that holds every entry
	 * @throws IOException if the archive is malformed, ends early, holds an unsupported entry or an entry outside
	 *                     {@code topLevel}, or a file cannot be written
	 */
	static void extract(InputStream archive, Path target, String topLevel) throws IOException
	{
		assert archive != null;
		assert target != null;
		assert topLevel != null;
		Path root = target.toAbsolutePath().normalize();
		Path top = root.resolve(topLevel);
		InputStream in = new GZIPInputStream(archive);
		byte[] header = new byte[BLOCK];
		Map<String, String> extended = Map.of();
		while (true)
		{
			readFully(in, header);
			if (isZero(header))
				break;
			verifyChecksum(header);
			char type = (char) header[156];
			long size = number(header, 124, 12);
			if (type == 'x')
			{
				extended = paxRecords(in, size);
				continue;
			}
			if (type == 'g')
			{
				skip(in, size);
				continue;
			}
			String name = extended.getOrDefault("path", entryName(header));
			String link = extended.getOrDefault("linkpath", text(header, 157, 100));
			extended = Map.of();
			Path path = resolveEntry(root, top, name);
			switch (type)
			{
				case '5' ->
				{
					Files.createDirectories(path);
					skip(in, size);
				}
				case '0', '\0' ->
				{
					Files.createDirectories(path.getParent());
					try (OutputStream out = Files.newOutputStream(path))
					{
						copy(in, out, size);
					}
					skip(in, padding(size));
					PortableFiles.setMode(path, permissions((int) number(header, 100, 8)));
				}
				case '2' ->
				{
					Path resolved = path.getParent().resolve(link).normalize();
					if (!resolved.startsWith(top) || Path.of(link).isAbsolute())
						throw new IOException("The symbolic link " + name + " points outside the archive: " + link);
					Files.createDirectories(path.getParent());
					Files.createSymbolicLink(path, Path.of(link));
					skip(in, size);
				}
				default -> throw new IOException("Unsupported archive entry type '" + type + "': " + name);
			}
		}
	}

	/**
	 * Returns an entry's local path after checking that it lies in the top-level directory.
	 *
	 * @param root the extraction directory
	 * @param top  the top-level directory
	 * @param name the entry's name
	 * @return the path
	 * @throws IOException if the name is absolute or lies outside {@code top}
	 */
	private static Path resolveEntry(Path root, Path top, String name) throws IOException
	{
		String relative = name;
		while (relative.endsWith("/"))
			relative = relative.substring(0, relative.length() - 1);
		boolean traverses = false;
		for (String segment : relative.split("/"))
		{
			if (segment.equals(".."))
				traverses = true;
		}
		if (traverses || relative.startsWith("/") || relative.contains("\\") || relative.contains(":"))
			throw new IOException("The archive entry lies outside the archive's directory: " + name);
		Path path = root.resolve(relative).normalize();
		if (!path.startsWith(top))
			throw new IOException("The archive entry lies outside the archive's directory: " + name);
		if (Files.isSymbolicLink(path.getParent()))
			throw new IOException("The archive entry lies below a symbolic link: " + name);
		if (Files.exists(path, NOFOLLOW_LINKS) && !Files.isDirectory(path, NOFOLLOW_LINKS))
			throw new IOException("The archive holds the entry twice: " + name);
		return path;
	}

	/**
	 * Returns a ustar header's entry name, joining its prefix and name fields.
	 *
	 * @param header the header
	 * @return the name
	 */
	private static String entryName(byte[] header)
	{
		String name = text(header, 0, 100);
		String prefix = text(header, 345, 155);
		if (prefix.isEmpty())
			return name;
		return prefix + "/" + name;
	}

	/**
	 * Reads a PAX extended header's records.
	 *
	 * @param in   the uncompressed archive
	 * @param size the header's size in bytes
	 * @return each record's value by its keyword
	 * @throws IOException if the header is malformed or too large
	 */
	private static Map<String, String> paxRecords(InputStream in, long size) throws IOException
	{
		if (size > MAXIMUM_PAX_BYTES)
			throw new IOException("The archive's PAX extended header is too large: " + size + " bytes");
		byte[] content = new byte[(int) size];
		readFully(in, content);
		skip(in, padding(size));
		Map<String, String> records = new HashMap<>();
		int offset = 0;
		while (offset < content.length)
		{
			int space = offset;
			while (space < content.length && content[space] != ' ')
				++space;
			int length;
			try
			{
				length = Integer.parseInt(new String(content, offset, space - offset, UTF_8));
			}
			catch (NumberFormatException e)
			{
				throw new IOException("Malformed PAX extended header", e);
			}
			if (length <= space - offset || offset + length > content.length || content[offset + length - 1] != '\n')
				throw new IOException("Malformed PAX extended header");
			String record = new String(content, space + 1, offset + length - space - 2, UTF_8);
			int equals = record.indexOf('=');
			if (equals == -1)
				throw new IOException("Malformed PAX extended header record: " + record);
			records.put(record.substring(0, equals), record.substring(equals + 1));
			offset += length;
		}
		return records;
	}

	/**
	 * Verifies a header's checksum.
	 *
	 * @param header the header
	 * @throws IOException if the checksum does not match
	 */
	private static void verifyChecksum(byte[] header) throws IOException
	{
		long expected = number(header, 148, 8);
		long actual = 0;
		for (int i = 0; i < BLOCK; ++i)
		{
			if (i >= 148 && i < 156)
				actual += ' ';
			else
				actual += header[i] & 0xFF;
		}
		if (actual != expected)
			throw new IOException("The archive holds a damaged entry header");
	}

	/**
	 * Returns a NUL-terminated text field.
	 *
	 * @param header the header
	 * @param offset the field's offset
	 * @param length the field's length
	 * @return the text
	 */
	private static String text(byte[] header, int offset, int length)
	{
		int end = offset;
		while (end < offset + length && header[end] != 0)
			++end;
		return new String(header, offset, end - offset, UTF_8);
	}

	/**
	 * Returns an octal number field.
	 *
	 * @param header the header
	 * @param offset the field's offset
	 * @param length the field's length
	 * @return the number
	 * @throws IOException if the field is not an octal number
	 */
	private static long number(byte[] header, int offset, int length) throws IOException
	{
		String digits = text(header, offset, length).strip();
		if (digits.isEmpty())
			return 0;
		try
		{
			long value = Long.parseLong(digits, 8);
			if (value < 0)
				throw new IOException("The archive holds a negative number: " + digits);
			return value;
		}
		catch (NumberFormatException e)
		{
			throw new IOException("The archive holds a malformed number: " + digits, e);
		}
	}

	/**
	 * Returns the permissions of POSIX mode bits.
	 *
	 * @param mode the mode bits
	 * @return the permissions
	 */
	private static Set<PosixFilePermission> permissions(int mode)
	{
		Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
		for (int i = 0; i < PERMISSIONS.length; ++i)
		{
			if ((mode & (0400 >> i)) != 0)
				permissions.add(PERMISSIONS[i]);
		}
		return permissions;
	}

	/**
	 * Returns whether a block holds only zero bytes, which marks the archive's end.
	 *
	 * @param block the block
	 * @return {@code true} if every byte is zero
	 */
	private static boolean isZero(byte[] block)
	{
		for (byte value : block)
		{
			if (value != 0)
				return false;
		}
		return true;
	}

	/**
	 * Returns the number of padding bytes that follow an entry's content.
	 *
	 * @param size the content's size
	 * @return the bytes up to the next block boundary
	 */
	private static long padding(long size)
	{
		long remainder = size % BLOCK;
		if (remainder == 0)
			return 0;
		return BLOCK - remainder;
	}

	/**
	 * Fills a buffer from a stream.
	 *
	 * @param in     the stream
	 * @param buffer the buffer
	 * @throws IOException if the stream ends first
	 */
	private static void readFully(InputStream in, byte[] buffer) throws IOException
	{
		int read = in.readNBytes(buffer, 0, buffer.length);
		if (read != buffer.length)
			throw new EOFException("The archive ends part way through an entry");
	}

	/**
	 * Copies bytes from a stream.
	 *
	 * @param in    the stream
	 * @param out   the destination
	 * @param count the number of bytes
	 * @throws IOException if the stream ends first or the destination cannot be written
	 */
	private static void copy(InputStream in, OutputStream out, long count) throws IOException
	{
		byte[] buffer = new byte[64 * 1024];
		long remaining = count;
		while (remaining > 0)
		{
			int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
			if (read == -1)
				throw new EOFException("The archive ends part way through an entry");
			out.write(buffer, 0, read);
			remaining -= read;
		}
	}

	/**
	 * Skips bytes of a stream.
	 *
	 * @param in    the stream
	 * @param count the number of bytes
	 * @throws IOException if the stream ends first
	 */
	private static void skip(InputStream in, long count) throws IOException
	{
		in.skipNBytes(count);
	}
}
