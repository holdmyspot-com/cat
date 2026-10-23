package com.holdmyspot.cat.common.release.fakes.test;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PortableFiles;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * Builds a local CAT release directory, holding a harness's archive and {@code SHA256SUMS.txt}, so that tests of the
 * release download never reach the network.
 */
public final class FakeRelease
{
	private static final int BLOCK = 512;

	/**
	 * Prevents instantiation.
	 */
	private FakeRelease()
	{
	}

	/**
	 * Creates a minimal CAT artifact: its version file, manifest, {@code plugin-info} and runner launchers, and hook
	 * launcher, in the form that an artifact built for a platform holds them.
	 *
	 * @param directory the artifact's directory, which this method creates
	 * @param manifest  the manifest's path relative to the artifact
	 * @param runner    the runner launcher's name
	 * @param version   the CAT version that the artifact's version file states
	 * @param platform  the platform that the artifact is built for
	 * @return {@code directory}
	 * @throws IOException if the files cannot be written
	 */
	public static Path artifact(Path directory, String manifest, String runner, String version, Platform platform)
		throws IOException
	{
		String extension = "";
		String hookExtension = "";
		if (platform.isWindows())
		{
			extension = ".cmd";
			hookExtension = ".bat";
		}
		write(directory.resolve("client/VERSION"), version + "\n", false);
		write(directory.resolve(manifest), "{}\n", false);
		write(directory.resolve("bin/plugin-info" + extension), "#!/bin/sh\n", true);
		write(directory.resolve("bin/" + runner + extension), "#!/bin/sh\n", true);
		write(directory.resolve("client/bin/session-start" + hookExtension), "#!/bin/sh\n", true);
		return directory;
	}

	/**
	 * Writes a file, creating its parent directories.
	 *
	 * @param file       the file
	 * @param content    the file's content
	 * @param executable {@code true} to let every user run the file
	 * @throws IOException if the file cannot be written
	 */
	private static void write(Path file, String content, boolean executable) throws IOException
	{
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
		String mode = "rw-r--r--";
		if (executable)
			mode = "rwxr-xr-x";
		PortableFiles.setMode(file, PosixFilePermissions.fromString(mode));
	}

	/**
	 * Publishes an artifact as a release archive whose single top-level directory is the archive's name without
	 * {@code .tar.gz}, and adds its checksum to the release's {@code SHA256SUMS.txt}.
	 *
	 * @param release  the release directory
	 * @param name     the archive's name without {@code .tar.gz}, such as {@code cat-codex-v1.0-linux-x64}
	 * @param artifact the artifact's directory
	 * @return the archive
	 * @throws IOException if the archive cannot be written
	 */
	public static Path publish(Path release, String name, Path artifact) throws IOException
	{
		Path archive = release.resolve(name + ".tar.gz");
		Files.createDirectories(release);
		try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(archive)))
		{
			try (Stream<Path> paths = Files.walk(artifact))
			{
				for (Path path : paths.sorted(Comparator.comparing(Path::toString)).toList())
				{
					String relative = artifact.relativize(path).toString().replace('\\', '/');
					String entry;
					if (relative.isEmpty())
						entry = name;
					else
						entry = name + "/" + relative;
					if (Files.isDirectory(path))
						writeEntry(out, entry + "/", '5', "", new byte[0], 0755);
					else
					{
						int mode = 0644;
						if (Files.isExecutable(path))
							mode = 0755;
						writeEntry(out, entry, '0', "", Files.readAllBytes(path), mode);
					}
				}
			}
			out.write(new byte[2 * BLOCK]);
		}
		addChecksum(release, archive.getFileName().toString(), sha256(Files.readAllBytes(archive)));
		return archive;
	}

	/**
	 * Publishes an archive that holds the given entries, and adds its checksum to the release's
	 * {@code SHA256SUMS.txt}.
	 *
	 * @param release the release directory
	 * @param name    the archive's file name
	 * @param entries the archive's entries
	 * @return the archive
	 * @throws IOException if the archive cannot be written
	 */
	public static Path publishEntries(Path release, String name, List<Entry> entries) throws IOException
	{
		Path archive = release.resolve(name);
		Files.createDirectories(release);
		try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(archive)))
		{
			for (Entry entry : entries)
				writeEntry(out, entry.name(), entry.type(), entry.link(), entry.content().getBytes(UTF_8), entry.mode());
			out.write(new byte[2 * BLOCK]);
		}
		addChecksum(release, name, sha256(Files.readAllBytes(archive)));
		return archive;
	}

	/**
	 * Adds a line to the release's {@code SHA256SUMS.txt}.
	 *
	 * @param release the release directory
	 * @param name    the archive's file name
	 * @param digest  the archive's SHA-256 digest in hexadecimal
	 * @throws IOException if the file cannot be written
	 */
	public static void addChecksum(Path release, String name, String digest) throws IOException
	{
		Path sums = release.resolve("SHA256SUMS.txt");
		List<String> lines = new ArrayList<>();
		if (Files.exists(sums))
			lines.addAll(Files.readAllLines(sums));
		lines.removeIf(line -> line.endsWith("  " + name));
		lines.add(digest + "  " + name);
		Files.write(sums, lines);
	}

	/**
	 * Returns the SHA-256 digest of bytes.
	 *
	 * @param bytes the bytes
	 * @return the lower-case hexadecimal digest
	 */
	public static String sha256(byte[] bytes)
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new AssertionError("Every Java implementation must provide SHA-256", e);
		}
	}

	/**
	 * Writes one entry, preceded by a PAX header that holds its name when the name does not fit the ustar header, as
	 * CAT's release packager writes it.
	 *
	 * @param out     the uncompressed archive stream
	 * @param name    the entry's name
	 * @param type    the entry's type flag
	 * @param link    the target of a symbolic link, otherwise empty
	 * @param content the entry's content
	 * @param mode    the entry's mode bits
	 * @throws IOException if the entry cannot be written
	 */
	private static void writeEntry(OutputStream out, String name, char type, String link, byte[] content, int mode)
		throws IOException
	{
		String headerName = name;
		if (name.getBytes(UTF_8).length > 100)
		{
			String record = "path=" + name + "\n";
			int length = record.getBytes(UTF_8).length + 3;
			while (Integer.toString(length).length() + 1 + record.getBytes(UTF_8).length != length)
				length = Integer.toString(length).length() + 1 + record.getBytes(UTF_8).length;
			byte[] pax = (length + " " + record).getBytes(UTF_8);
			out.write(header("PaxHeader", 'x', "", pax.length, 0644));
			writePadded(out, pax);
			headerName = "long-name";
		}
		out.write(header(headerName, type, link, content.length, mode));
		writePadded(out, content);
	}

	/**
	 * Writes content padded to a whole number of blocks.
	 *
	 * @param out     the uncompressed archive stream
	 * @param content the content
	 * @throws IOException if the content cannot be written
	 */
	private static void writePadded(OutputStream out, byte[] content) throws IOException
	{
		out.write(content);
		int remainder = content.length % BLOCK;
		if (remainder != 0)
			out.write(new byte[BLOCK - remainder]);
	}

	/**
	 * Returns a ustar header.
	 *
	 * @param name the entry's name, at most 100 bytes
	 * @param type the entry's type flag
	 * @param link the target of a symbolic link, otherwise empty
	 * @param size the entry's size in bytes
	 * @param mode the entry's mode bits
	 * @return the 512-byte header
	 */
	private static byte[] header(String name, char type, String link, long size, int mode)
	{
		byte[] header = new byte[BLOCK];
		put(header, 0, 100, name.getBytes(UTF_8));
		put(header, 100, 8, octal(mode, 7));
		put(header, 108, 8, octal(0, 7));
		put(header, 116, 8, octal(0, 7));
		put(header, 124, 12, octal(size, 11));
		put(header, 136, 12, octal(0, 11));
		header[156] = (byte) type;
		put(header, 157, 100, link.getBytes(UTF_8));
		put(header, 257, 6, "ustar\0".getBytes(US_ASCII));
		put(header, 263, 2, "00".getBytes(US_ASCII));
		for (int i = 148; i < 156; ++i)
			header[i] = ' ';
		long checksum = 0;
		for (byte value : header)
			checksum += value & 0xFF;
		put(header, 148, 8, octal(checksum, 6));
		header[155] = ' ';
		return header;
	}

	/**
	 * Returns a number as zero-padded octal digits followed by a NUL.
	 *
	 * @param value  the number
	 * @param digits the number of digits
	 * @return the field's bytes
	 */
	private static byte[] octal(long value, int digits)
	{
		String text = Long.toOctalString(value);
		return ("0".repeat(digits - text.length()) + text + "\0").getBytes(US_ASCII);
	}

	/**
	 * Copies bytes into a header field.
	 *
	 * @param header the header
	 * @param offset the field's offset
	 * @param length the field's length
	 * @param value  the bytes, at most {@code length}
	 */
	private static void put(byte[] header, int offset, int length, byte[] value)
	{
		if (value.length > length)
			throw new IllegalArgumentException("Field too long: " + new String(value, UTF_8));
		System.arraycopy(value, 0, header, offset, value.length);
	}

	/**
	 * Returns a gzip stream's bytes cut to their first half, which leaves an archive that ends in the middle.
	 *
	 * @param archive the archive
	 * @return the first half of its bytes
	 * @throws IOException if the archive cannot be read
	 */
	public static byte[] truncated(Path archive) throws IOException
	{
		byte[] bytes = Files.readAllBytes(archive);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(bytes, 0, bytes.length / 2);
		return out.toByteArray();
	}

	/**
	 * One entry of an archive that {@link #publishEntries} writes.
	 *
	 * @param name    the entry's name
	 * @param type    the entry's type flag: {@code '0'} for a file, {@code '5'} for a directory, {@code '2'} for a
	 *                symbolic link
	 * @param link    the target of a symbolic link, otherwise empty
	 * @param content the content of a file, otherwise empty
	 * @param mode    the entry's mode bits
	 */
	public record Entry(String name, char type, String link, String content, int mode)
	{
		/**
		 * Returns a file entry.
		 *
		 * @param name    the entry's name
		 * @param content the file's content
		 * @return the entry
		 */
		public static Entry file(String name, String content)
		{
			return new Entry(name, '0', "", content, 0644);
		}

		/**
		 * Returns a symbolic-link entry.
		 *
		 * @param name the entry's name
		 * @param link the link's target
		 * @return the entry
		 */
		public static Entry link(String name, String link)
		{
			return new Entry(name, '2', link, "", 0777);
		}
	}
}
