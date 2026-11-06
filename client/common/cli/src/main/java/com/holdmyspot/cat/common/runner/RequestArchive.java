package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;

/**
 * The archive in which a sandboxed session of a run sends a session request through {@link SessionRequestAccess}:
 * its manifest format, and the operations that pack a request inside the sender's sandbox and copy, extract and read
 * it on the receiver's side. A manifest that chooses a field that a request may not choose is refused by name.
 * <p>
 * A sender packs only what its sandbox lets it read, without following links and leaving out the null-device
 * placeholders of files that it may not read. A receiver has the user's permissions, so it opens only the one archive,
 * copies it without following links, and extracts only plain files and directories, within {@link RequestLimits}.
 * <p>
 * <b>Thread Safety:</b> This class is stateless and thread-safe.
 */
public final class RequestArchive
{
	/**
	 * The harnesses that a request may choose for its child.
	 */
	static final Set<String> HARNESSES = Set.of("claude", "codex");
	static final String EXTENSION = ".tar";
	static final String MANIFEST = "request.json";
	/**
	 * The version of the manifest.
	 */
	static final int VERSION = 3;
	/**
	 * The most bytes of a manifest, which a receiver reads into memory.
	 */
	static final int MAXIMUM_MANIFEST_BYTES = 65_536;
	/**
	 * The most bytes of one prompt of a request made inside a run, which a receiver reads into memory.
	 */
	static final int MAXIMUM_PROMPT_BYTES = 1_048_576;
	/**
	 * The most prompts or plugins of a request made inside a run.
	 */
	private static final int MAXIMUM_RUN_INPUTS = 32;
	/**
	 * The device that a sandbox mounts over each file that a session may not read.
	 */
	static final Path NULL_DEVICE = Path.of("/dev/null");
	/**
	 * The manifest field that holds a request's extra permissions.
	 */
	static final String EXTRA_PERMISSIONS = "extra_permissions";
	private static final Set<String> RUN_FIELDS = Set.of("format_version", "harness", "project", "prompts",
		"plugins", "model", "effort", "session_transcripts", "install_cat", EXTRA_PERMISSIONS);
	/**
	 * The start of the name of a request file that its sender is still writing.
	 */
	private static final String TEMPORARY_PREFIX = ".tmp-";
	private static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-r--r--");
	private static final Set<PosixFilePermission> PROGRAM_MODE = PosixFilePermissions.fromString("rwxr-xr-x");

	/**
	 * Publishes a request made inside a run with archive-relative manifest paths, packing prompt texts after the
	 * request's prompt files.
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
		publish(json, destination, limits, (archive, budget) ->
		{
			Path project = Path.of(request.path("project").asString());
			packTree(archive, project, "project", budget, nullDevice);
			request.put("project", "project");
			for (String field : new String[]{"prompts", "plugins"})
			{
				ArrayNode paths = request.withArray(field);
				if (paths.size() > MAXIMUM_RUN_INPUTS)
					throw new IOException("Request accepts at most " + MAXIMUM_RUN_INPUTS + " inputs: " + field);
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
			if (names.size() + prompts.size() > MAXIMUM_RUN_INPUTS)
				throw new IOException("Request accepts at most " + MAXIMUM_RUN_INPUTS + " inputs: prompts");
			for (String prompt : prompts)
			{
				String name = "prompts/" + names.size();
				packBytes(archive, prompt.getBytes(StandardCharsets.UTF_8), name, MAXIMUM_PROMPT_BYTES, budget);
				names.add(name);
			}
			return request;
		});
	}

	/**
	 * Writes an archive beside its destination, checks it as its receiver will, and renames it into place.
	 *
	 * @param json        the manifest mapper
	 * @param destination the final request file
	 * @param limits      the bounds that the archive must stay within
	 * @param contents    packs the request's inputs and returns its manifest
	 * @throws IOException if packing fails, the archive exceeds {@code limits}, or it cannot be renamed into place
	 */
	private static void publish(JsonMapper json, Path destination, RequestLimits limits, Contents contents)
		throws IOException
	{
		Path temporary = Files.createTempFile(destination.getParent(), TEMPORARY_PREFIX,
			EXTENSION);
		try
		{
			try (OutputStream bytes = Files.newOutputStream(temporary);
				TarArchiveOutputStream archive = new TarArchiveOutputStream(bytes))
			{
				archive.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
				// Star's binary size field lets an entry exceed the 8 GiB of the octal field without the PAX headers that
				// the receiver refuses.
				archive.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_STAR);
				Budget budget = new Budget(limits);
				ObjectNode request = contents.write(archive, budget);
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
	 * Returns the identifiers of the request archives that a directory holds, skipping files that their senders are
	 * still writing.
	 *
	 * @param directory the directory that senders publish request archives to
	 * @return the identifier of each file named {@code <id>.tar} whose identifier could name a directory, in
	 *   ascending order; names that start with a period are skipped
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the directory cannot be listed
	 */
	public static List<String> pendingIds(Path directory) throws IOException
	{
		requireThat(directory, "directory").isNotNull();
		List<String> ids = new ArrayList<>();
		try (DirectoryStream<Path> files = Files.newDirectoryStream(directory))
		{
			for (Path file : files)
			{
				String name = file.getFileName().toString();
				if (name.startsWith(".") || !name.endsWith(EXTENSION))
					continue;
				String id = name.substring(0, name.length() - EXTENSION.length());
				try
				{
					SessionRequestCli.validateId(id);
				}
				catch (IllegalArgumentException _)
				{
					// Such a name cannot name a response directory. No sender generates one.
					continue;
				}
				ids.add(id);
			}
		}
		Collections.sort(ids);
		return ids;
	}

	/**
	 * Copies a published file into a private file of the receiver, opening it without following links and
	 * refusing it unless it is a regular file with one name.
	 *
	 * @param source      the published file
	 * @param destination the new private copy
	 * @param limits      the bounds whose {@link RequestLimits#archiveBytes()} the file must stay within
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the file is a link, is not a regular file, has another name, exceeds
	 *                              {@code limits}, or cannot be copied
	 */
	public static void copy(Path source, Path destination, RequestLimits limits) throws IOException
	{
		requireThat(source, "source").isNotNull();
		requireThat(destination, "destination").isNotNull();
		requireThat(limits, "limits").isNotNull();
		ArchiveSource.copy(source, destination, limits.archiveBytes());
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
					budget.add(0, budget.limits.entryBytes());
					archive.putArchiveEntry(new TarArchiveEntry(entryName + "/"));
					archive.closeArchiveEntry();
				}
				else if (!SandboxPlaceholders.isPlaceholder(path, attributes, nullDevice))
					packFile(archive, path, entryName, budget.limits.entryBytes(), budget);
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
	 * Rejects a symbolic link in any existing input path component below the file system's root directory.
	 * <p>
	 * On a POSIX file system only the administrator may change the root directory's entries, so neither the session
	 * nor its parent can plant or retarget a link there; the operating system's own links there, such as macOS's
	 * {@code /var} and {@code /tmp}, are accepted. The root of a Windows drive lets every user add entries, so there
	 * every link is rejected.
	 *
	 * @param source the input path
	 * @throws IOException if a component that the session or its parent may change is a symbolic link
	 */
	private static void checkAncestors(Path source) throws IOException
	{
		Path absolute = source.toAbsolutePath();
		boolean administratorOwnsRoot = absolute.getFileSystem().supportedFileAttributeViews().contains("posix");
		Path component = absolute.getRoot();
		for (Path name : absolute)
		{
			component = component.resolve(name);
			boolean rootEntry = component.getNameCount() == 1;
			if (Files.isSymbolicLink(component) && !(rootEntry && administratorOwnsRoot))
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
	public static void unpack(Path source, Path target, RequestLimits limits) throws IOException
	{
		requireThat(source, "source").isNotNull();
		requireThat(target, "target").isNotNull();
		requireThat(limits, "limits").isNotNull();
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
				long maximum = limits.entryBytes();
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
			long maximum = budget.limits.entryBytes();
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
	 * Reads the manifest of an extracted request made inside a run.
	 *
	 * @param json   the manifest mapper
	 * @param inputs the private directory that {@link #unpack(Path, Path, RequestLimits)} extracted the request into
	 * @return the request, with its prompts' text already read
	 * @throws IOException              if the manifest is malformed, chooses a field that a request inside a run may
	 *                                  not choose, or names an entry that is missing or of the wrong type
	 * @throws IllegalArgumentException if the request names no model or no effort
	 */
	static RunRequest readRun(JsonMapper json, Path inputs) throws IOException
	{
		JsonNode value = manifest(json, inputs, RUN_FIELDS, "A request inside a run");
		String harness = harness(value);
		Path project = entry(value, inputs, "project", Kind.DIRECTORY);
		List<Path> plugins = namedEntries(value, inputs, "plugins", Kind.DIRECTORY, MAXIMUM_RUN_INPUTS);
		List<Path> promptFiles = namedEntries(value, inputs, "prompts", Kind.FILE, MAXIMUM_RUN_INPUTS);
		if (promptFiles.isEmpty())
			throw new IOException("Request requires at least one prompt: prompts");
		List<String> prompts = new ArrayList<>();
		for (Path prompt : promptFiles)
			prompts.add(Files.readString(prompt, StandardCharsets.UTF_8));
		String model = optionalText(value, "model").orElse("");
		String effort = optionalText(value, "effort").orElse("");
		HarnessRunnerOptions.requireProfile(harness, model, effort);
		return new RunRequest(harness, project, plugins, prompts, model, effort, flag(value, "session_transcripts"),
			flag(value, "install_cat"), extraPermissions(value));
	}

	/**
	 * Reads the extra permissions that a manifest asks for.
	 *
	 * @param value the manifest
	 * @return the permissions, or none if the manifest has no such field
	 * @throws IOException if the field is not a valid document of extra permissions
	 */
	private static ExtraPermissions extraPermissions(JsonNode value) throws IOException
	{
		if (!value.has(EXTRA_PERMISSIONS))
			return ExtraPermissions.NONE;
		return ExtraPermissions.fromDocument(value.get(EXTRA_PERMISSIONS), "The request's " + EXTRA_PERMISSIONS);
	}

	/**
	 * Reads an extracted manifest and refuses a field that its level does not allow.
	 *
	 * @param json    the manifest mapper
	 * @param inputs  the extracted request
	 * @param allowed the fields that the level allows
	 * @param level   names the level's requests in a refusal
	 * @return the manifest object
	 * @throws IOException if the manifest is missing, is not a JSON object of {@link #VERSION}, or chooses a field
	 *                     outside {@code allowed}
	 */
	private static JsonNode manifest(JsonMapper json, Path inputs, Set<String> allowed, String level)
		throws IOException
	{
		Path file = inputs.resolve(MANIFEST);
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Request archive requires " + MANIFEST);
		JsonNode value;
		try
		{
			value = json.readTree(Files.readAllBytes(file));
		}
		catch (JacksonException e)
		{
			throw new IOException("Request manifest is not valid JSON: " + e.getOriginalMessage(), e);
		}
		if (!value.isObject())
			throw new IOException("Request manifest must be a JSON object");
		if (!value.path("format_version").isInt() || value.path("format_version").intValue() != VERSION)
			throw new IOException("Request manifest requires format_version " + VERSION);
		for (String field : value.propertyNames())
		{
			if (!allowed.contains(field))
				throw new IOException(level + " may not choose " + field);
		}
		return value;
	}

	/**
	 * Reads a request's harness.
	 *
	 * @param value the manifest
	 * @return the harness
	 * @throws IOException if it is missing or not one of {@link #HARNESSES}
	 */
	private static String harness(JsonNode value) throws IOException
	{
		String harness = optionalText(value, "harness").orElse("");
		if (!HARNESSES.contains(harness))
			throw new IOException("Unsupported requested harness: " + harness);
		return harness;
	}

	/**
	 * Reads an optional text field.
	 *
	 * @param value the manifest
	 * @param field the field
	 * @return its text, or empty when the field is absent
	 * @throws IOException if the field is present but not text
	 */
	private static Optional<String> optionalText(JsonNode value, String field) throws IOException
	{
		if (!value.has(field))
			return Optional.empty();
		if (!value.path(field).isString())
			throw new IOException("Request field must be text: " + field);
		return Optional.of(value.path(field).asString());
	}

	/**
	 * Reads an optional boolean field.
	 *
	 * @param value the manifest
	 * @param field the field
	 * @return its value, or {@code false} when the field is absent
	 * @throws IOException if the field is present but not a boolean
	 */
	private static boolean flag(JsonNode value, String field) throws IOException
	{
		if (!value.has(field))
			return false;
		if (!value.path(field).isBoolean())
			throw new IOException(field + " must be a boolean");
		return value.path(field).booleanValue();
	}

	/**
	 * Resolves a field that must name the archive entry of the same name.
	 *
	 * @param value  the manifest
	 * @param inputs the extracted request
	 * @param field  the field, which is also the entry's name
	 * @param kind   the entry's required type
	 * @return the extracted entry
	 * @throws IOException if the field does not name its entry, or the entry is missing or of the wrong type
	 */
	private static Path entry(JsonNode value, Path inputs, String field, Kind kind) throws IOException
	{
		if (!value.path(field).isString() || !value.path(field).asString().equals(field))
			throw new IOException(field + " must name the archive entry " + field);
		return extracted(inputs, field, kind);
	}

	/**
	 * Resolves a field that must list the archive entries {@code <field>/0}, {@code <field>/1} and so on, in order.
	 *
	 * @param value   the manifest
	 * @param inputs  the extracted request
	 * @param field   the field
	 * @param kind    each entry's required type
	 * @param maximum the most entries
	 * @return the extracted entries, or an empty list when the field is absent
	 * @throws IOException if the field is not such a list, or an entry is missing or of the wrong type
	 */
	private static List<Path> namedEntries(JsonNode value, Path inputs, String field, Kind kind, int maximum)
		throws IOException
	{
		if (!value.has(field))
			return List.of();
		JsonNode array = value.path(field);
		if (!array.isArray() || array.size() > maximum)
		{
			String bound = "";
			if (maximum != Integer.MAX_VALUE)
				bound = " of at most " + maximum;
			throw new IOException("Request requires an array" + bound + " of paths: " + field);
		}
		List<Path> paths = new ArrayList<>();
		for (JsonNode element : array)
		{
			String expected = field + "/" + paths.size();
			if (!element.isString() || !element.asString().equals(expected))
				throw new IOException("Request input must name its ordered archive entry: " + expected);
			paths.add(extracted(inputs, expected, kind));
		}
		return paths;
	}

	/**
	 * Resolves an extracted entry and checks its type.
	 *
	 * @param inputs the extracted request
	 * @param name   the entry's name
	 * @param kind   its required type
	 * @return the entry
	 * @throws IOException if the entry is missing or of the wrong type
	 */
	private static Path extracted(Path inputs, String name, Kind kind) throws IOException
	{
		Path path = inputs.resolve(name);
		boolean directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
		boolean file = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
		boolean matches = switch (kind)
		{
			case DIRECTORY -> directory;
			case FILE -> file;
		};
		if (!matches)
			throw new IOException("Request archive input is missing or has the wrong type: " + name);
		return path;
	}

	/**
	 * The type that a manifest field requires of its archive entry.
	 */
	private enum Kind
	{
		/**
		 * A directory.
		 */
		DIRECTORY,
		/**
		 * A regular file.
		 */
		FILE
	}

	/**
	 * Packs a request's inputs into its archive.
	 */
	@FunctionalInterface
	private interface Contents
	{
		/**
		 * Packs the inputs.
		 *
		 * @param archive the archive
		 * @param budget  the request's cumulative limits
		 * @return the manifest, whose paths name the entries packed
		 * @throws IOException if an input cannot be packed
		 */
		ObjectNode write(TarArchiveOutputStream archive, Budget budget) throws IOException;
	}

	/**
	 * An extracted request made inside a run.
	 *
	 * @param harness            the selected harness
	 * @param project            the extracted project
	 * @param plugins            the extracted plugins
	 * @param prompts            the prompts' text in turn order
	 * @param model              the selected model
	 * @param effort             the selected effort
	 * @param sessionTranscripts indicates whether native transcripts are requested
	 * @param installCat         indicates whether CAT is installed in the requested session's home
	 * @param extraPermissions   the writes and hosts that the requested session needs beyond its harness's
	 */
	record RunRequest(String harness, Path project, List<Path> plugins, List<String> prompts, String model,
		String effort, boolean sessionTranscripts, boolean installCat, ExtraPermissions extraPermissions)
	{
	}

	/**
	 * Prevents construction.
	 */
	private RequestArchive()
	{
	}
}
