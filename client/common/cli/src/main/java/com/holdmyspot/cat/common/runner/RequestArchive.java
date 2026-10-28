package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.PortableFiles;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;

/**
 * Packs sandbox-readable inputs and extracts bounded, plain request trees into broker-owned directories.
 */
final class RequestArchive
{
	static final String EXTENSION = ".tar";
	static final String MANIFEST = "request.json";
	static final int VERSION = 2;
	static final int MAXIMUM_MANIFEST_BYTES = 65_536;
	static final int MAXIMUM_PROMPT_BYTES = 1_048_576;
	static final long MAXIMUM_ENTRY_BYTES = 67_108_864;
	/**
	 * The device that a sandbox mounts over each file that a session may not read.
	 */
	static final Path NULL_DEVICE = Path.of("/dev/null");
	private static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-r--r--");
	private static final Set<PosixFilePermission> PROGRAM_MODE = PosixFilePermissions.fromString("rwxr-xr-x");

	/**
	 * Publishes a request with archive-relative manifest paths, packing prompt texts after the request's prompt files.
	 *
	 * @param json        the manifest mapper
	 * @param destination the final request file
	 * @param request     the launcher options; receives archive-relative paths
	 * @param prompts     the prompt texts that follow the request's prompt files, in order
	 * @param nullDevice  the null device, which a sandbox mounts over each file that the session may not read; the
	 *                    request leaves those files out of the trees that it carries
	 * @param limits      the bounds that the archive must stay within
	 * @throws IOException if an input is linked, special, oversized, or cannot be packed
	 */
	static void pack(JsonMapper json, Path destination, ObjectNode request, List<String> prompts, Path nullDevice,
		RequestLimits limits) throws IOException
	{
		Path temporary = Files.createTempFile(destination.getParent(), ".request-", EXTENSION);
		try
		{
			try (OutputStream bytes = Files.newOutputStream(temporary);
				TarArchiveOutputStream archive = new TarArchiveOutputStream(bytes))
			{
				archive.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
				Budget budget = new Budget(limits);
				Path project = Path.of(request.path("project").asString());
				packTree(archive, project, "project", budget, nullDevice);
				request.put("project", "project");
				for (String field : new String[]{"prompts", "plugins"})
				{
					ArrayNode paths = request.withArray(field);
					if (paths.size() > 32)
						throw new IOException("Request accepts at most 32 inputs: " + field);
					for (int index = 0; index < paths.size(); ++index)
					{
						Path source = Path.of(paths.get(index).asString());
						String name = field + "/" + index;
						if (field.equals("prompts"))
							packFile(archive, source, name, MAXIMUM_PROMPT_BYTES, budget);
						else
							packTree(archive, source, name, budget, nullDevice);
						paths.set(index, json.getNodeFactory().stringNode(name));
					}
				}
				ArrayNode names = request.withArray("prompts");
				if (names.size() + prompts.size() > 32)
					throw new IOException("Request accepts at most 32 inputs: prompts");
				for (String prompt : prompts)
				{
					String name = "prompts/" + names.size();
					packBytes(archive, prompt.getBytes(StandardCharsets.UTF_8), name, MAXIMUM_PROMPT_BYTES, budget);
					names.add(name);
				}
				byte[] manifest = json.writeValueAsBytes(request);
				budget.add(manifest.length, MAXIMUM_MANIFEST_BYTES);
				TarArchiveEntry entry = new TarArchiveEntry(MANIFEST);
				entry.setSize(manifest.length);
				archive.putArchiveEntry(entry);
				archive.write(manifest);
				archive.closeArchiveEntry();
			}
			if (Files.size(temporary) > limits.archiveBytes())
				throw new IOException("Request archive exceeds " + limits.archiveBytes() + " bytes");
			try (InputStream bytes = Files.newInputStream(temporary);
				TarArchiveInputStream archive = new BoundedTarInput(bytes, limits))
			{
				Set<Path> seen = new HashSet<>();
				for (TarArchiveEntry entry = archive.getNextEntry(); entry != null; entry = archive.getNextEntry())
				{
					Path path = resolveEntry(destination.getParent(), entry.getName());
					if (!seen.add(path))
						throw new IOException("Duplicate request archive entry: " + entry.getName());
					archive.transferTo(OutputStream.nullOutputStream());
				}
			}
			Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Packs one plain directory and its descendants without following symbolic links, leaving out the null-device
	 * placeholders of files that the session may not read.
	 *
	 * @param archive    the destination archive
	 * @param source     the readable directory
	 * @param name       its archive name
	 * @param budget     the request's cumulative limits
	 * @param nullDevice the null device, which a sandbox mounts over each file that the session may not read
	 * @throws IOException if the input is unsafe, oversized or unreadable
	 */
	private static void packTree(TarArchiveOutputStream archive, Path source, String name, Budget budget,
		Path nullDevice) throws IOException
	{
		checkAncestors(source);
		if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Input must be a directory without symbolic links: " + source);
		// Windows has no null device in its file system, so no placeholder there.
		boolean placeholders = Files.exists(nullDevice, LinkOption.NOFOLLOW_LINKS);
		try (Stream<Path> paths = Files.walk(source))
		{
			for (Path path : (Iterable<Path>) paths::iterator)
			{
				String relative = source.relativize(path).toString().replace(source.getFileSystem().getSeparator(), "/");
				String entryName = name;
				if (!relative.isEmpty())
					entryName = name + "/" + relative;
				BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
					LinkOption.NOFOLLOW_LINKS);
				if (attributes.isDirectory())
				{
					budget.add(0, MAXIMUM_ENTRY_BYTES);
					archive.putArchiveEntry(new TarArchiveEntry(entryName + "/"));
					archive.closeArchiveEntry();
				}
				else if (!placeholders || attributes.isSymbolicLink() || !Files.isSameFile(path, nullDevice))
					packFile(archive, path, entryName, MAXIMUM_ENTRY_BYTES, budget);
			}
		}
	}

	/**
	 * Packs exactly one bounded regular file, preserving its executable permission.
	 *
	 * @param archive the destination
	 * @param source  the input file
	 * @param name    its archive name
	 * @param maximum the content bound
	 * @param budget  cumulative limits
	 * @throws IOException if the file is unsafe, changes size or cannot be read
	 */
	private static void packFile(TarArchiveOutputStream archive, Path source, String name, long maximum,
		Budget budget) throws IOException
	{
		checkAncestors(source);
		BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class,
			LinkOption.NOFOLLOW_LINKS);
		if (!attributes.isRegularFile())
			throw new IOException("Input must be a regular file without symbolic links or special types: " + source);
		budget.add(attributes.size(), maximum);
		TarArchiveEntry entry = new TarArchiveEntry(name);
		entry.setSize(attributes.size());
		entry.setMode(modeBits(FILE_MODE));
		if (PortableFiles.mode(source).contains(PosixFilePermission.OWNER_EXECUTE))
			entry.setMode(modeBits(PROGRAM_MODE));
		archive.putArchiveEntry(entry);
		try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS))
		{
			copy(input, archive, attributes.size());
			if (input.read() != -1)
				throw new IOException("Input grew while packing: " + source);
		}
		archive.closeArchiveEntry();
	}

	/**
	 * Packs one bounded file from its contents.
	 *
	 * @param archive the destination
	 * @param content the file's contents
	 * @param name    its archive name
	 * @param maximum the content bound
	 * @param budget  cumulative limits
	 * @throws IOException if the contents exceed a bound or cannot be written
	 */
	private static void packBytes(TarArchiveOutputStream archive, byte[] content, String name, long maximum,
		Budget budget) throws IOException
	{
		budget.add(content.length, maximum);
		TarArchiveEntry entry = new TarArchiveEntry(name);
		entry.setSize(content.length);
		entry.setMode(modeBits(FILE_MODE));
		archive.putArchiveEntry(entry);
		archive.write(content);
		archive.closeArchiveEntry();
	}

	/**
	 * Rejects a symbolic link in any existing input path component.
	 *
	 * @param source the input path
	 * @throws IOException if a component is a symbolic link
	 */
	private static void checkAncestors(Path source) throws IOException
	{
		Path component = source.toAbsolutePath().getRoot();
		for (Path name : source.toAbsolutePath())
		{
			component = component.resolve(name);
			if (Files.isSymbolicLink(component))
				throw new IOException("Input path contains a symbolic link: " + source);
		}
	}

	/**
	 * Extracts only ordinary files and directories from a private archive copy.
	 *
	 * @param source the private archive
	 * @param target the private empty destination
	 * @param limits the bounds that the archive must stay within
	 * @throws IOException if an entry is malformed, unsafe or exceeds request limits
	 */
	static void unpack(Path source, Path target, RequestLimits limits) throws IOException
	{
		Budget budget = new Budget(limits);
		Set<Path> seen = new HashSet<>();
		try (InputStream bytes = Files.newInputStream(source);
			TarArchiveInputStream archive = new BoundedTarInput(bytes, limits))
		{
			for (TarArchiveEntry entry = archive.getNextEntry(); entry != null; entry = archive.getNextEntry())
			{
				Path path = resolveEntry(target, entry.getName());
				if (!seen.add(path))
					throw new IOException("Duplicate request archive entry: " + entry.getName());
				byte type = entry.getLinkFlag();
				boolean plainType = type == '0' || type == 0 || type == '5';
				if (!plainType || entry.isSparse() || !entry.isCheckSumOK())
					throw new IOException("Request archive entry is not a plain file or directory: " + entry.getName());
				long maximum = MAXIMUM_ENTRY_BYTES;
				if (entry.getName().equals(MANIFEST))
					maximum = MAXIMUM_MANIFEST_BYTES;
				else if (entry.getName().startsWith("prompts/"))
					maximum = MAXIMUM_PROMPT_BYTES;
				budget.add(entry.getSize(), maximum);
				if (type == '5')
				{
					if (entry.getSize() != 0)
						throw new IOException("Request archive directory has content: " + entry.getName());
					Files.createDirectories(path);
					continue;
				}
				Files.createDirectories(path.getParent());
				try (OutputStream output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW))
				{
					copy(archive, output, entry.getSize());
				}
				Set<PosixFilePermission> mode = EnumSet.of(PosixFilePermission.OWNER_READ,
					PosixFilePermission.OWNER_WRITE);
				if ((entry.getMode() & modeBits(Set.of(PosixFilePermission.OWNER_EXECUTE))) != 0)
					mode.add(PosixFilePermission.OWNER_EXECUTE);
				PortableFiles.setMode(path, mode);
			}
		}
	}

	/**
	 * Converts readable permissions at the tar API boundary.
	 *
	 * @param permissions the POSIX permissions
	 * @return the tar mode bits
	 */
	private static int modeBits(Set<PosixFilePermission> permissions)
	{
		int bits = 0;
		for (PosixFilePermission permission : permissions)
			bits |= 0400 >> permission.ordinal();
		return bits;
	}

	/**
	 * Resolves an archive name without accepting platform-specific or parent path syntax.
	 *
	 * @param target the extraction directory
	 * @param name   the archive name
	 * @return a descendant of the extraction directory
	 * @throws IOException if the name is unsafe
	 */
	private static Path resolveEntry(Path target, String name) throws IOException
	{
		if (name.isBlank() || name.startsWith("/") || name.contains("\\") || name.contains(":"))
			throw new IOException("Request archive path must be relative: " + name);
		for (String component : name.split("/"))
		{
			if (component.equals("..") || component.equals("."))
				throw new IOException("Request archive path contains a parent or dot component: " + name);
		}
		Path directory = target.toAbsolutePath().normalize();
		Path path = directory.resolve(name).normalize();
		if (path.equals(directory) || !path.startsWith(directory))
			throw new IOException("Request archive path escapes inputs: " + name);
		return path;
	}

	/**
	 * Copies an exact byte count with bounded working memory.
	 *
	 * @param input  the source
	 * @param output the destination
	 * @param size   the byte count
	 * @throws IOException if the stream ends early or writing fails
	 */
	private static void copy(InputStream input, OutputStream output, long size) throws IOException
	{
		byte[] buffer = new byte[8192];
		long remaining = size;
		while (remaining > 0)
		{
			int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
			if (read == -1)
				throw new IOException("Request input ends before its declared size");
			output.write(buffer, 0, read);
			remaining -= read;
		}
	}

	/**
	 * Checks physical headers before Commons Compress can dispatch metadata or recurse into another header.
	 * Only GNU long names are needed by the writer; PAX and sparse metadata are outside this protocol.
	 */
	private static final class BoundedTarInput extends TarArchiveInputStream
	{
		private final Budget budget;
		private boolean longName;

		/**
		 * Wraps a private archive stream.
		 *
		 * @param input  the bounded archive bytes
		 * @param limits the bounds that the archive must stay within
		 */
		private BoundedTarInput(InputStream input, RequestLimits limits)
		{
			super(input);
			this.budget = new Budget(limits);
		}

		@Override
		protected byte[] readRecord() throws IOException
		{
			byte[] record = super.readRecord();
			if (isEOFRecord(record))
				return record;
			TarArchiveEntry entry = new TarArchiveEntry(record);
			byte type = entry.getLinkFlag();
			boolean metadata = type == 'L';
			boolean plain = type == '0' || type == 0 || type == '5';
			if (!entry.isCheckSumOK() || (!plain && !metadata) || (metadata && longName))
				throw new IOException("Request archive contains invalid or unsupported physical headers");
			long maximum = MAXIMUM_ENTRY_BYTES;
			if (metadata)
				maximum = MAXIMUM_MANIFEST_BYTES;
			budget.add(entry.getSize(), maximum);
			longName = metadata;
			return record;
		}
	}

	/**
	 * Applies the same payload and entry limits on both sides of the archive boundary.
	 */
	private static final class Budget
	{
		private final RequestLimits limits;
		private int entries;
		private long bytes;

		/**
		 * Creates an empty account.
		 *
		 * @param limits the request's entry-count and total-size bounds
		 */
		private Budget(RequestLimits limits)
		{
			this.limits = limits;
		}

		/**
		 * Accounts for one entry before reading or writing its content.
		 *
		 * @param size    the entry's byte count
		 * @param maximum the entry's content bound
		 * @throws IOException if any request bound is exceeded
		 */
		private void add(long size, long maximum) throws IOException
		{
			entries += 1;
			if (entries > limits.entries() || size < 0 || size > maximum || size > limits.totalBytes() - bytes)
				throw new IOException("Request exceeds entry count, per-entry or total size bounds: " + size + " bytes");
			bytes += size;
		}
	}

	/**
	 * Prevents construction.
	 */
	private RequestArchive()
	{
	}
}
