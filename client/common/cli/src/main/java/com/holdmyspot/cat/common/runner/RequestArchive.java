package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;

/**
 * The archive in which a sandboxed session sends a session request: its manifest format, which the run's runner and
 * the session's request server share, and the operations that pack a request inside the sender's sandbox and copy,
 * extract and read it on the receiver's side. A request that a run's runner serves may choose fewer fields than one
 * that a request server serves, and the run's runner refuses by name each field that only the server reads.
 * <p>
 * A sender packs only what its sandbox lets it read, leaving out the null-device placeholders of files that it may not
 * read. It follows no link, except that a symbolic link in the project whose target stays inside the project travels
 * as a link, and any other link in the project travels as a read-only copy of the file or directory tree that it
 * leads to. A
 * receiver has the user's permissions, so it opens only the one archive, copies it without following links, and
 * extracts only plain files, directories and the project's links, within {@link RequestLimits}. It creates the links
 * after every file and directory, and refuses an entry whose path passes through a link and a link whose target leaves
 * the project. It extracts each read-only copy beside the project and links the copy's place in the project to it, so
 * that the extracted project holds a link to a target outside it where the sender's project did.
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
	 * The directory beside the extracted project that holds the copies of the targets of the project's links outside
	 * the sender's project.
	 */
	static final String LINK_TARGETS = "link-targets";
	/**
	 * The prefix of the temporary file that a sender writes beside a request before renaming it into place.
	 */
	private static final String TEMPORARY_PREFIX = ".tmp-";
	/**
	 * The version of the manifest of a request made inside a run.
	 */
	static final int VERSION = 3;
	/**
	 * The most bytes of a manifest at every level, which a receiver reads into memory.
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
		"plugins", "model", "effort", "session_transcripts", "install_cat", EXTRA_PERMISSIONS,
		StreamedRequest.INPUT_FIELD, "workspace", "read_only_evidence", "deny_read", "instruction_files",
		"tool_output_token_limit", "keep_run");
	/**
	 * The manifest fields that a session's request server reads and the run's runner refuses, because the run's runner
	 * chooses them for the sessions that it starts.
	 */
	private static final Set<String> SERVED_FIELDS = Set.of("agent", "session_requests", "request_depth",
		"capacity_wait", "max_concurrency");
	private static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-r--r--");
	private static final Set<PosixFilePermission> PROGRAM_MODE = PosixFilePermissions.fromString("rwxr-xr-x");
	/**
	 * The mode of a file in a copy of a project link's target, which the receiver extracts read-only.
	 */
	private static final Set<PosixFilePermission> READ_ONLY_FILE_MODE = PosixFilePermissions.fromString("r--r--r--");
	/**
	 * The mode of a program in a copy of a project link's target, which the receiver extracts read-only.
	 */
	private static final Set<PosixFilePermission> READ_ONLY_PROGRAM_MODE = PosixFilePermissions.
		fromString("r-xr-xr-x");
	/**
	 * The mode of a directory of the project.
	 */
	private static final Set<PosixFilePermission> DIRECTORY_MODE = PROGRAM_MODE;
	/**
	 * The mode of a directory in a copy of a project link's target, which the receiver extracts read-only.
	 */
	private static final Set<PosixFilePermission> READ_ONLY_DIRECTORY_MODE = READ_ONLY_PROGRAM_MODE;

	/**
	 * Publishes a request made inside a run with archive-relative manifest paths, packing prompt texts after the
	 * request's prompt files. The request's {@code workspace} and {@code read_only_evidence}, when it names them,
	 * travel inside the archive too.
	 *
	 * @param json        the manifest mapper
	 * @param destination the final request file
	 * @param request     the launcher options; receives archive-relative paths
	 * @param prompts     the prompt texts that follow the request's prompt files, in order
	 * @param nullDevice  the null device, which a sandbox mounts over each file that the session may not read; the
	 *                    request leaves those files out of the trees that it carries
	 * @param limits      the bounds that the archive must stay within
	 * @throws IOException if an input path passes through a symbolic link, a plugin, workspace or evidence input contains
	 *                     one, a link in the project leads to a target that is missing, that the sandbox does not let
	 *                     the session read or that holds the project, or an input is special, oversized, or cannot be
	 *                     packed
	 */
	static void pack(JsonMapper json, Path destination, ObjectNode request, List<String> prompts, Path nullDevice,
		RequestLimits limits) throws IOException
	{
		Set<Path> skipped = RequestServer.ownEntries(destination.toAbsolutePath().getParent().toRealPath().
			getParent());
		publish(json, destination, limits, (archive, budget) ->
		{
			Path project = Path.of(request.path("project").asString());
			packProject(archive, project, budget, nullDevice, skipped);
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
						packTree(archive, source, name, budget, nullDevice, skipped);
					paths.set(index, json.getNodeFactory().stringNode(name));
				}
			}
			if (request.has("workspace"))
			{
				packTree(archive, Path.of(request.path("workspace").asString()), "workspace", budget, nullDevice,
					skipped);
				request.put("workspace", "workspace");
			}
			if (request.has("read_only_evidence"))
			{
				ArrayNode evidence = request.withArray("read_only_evidence");
				if (evidence.size() > MAXIMUM_RUN_INPUTS)
				{
					throw new IOException("Request accepts at most " + MAXIMUM_RUN_INPUTS +
						" inputs: read_only_evidence");
				}
				for (int index = 0; index < evidence.size(); ++index)
				{
					Path source = Path.of(evidence.get(index).asString());
					String name = "read_only_evidence/" + index;
					if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS))
						packTree(archive, source, name, budget, nullDevice, skipped);
					else
						packFile(archive, source, name, limits.entryBytes(), budget);
					evidence.set(index, json.getNodeFactory().stringNode(name));
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
					if (entry.isSymbolicLink())
						linkTarget(destination.getParent(), path, entry);
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
	 * @param prefix    the start of a request archive's name before its identifier
	 * @return the identifier of each file named {@code <prefix><id>.tar} whose identifier could name a directory, in
	 *   ascending order; names that start with a period are skipped
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the directory cannot be listed
	 */
	public static List<String> pendingIds(Path directory, String prefix) throws IOException
	{
		requireThat(directory, "directory").isNotNull();
		requireThat(prefix, "prefix").isNotNull();
		List<String> ids = new ArrayList<>();
		try (DirectoryStream<Path> files = Files.newDirectoryStream(directory))
		{
			for (Path file : files)
			{
				String name = file.getFileName().toString();
				if (name.startsWith(".") || !name.startsWith(prefix) || !name.endsWith(EXTENSION))
					continue;
				String id = name.substring(prefix.length(), name.length() - EXTENSION.length());
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
	 * @throws RequestArchiveBusyException if another handle to the file is open on Windows, where the file is opened
	 *                              exclusively so that no writer can change it while it is read
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
	 * Packs the project directory and its descendants, leaving out the null-device placeholders of files that the
	 * session may not read and the request server's own entries. A symbolic link whose target stays inside the project
	 * travels as a link with the same target; any other link travels as a copy of the file or directory tree that it
	 * leads to, which counts against the same limits as the project's own files. The entries of that copy lack every
	 * write permission, whatever the target's own mode, so that the receiver extracts them read-only.
	 *
	 * @param archive    the destination archive
	 * @param source     the readable project directory
	 * @param budget     the request's cumulative limits
	 * @param nullDevice the null device, which a sandbox mounts over each file that the session may not read
	 * @param skipped    the real paths of the entries that the archive leaves out with their descendants
	 * @throws IOException if the project's path passes through a symbolic link, a link in the project leads to a target
	 *                     that is missing, that the sandbox does not let the session read or that holds the project, or
	 *                     the project is oversized or unreadable
	 */
	private static void packProject(TarArchiveOutputStream archive, Path source, Budget budget, Path nullDevice,
		Set<Path> skipped) throws IOException
	{
		checkAncestors(source);
		if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Input must be a directory: " + source);
		long maximum = budget.limits.entryBytes();
		ProjectTree.copy(source.toAbsolutePath().normalize(), nullDevice, skipped::contains, new ProjectTree.Writer()
		{
			@Override
			public void directory(Path relative, Path directory, boolean copy) throws IOException
			{
				budget.add(0, maximum);
				TarArchiveEntry entry = new TarArchiveEntry(entryName(relative) + "/");
				if (copy)
					entry.setMode(modeBits(READ_ONLY_DIRECTORY_MODE));
				else
					entry.setMode(modeBits(DIRECTORY_MODE));
				archive.putArchiveEntry(entry);
				archive.closeArchiveEntry();
			}

			@Override
			public void file(Path relative, Path file, boolean copy) throws IOException
			{
				packFile(archive, file, entryName(relative), maximum, budget, copy);
			}

			@Override
			public void link(Path relative, Path target) throws IOException
			{
				budget.add(0, maximum);
				TarArchiveEntry entry = new TarArchiveEntry(entryName(relative), TarConstants.LF_SYMLINK);
				entry.setLinkName(target.toString().replace(target.getFileSystem().getSeparator(), "/"));
				archive.putArchiveEntry(entry);
				archive.closeArchiveEntry();
			}

			/**
			 * Returns the archive name of a path in the project.
			 *
			 * @param relative the path relative to the project
			 * @return its name
			 */
			private String entryName(Path relative)
			{
				String name = relative.toString().replace(relative.getFileSystem().getSeparator(), "/");
				if (name.isEmpty())
					return "project";
				return "project/" + name;
			}
		});
	}

	/**
	 * Packs one plain directory and its descendants without following symbolic links, leaving out the null-device
	 * placeholders of files that the session may not read and the request server's own entries.
	 *
	 * @param archive    the destination archive
	 * @param source     the readable directory
	 * @param name       its archive name
	 * @param budget     the request's cumulative limits
	 * @param nullDevice the null device, which a sandbox mounts over each file that the session may not read
	 * @param skipped    the real paths of the entries that the archive leaves out with their descendants
	 * @throws IOException if the input is unsafe, oversized or unreadable
	 */
	private static void packTree(TarArchiveOutputStream archive, Path source, String name, Budget budget,
		Path nullDevice, Set<Path> skipped) throws IOException
	{
		checkAncestors(source);
		if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Input must be a directory without symbolic links: " + source);
		Path realSource = source.toRealPath(LinkOption.NOFOLLOW_LINKS);
		Files.walkFileTree(source, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
				throws IOException
			{
				if (skipped.contains(realSource.resolve(source.relativize(directory))))
					return FileVisitResult.SKIP_SUBTREE;
				budget.add(0, budget.limits.entryBytes());
				archive.putArchiveEntry(new TarArchiveEntry(entryName(directory) + "/"));
				archive.closeArchiveEntry();
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
			{
				if (!skipped.contains(realSource.resolve(source.relativize(file))) &&
					!SandboxPlaceholders.isPlaceholder(file, attributes, nullDevice))
				{
					packFile(archive, file, entryName(file), budget.limits.entryBytes(), budget);
				}
				return FileVisitResult.CONTINUE;
			}

			/**
			 * Returns the archive name of a path in the tree.
			 *
			 * @param path the path
			 * @return its name
			 */
			private String entryName(Path path)
			{
				String relative = source.relativize(path).toString().replace(source.getFileSystem().getSeparator(), "/");
				if (relative.isEmpty())
					return name;
				return name + "/" + relative;
			}
		});
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
		packFile(archive, source, name, maximum, budget, false);
	}

	/**
	 * Packs exactly one bounded regular file, preserving its executable permission.
	 *
	 * @param archive  the destination
	 * @param source   the input file
	 * @param name     its archive name
	 * @param maximum  the content bound
	 * @param budget   cumulative limits
	 * @param readOnly {@code true} if the receiver extracts the file read-only
	 * @throws IOException if the file is unsafe, changes size or cannot be read
	 */
	private static void packFile(TarArchiveOutputStream archive, Path source, String name, long maximum,
		Budget budget, boolean readOnly) throws IOException
	{
		checkAncestors(source);
		BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class,
			LinkOption.NOFOLLOW_LINKS);
		if (!attributes.isRegularFile())
			throw new IOException("Input must be a regular file without symbolic links or special types: " + source);
		budget.add(attributes.size(), maximum);
		TarArchiveEntry entry = new TarArchiveEntry(name);
		entry.setSize(attributes.size());
		boolean program = PortableFiles.mode(source).contains(PosixFilePermission.OWNER_EXECUTE);
		Set<PosixFilePermission> mode;
		if (readOnly && program)
			mode = READ_ONLY_PROGRAM_MODE;
		else if (readOnly)
			mode = READ_ONLY_FILE_MODE;
		else if (program)
			mode = PROGRAM_MODE;
		else
			mode = FILE_MODE;
		entry.setMode(modeBits(mode));
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
	 * Extracts only ordinary files, directories and the project's symbolic links from a private archive copy, creating
	 * the links after every file and directory. A file or directory of the project whose entry lacks the owner's write
	 * permission, as the copy of a link's target does, goes with the rest of its copy into its own directory of
	 * {@code link-targets} beside the project, read-only as {@link PortableFiles#setReadOnly(Path)} makes it, and the
	 * project holds a symbolic link to it in its place. A runner that copies the extracted project therefore copies the
	 * copy as it copies any target of a link outside the project: read-only, and checked for changes once its session
	 * ends.
	 *
	 * @param source the private archive
	 * @param target the private empty destination
	 * @param limits the bounds that the archive must stay within
	 * @throws IOException if an entry is malformed, unsafe or exceeds request limits, a link lies outside the project or
	 *                     its target leaves the project or does not exist, an entry's path passes through a link, an
	 *                     entry lies in {@code link-targets}, or a copy of a link's target holds a writable entry, a
	 *                     link or another copy, or comes after an entry inside it
	 */
	public static void unpack(Path source, Path target, RequestLimits limits) throws IOException
	{
		requireThat(source, "source").isNotNull();
		requireThat(target, "target").isNotNull();
		requireThat(limits, "limits").isNotNull();
		Budget budget = new Budget(limits);
		Set<Path> seen = new HashSet<>();
		Map<Path, Path> links = new LinkedHashMap<>();
		// The path in the project of each copy of a link's target, and where the copy goes instead
		Map<Path, Path> copies = new LinkedHashMap<>();
		List<Path> readOnlyDirectories = new ArrayList<>();
		Path directory = target.toAbsolutePath().normalize();
		Path project = directory.resolve("project");
		Path linkTargets = directory.resolve(LINK_TARGETS);
		int ownerWrite = modeBits(Set.of(PosixFilePermission.OWNER_WRITE));
		try (InputStream bytes = Files.newInputStream(source);
			TarArchiveInputStream archive = new BoundedTarInput(bytes, limits))
		{
			for (TarArchiveEntry entry = archive.getNextEntry(); entry != null; entry = archive.getNextEntry())
			{
				Path path = resolveEntry(target, entry.getName());
				if (!seen.add(path))
					throw new IOException("Duplicate request archive entry: " + entry.getName());
				if (path.startsWith(linkTargets))
				{
					throw new IOException("Request archive entry " + entry.getName() + " lies in " + LINK_TARGETS +
						", which holds the copies of the targets of the project's links");
				}
				byte type = entry.getLinkFlag();
				boolean plainType = type == '0' || type == 0 || type == '5' || type == TarConstants.LF_SYMLINK;
				if (!plainType || entry.isSparse() || !entry.isCheckSumOK())
				{
					throw new IOException("Request archive entry is not a plain file, directory or symbolic link: " +
						entry.getName());
				}
				long maximum = limits.entryBytes();
				if (entry.getName().equals(MANIFEST))
					maximum = MAXIMUM_MANIFEST_BYTES;
				else if (entry.getName().startsWith("prompts/"))
					maximum = MAXIMUM_PROMPT_BYTES;
				budget.add(entry.getSize(), maximum);
				// Only a copy of a project link's target lacks the owner's write permission
				boolean readOnly = path.startsWith(project) && !path.equals(project) &&
					type != TarConstants.LF_SYMLINK && (entry.getMode() & ownerWrite) == 0;
				Path destination = place(copies, path, project, linkTargets, readOnly, type, entry);
				if (type == '5')
				{
					if (entry.getSize() != 0)
						throw new IOException("Request archive directory has content: " + entry.getName());
					Files.createDirectories(destination);
					if (readOnly)
						readOnlyDirectories.add(destination);
					continue;
				}
				Files.createDirectories(destination.getParent());
				if (type == TarConstants.LF_SYMLINK)
				{
					links.put(path, linkTarget(target, path, entry));
					continue;
				}
				try (OutputStream output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW))
				{
					copy(archive, output, entry.getSize());
				}
				Set<PosixFilePermission> mode = EnumSet.of(PosixFilePermission.OWNER_READ,
					PosixFilePermission.OWNER_WRITE);
				if ((entry.getMode() & modeBits(Set.of(PosixFilePermission.OWNER_EXECUTE))) != 0)
					mode.add(PosixFilePermission.OWNER_EXECUTE);
				PortableFiles.setMode(destination, mode);
				if (readOnly)
					PortableFiles.setReadOnly(destination);
			}
		}
		for (Path path : seen)
		{
			for (Path ancestor = path.getParent(); !ancestor.equals(directory); ancestor = ancestor.getParent())
			{
				if (links.containsKey(ancestor))
				{
					throw new IOException("Request archive entry " + directory.relativize(path) + " passes through the " +
						"symbolic link " + directory.relativize(ancestor));
				}
			}
		}
		linkCopies(copies, directory);
		ProjectTree.createLinks(project, links);
		// A directory becomes read-only only once nothing more is added to it
		for (Path readOnly : readOnlyDirectories)
			PortableFiles.setReadOnly(readOnly);
	}

	/**
	 * Returns where an extracted entry goes. A read-only entry of the project, which only a copy of a project link's
	 * target holds, goes into a directory of {@link #LINK_TARGETS} beside the project with the rest of its copy, so that
	 * the project holds a link to it, as the sender's project did, and a runner that copies the project makes a
	 * read-only copy of it that the runner checks for changes. Every other entry goes to its own path.
	 *
	 * @param copies      the path in the project of each copy found so far, and where it goes instead; receives a new
	 *                    copy
	 * @param path        the entry's resolved path
	 * @param project     the extracted project
	 * @param linkTargets the directory that holds the copies
	 * @param readOnly    {@code true} if the entry is a read-only file or directory of the project
	 * @param type        the entry's type
	 * @param entry       the entry
	 * @return the path that the entry goes to
	 * @throws IOException if a copy holds a writable entry or a symbolic link
	 */
	private static Path place(Map<Path, Path> copies, Path path, Path project, Path linkTargets, boolean readOnly,
		byte type, TarArchiveEntry entry) throws IOException
	{
		for (Path ancestor = path.getParent(); ancestor != null && ancestor.startsWith(project) &&
			!ancestor.equals(project); ancestor = ancestor.getParent())
		{
			Path copy = copies.get(ancestor);
			if (copy == null)
				continue;
			if (type == TarConstants.LF_SYMLINK)
			{
				throw new IOException("Request archive entry " + entry.getName() + " is a symbolic link in a copy of a " +
					"project link's target");
			}
			if (!readOnly)
			{
				throw new IOException("Request archive entry " + entry.getName() + " is writable but lies in a " +
					"read-only copy of a project link's target");
			}
			return copy.resolve(ancestor.relativize(path));
		}
		if (!readOnly)
			return path;
		Path copy = linkTargets.resolve(String.valueOf(copies.size()));
		copies.put(path, copy);
		return copy;
	}

	/**
	 * Links each path in the extracted project that a copy of a link's target stands for to the copy.
	 *
	 * @param copies    the path in the project of each copy, and where it went instead
	 * @param directory the extraction directory
	 * @throws IOException if a copy's path lies inside another copy or holds another entry, or a link cannot be
	 *                     created
	 */
	private static void linkCopies(Map<Path, Path> copies, Path directory) throws IOException
	{
		for (Entry<Path, Path> copy : copies.entrySet())
		{
			Path path = copy.getKey();
			for (Path ancestor = path.getParent(); !ancestor.equals(directory); ancestor = ancestor.getParent())
			{
				if (copies.containsKey(ancestor))
				{
					throw new IOException("Request archive entry " + directory.relativize(path) + " comes before the " +
						"read-only copy " + directory.relativize(ancestor) + " that holds it");
				}
			}
			if (Files.exists(path, LinkOption.NOFOLLOW_LINKS))
			{
				throw new IOException("Request archive entry " + directory.relativize(path) + " is a read-only copy " +
					"of a project link's target, which another entry lies in");
			}
			Files.createDirectories(path.getParent());
			Files.createSymbolicLink(path, path.getParent().relativize(copy.getValue()));
		}
	}

	/**
	 * Returns the target of a symbolic link entry, which only the project holds.
	 *
	 * @param target the extraction directory
	 * @param path   the link's resolved path below {@code target}
	 * @param entry  the link entry
	 * @return the link's relative target
	 * @throws IOException if the link lies outside the project, has content, or names an absolute or platform-specific
	 *                     target
	 */
	private static Path linkTarget(Path target, Path path, TarArchiveEntry entry) throws IOException
	{
		Path project = target.toAbsolutePath().normalize().resolve("project");
		if (!path.startsWith(project) || path.equals(project))
			throw new IOException("Request archive symbolic link " + entry.getName() + " lies outside the project");
		if (entry.getSize() != 0)
			throw new IOException("Request archive symbolic link has content: " + entry.getName());
		String link = entry.getLinkName();
		if (link.isBlank() || link.startsWith("/") || link.contains("\\") || link.contains(":"))
		{
			throw new IOException("Request archive symbolic link " + entry.getName() + " has an absolute or " +
				"platform-specific target: " + link);
		}
		try
		{
			return Path.of(link);
		}
		catch (InvalidPathException e)
		{
			throw new IOException("Request archive symbolic link " + entry.getName() + " has an invalid target: " +
				link, e);
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
	 * Only GNU long names and long link targets are needed by the writer; PAX and sparse metadata are outside this
	 * protocol.
	 */
	private static final class BoundedTarInput extends TarArchiveInputStream
	{
		private final Budget budget;
		private boolean longName;
		private boolean longLink;

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
			boolean name = type == TarConstants.LF_GNUTYPE_LONGNAME;
			boolean link = type == TarConstants.LF_GNUTYPE_LONGLINK;
			boolean plain = type == '0' || type == 0 || type == '5' || type == TarConstants.LF_SYMLINK;
			if (!entry.isCheckSumOK() || (!plain && !name && !link) || (name && longName) || (link && longLink))
				throw new IOException("Request archive contains invalid or unsupported physical headers");
			long maximum = budget.limits.entryBytes();
			if (name || link)
				maximum = MAXIMUM_MANIFEST_BYTES;
			budget.add(entry.getSize(), maximum);
			longName = (longName || name) && !plain;
			longLink = (longLink || link) && !plain;
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
		return readRun(json, inputs, false);
	}

	/**
	 * Reads the manifest of an extracted request that a session's command made to the session's request server. Such a
	 * request may also choose the requested session's agent, its own session requests and their depth, its capacity
	 * wait and its concurrency ceiling, which the run's runner chooses inside a run.
	 *
	 * @param json   the manifest mapper
	 * @param inputs the private directory that {@link #unpack(Path, Path, RequestLimits)} extracted the request into
	 * @return the request, with its prompts' text already read
	 * @throws IOException              if the manifest is malformed, chooses a field that the request may not choose,
	 *                                  or names an entry that is missing or of the wrong type
	 * @throws IllegalArgumentException if the request names no agent and no model or no effort
	 */
	static RunRequest readServed(JsonMapper json, Path inputs) throws IOException
	{
		return readRun(json, inputs, true);
	}

	/**
	 * Reads the manifest of an extracted request.
	 *
	 * @param json   the manifest mapper
	 * @param inputs the private directory that {@link #unpack(Path, Path, RequestLimits)} extracted the request into
	 * @param served {@code true} for a request to a session's request server, {@code false} for one inside a run
	 * @return the request, with its prompts' text already read
	 * @throws IOException              if the manifest is malformed, chooses a field that the request may not choose,
	 *                                  or names an entry that is missing or of the wrong type
	 * @throws IllegalArgumentException if the request names no agent and no model or no effort
	 */
	private static RunRequest readRun(JsonMapper json, Path inputs, boolean served) throws IOException
	{
		Set<String> allowed = RUN_FIELDS;
		String level = "A request inside a run";
		if (served)
		{
			allowed = new HashSet<>(RUN_FIELDS);
			allowed.addAll(SERVED_FIELDS);
			level = "A session request";
		}
		JsonNode value = manifest(json, inputs, allowed, level, VERSION,
			"Request manifest requires format_version " + VERSION);
		String harness = harness(value);
		Path project = entry(value, inputs, "project", Kind.DIRECTORY);
		List<Path> plugins = namedEntries(value, inputs, "plugins", Kind.DIRECTORY, MAXIMUM_RUN_INPUTS);
		List<Path> promptFiles = namedEntries(value, inputs, "prompts", Kind.FILE, MAXIMUM_RUN_INPUTS);
		boolean streamed = value.has(StreamedRequest.INPUT_FIELD);
		if (streamed && !value.path(StreamedRequest.INPUT_FIELD).stringValue("").equals(StreamedRequest.STREAM))
		{
			throw new IOException("Request accepts only \"" + StreamedRequest.STREAM + "\" as its " +
				StreamedRequest.INPUT_FIELD);
		}
		if (streamed && !promptFiles.isEmpty())
			throw new IOException("A request whose records follow it carries no prompts: prompts");
		if (!streamed && promptFiles.isEmpty())
			throw new IOException("Request requires at least one prompt: prompts");
		List<String> prompts = new ArrayList<>();
		for (Path prompt : promptFiles)
			prompts.add(Files.readString(prompt, StandardCharsets.UTF_8));
		String model = optionalText(value, "model").orElse("");
		String effort = optionalText(value, "effort").orElse("");
		Optional<String> agent = optionalText(value, "agent");
		if (agent.isEmpty())
			HarnessRunnerOptions.requireProfile(harness, model, effort);
		Optional<Path> workspace = Optional.empty();
		if (value.has("workspace"))
			workspace = Optional.of(entry(value, inputs, "workspace", Kind.DIRECTORY));
		List<Path> evidence = namedEntries(value, inputs, "read_only_evidence", Kind.ANY, MAXIMUM_RUN_INPUTS);
		Optional<Integer> toolOutputTokenLimit = count(value, "tool_output_token_limit");
		if (toolOutputTokenLimit.isPresent() && toolOutputTokenLimit.get() <= 0)
			throw new IOException("tool_output_token_limit must be positive");
		return new RunRequest(harness, project, plugins, prompts, model, effort, flag(value, "session_transcripts"),
			flag(value, "install_cat"), extraPermissions(value), streamed, workspace, evidence,
			absolutePaths(value, "deny_read"), optionalText(value, "instruction_files"), toolOutputTokenLimit,
			flag(value, "keep_run"), agent, nonNegative(value, "session_requests").orElse(0),
			nonNegative(value, "request_depth").orElse(0), nonNegative(value, "capacity_wait"),
			positive(value, "max_concurrency"));
	}

	/**
	 * Reads an optional field that holds a whole number that is zero or more.
	 *
	 * @param value the manifest
	 * @param field the field
	 * @return the number, or empty when the field is absent
	 * @throws IOException if the field is present but not such a number
	 */
	private static Optional<Integer> nonNegative(JsonNode value, String field) throws IOException
	{
		Optional<Integer> number = count(value, field);
		if (number.isPresent() && number.get() < 0)
			throw new IOException(field + " may not be negative");
		return number;
	}

	/**
	 * Reads an optional field that holds a whole number that is one or more.
	 *
	 * @param value the manifest
	 * @param field the field
	 * @return the number, or empty when the field is absent
	 * @throws IOException if the field is present but not such a number
	 */
	private static Optional<Integer> positive(JsonNode value, String field) throws IOException
	{
		Optional<Integer> number = count(value, field);
		if (number.isPresent() && number.get() <= 0)
			throw new IOException(field + " must be positive");
		return number;
	}

	/**
	 * Reads an optional field that lists absolute paths.
	 *
	 * @param value the manifest
	 * @param field the field
	 * @return the paths, or an empty list when the field is absent
	 * @throws IOException if the field is present but not an array of absolute paths
	 */
	private static List<Path> absolutePaths(JsonNode value, String field) throws IOException
	{
		if (!value.has(field))
			return List.of();
		JsonNode array = value.path(field);
		if (!array.isArray() || array.size() > MAXIMUM_RUN_INPUTS)
			throw new IOException(field + " must be an array of at most " + MAXIMUM_RUN_INPUTS + " absolute paths");
		List<Path> paths = new ArrayList<>();
		for (JsonNode element : array)
		{
			if (!element.isString())
				throw new IOException(field + " must be an array of absolute paths: " + element);
			Path path;
			try
			{
				path = Path.of(element.asString());
			}
			catch (InvalidPathException e)
			{
				throw new IOException(field + " must be an array of absolute paths: " + e.getInput(), e);
			}
			if (!path.isAbsolute())
				throw new IOException(field + " must be an array of absolute paths: " + path);
			paths.add(path);
		}
		return paths;
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
	 * @param json            the manifest mapper
	 * @param inputs          the extracted request
	 * @param allowed         the fields that the level allows
	 * @param level           names the level's requests in a refusal
	 * @param version         the level's {@code format_version}
	 * @param versionMismatch the refusal of a manifest of another {@code format_version}
	 * @return the manifest object
	 * @throws IOException if the manifest is missing, is not a JSON object of {@code version}, or chooses a field
	 *                     outside {@code allowed}
	 */
	private static JsonNode manifest(JsonMapper json, Path inputs, Set<String> allowed, String level, int version,
		String versionMismatch) throws IOException
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
		if (!value.path("format_version").isInt() || value.path("format_version").intValue() != version)
			throw new IOException(versionMismatch);
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
	 * Reads an optional integer field.
	 *
	 * @param value the manifest
	 * @param field the field
	 * @return its value, or empty when the field is absent
	 * @throws IOException if the field is present but not an {@code int}
	 */
	private static Optional<Integer> count(JsonNode value, String field) throws IOException
	{
		if (!value.has(field))
			return Optional.empty();
		if (!value.path(field).isInt())
			throw new IOException(field + " must be an integer");
		return Optional.of(value.path(field).intValue());
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
			case ANY -> directory || file;
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
		FILE,
		/**
		 * A directory or a regular file.
		 */
		ANY
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
	 * @param streamed           indicates whether the request's records follow it, as {@link StreamedRequest}
	 *                           publishes them, instead of its prompts
	 * @param workspace          the extracted workspace files, or empty if the request names none
	 * @param readOnlyEvidence   the extracted evidence files and directories that the session may read but not change
	 * @param deniedReads        the further paths that the session may not read
	 * @param instructionFiles   the instruction files that the session loads, or empty for its harness's default
	 * @param toolOutputTokenLimit the most tokens of one tool's output, or empty for its harness's default
	 * @param keepRun            indicates whether the requester receives a copy of the session's kept run
	 * @param agent              the agent whose definition gives the session's model and effort, or empty if the
	 *                           request names them; only a request to a session's request server names one
	 * @param sessionRequests    how many sessions the requested session may request in turn; only a request to a
	 *                           session's request server names a number other than {@code 0}
	 * @param requestDepth       how many levels below the requested session's requests may request sessions in turn
	 * @param capacityWait       the most seconds that the requested session waits for capacity, or empty for the
	 *                           requester's choice
	 * @param maximumConcurrency the most sessions that run at once, or empty for the requester's choice
	 */
	record RunRequest(String harness, Path project, List<Path> plugins, List<String> prompts, String model,
		String effort, boolean sessionTranscripts, boolean installCat, ExtraPermissions extraPermissions,
		boolean streamed, Optional<Path> workspace, List<Path> readOnlyEvidence, List<Path> deniedReads,
		Optional<String> instructionFiles, Optional<Integer> toolOutputTokenLimit, boolean keepRun,
		Optional<String> agent, int sessionRequests, int requestDepth, Optional<Integer> capacityWait,
		Optional<Integer> maximumConcurrency)
	{
	}

	/**
	 * Prevents construction.
	 */
	private RequestArchive()
	{
	}
}
