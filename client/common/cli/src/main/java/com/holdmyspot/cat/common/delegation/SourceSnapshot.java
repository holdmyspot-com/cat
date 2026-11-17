package com.holdmyspot.cat.common.delegation;

import com.holdmyspot.cat.common.FileLinks;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.LinkOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.FileStore;
import java.nio.ByteBuffer;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Captures and materializes an immutable source set.
 */
public final class SourceSnapshot
{
	private static final LinkOption NO_FOLLOW = LinkOption.NOFOLLOW_LINKS;
	private static final int COPY_BUFFER_SIZE = 8192;
	private final Path objectDirectory;
	private final List<Entry> entries;
	private final String identity;

	/**
	 * Creates a snapshot value.
	 *
	 * @param objectDirectory content-addressed object directory
	 * @param entries immutable manifest entries
	 * @param identity canonical manifest identity
	 */
	private SourceSnapshot(Path objectDirectory, List<Entry> entries, String identity)
	{
		this.objectDirectory = objectDirectory;
		this.entries = List.copyOf(entries);
		this.identity = identity;
	}

	/**
	 * Captures selected project files into content-addressed storage.
	 *
	 * @param project project root
	 * @param cohort cohort-owned directory
	 * @param sourcePaths exact project-relative source paths
	 * @return immutable source snapshot
	 * @throws IOException if capture fails
	 */
	public static SourceSnapshot capture(Path project, Path cohort, List<String> sourcePaths) throws IOException
	{
		Path root = project.toRealPath();
		Path objectDirectory = cohort.resolve("source/objects");
		Files.createDirectories(objectDirectory);
		Set<String> selected = new HashSet<>(sourcePaths);
		if (selected.size() != sourcePaths.size())
			throw new IOException("Source paths are duplicated");
		List<Entry> entries = new ArrayList<>();
		for (String source : sourcePaths.stream().sorted().toList())
		{
			Path path = resolveSource(root, source);
			entries.add(captureEntry(root, path, source, selected, objectDirectory));
			Path directory = path.getParent();
			while (directory != null && directory.startsWith(root))
			{
				String relative = root.relativize(directory).toString().replace('\\', '/');
				if (relative.isEmpty())
					relative = ".";
				String directoryPath = relative;
				if (entries.stream().noneMatch(entry -> entry.path().equals(directoryPath)))
					entries.add(captureEntry(root, directory, directoryPath, selected, objectDirectory));
				if (directory.equals(root))
					break;
				directory = directory.getParent();
			}
		}
		entries.sort(java.util.Comparator.comparing(Entry::path));
		for (Entry entry : entries)
			verifyUnchanged(root, entry);
		StringBuilder manifest = new StringBuilder();
		for (Entry entry : entries)
			manifest.append(toJsonLine(entry)).append('\n');
		Path manifestPath = cohort.resolve("source/manifest.jsonl");
		Files.writeString(manifestPath, manifest.toString());
		return new SourceSnapshot(objectDirectory, entries,
			digest(manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
	}

	/**
	 * Loads a retained manifest and verifies its content-addressed source objects.
	 *
	 * @param mapper configured JSON mapper
	 * @param cohort cohort directory
	 * @return retained immutable snapshot
	 * @throws IOException if the manifest or any referenced object is invalid
	 */
	public static SourceSnapshot read(JsonMapper mapper, Path cohort) throws IOException
	{
		Path manifestPath = cohort.resolve("source/manifest.jsonl");
		Path objectDirectory = cohort.resolve("source/objects");
		List<Entry> entries = new ArrayList<>();
		StringBuilder manifest = new StringBuilder();
		try (BufferedReader input = Files.newBufferedReader(manifestPath))
		{
			String line = input.readLine();
			while (line != null)
			{
				manifest.append(line).append('\n');
				JsonNode value = mapper.readTree(line);
				if (value == null || !value.isObject())
					throw new IOException("Invalid snapshot entry in " + manifestPath);
				String path = text(value, "path", manifestPath);
				boolean present = bool(value, "present", manifestPath);
				String kind = text(value, "kind", manifestPath);
				String hash = text(value, "sha256", manifestPath);
				boolean executable = bool(value, "executable", manifestPath);
				String permissionText = text(value, "permissions", manifestPath);
				long modified = number(value, "last_modified_ms", manifestPath);
				String linkText = text(value, "link_text", manifestPath);
				Set<PosixFilePermission> permissions = PosixFilePermissions.fromString(permissionText);
				Entry entry = new Entry(path, present, kind, hash, executable, permissions, modified, linkText,
					text(value, "metadata_id", manifestPath));
				if (present && !kind.equals("directory"))
				{
					Path object = objectDirectory.resolve(hash);
					if (!Files.isRegularFile(object) || !digest(object).equals(hash))
						throw new IOException("Missing or corrupt snapshot object for " + path + " in " + manifestPath);
				}
				entries.add(entry);
				line = input.readLine();
			}
		}
		catch (JacksonException e)
		{
			throw new IOException("Malformed snapshot manifest: " + manifestPath, e);
		}
		return new SourceSnapshot(objectDirectory, entries,
			digest(manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
	}

	/**
	 * Reads a required string from a retained manifest entry.
	 *
	 * @param value manifest entry
	 * @param field field name
	 * @param manifest manifest path for diagnostics
	 * @return field value
	 * @throws IOException if the field is missing or has the wrong type
	 */
	private static String text(JsonNode value, String field, Path manifest) throws IOException
	{
		JsonNode member = value.get(field);
		if (member == null || !member.isString())
			throw new IOException("Invalid " + field + " in snapshot manifest " + manifest);
		return member.stringValue();
	}

	/**
	 * Reads a required boolean from a retained manifest entry.
	 *
	 * @param value manifest entry
	 * @param field field name
	 * @param manifest manifest path for diagnostics
	 * @return field value
	 * @throws IOException if the field is missing or has the wrong type
	 */
	private static boolean bool(JsonNode value, String field, Path manifest) throws IOException
	{
		JsonNode member = value.get(field);
		if (member == null || !member.isBoolean())
			throw new IOException("Invalid " + field + " in snapshot manifest " + manifest);
		return member.booleanValue();
	}

	/**
	 * Reads a required integer from a retained manifest entry.
	 *
	 * @param value manifest entry
	 * @param field field name
	 * @param manifest manifest path for diagnostics
	 * @return field value
	 * @throws IOException if the field is missing or has the wrong type
	 */
	private static long number(JsonNode value, String field, Path manifest) throws IOException
	{
		JsonNode member = value.get(field);
		if (member == null || !member.isIntegralNumber())
			throw new IOException("Invalid " + field + " in snapshot manifest " + manifest);
		return member.longValue();
	}

	/**
	 * Materializes the snapshot under a new project directory.
	 *
	 * @param destination destination root
	 * @throws IOException if materialization fails
	 */
	public void materialize(Path destination) throws IOException
	{
		materialize(destination, false);
	}

	/**
	 * Materializes selected entries over the matching files in an operation-owned checkout.
	 *
	 * @param destination destination project root
	 * @throws IOException if materialization fails
	 */
	void materializeReplacing(Path destination) throws IOException
	{
		materialize(destination, true);
	}

	/**
	 * Materializes entries, optionally replacing selected leaf files in a tool-owned checkout.
	 *
	 * @param destination destination project root
	 * @param replaceSelectedFiles whether to replace existing selected leaf entries
	 * @throws IOException if materialization fails
	 */
	private void materialize(Path destination, boolean replaceSelectedFiles) throws IOException
	{
		Path targetRoot = destination.toAbsolutePath().normalize();
		Files.createDirectories(targetRoot);
		for (Entry entry : entries)
		{
			Path target = targetRoot.resolve(entry.path()).normalize();
			if (!target.startsWith(targetRoot))
				throw new IOException("Snapshot path escapes its destination: " + entry.path());
			if (!entry.present())
				continue;
			if (entry.kind().equals("directory"))
			{
				Files.createDirectories(target);
				continue;
			}
			Files.createDirectories(target.getParent());
			if (replaceSelectedFiles)
				Files.deleteIfExists(target);
			Path object = objectDirectory.resolve(entry.sha256());
			if (entry.kind().equals("symlink"))
			{
				Files.createSymbolicLink(target, Path.of(Files.readString(object)));
				restoreAndVerifySymlinkMetadata(target, entry);
			}
			else
			{
				Files.copy(object, target, StandardCopyOption.COPY_ATTRIBUTES);
				Files.setLastModifiedTime(target, FileTime.fromMillis(entry.lastModifiedMillis()));
				if (entry.permissions().isEmpty() && entry.executable())
					makeExecutable(target);
				if (!entry.permissions().isEmpty())
					Files.setPosixFilePermissions(target, entry.permissions());
				if (!metadataIdentity(target).equals(entry.metadataIdentity()))
					throw new IOException("unsupported_metadata: snapshot materialization differs at " + entry.path());
			}
		}
		for (Entry entry : entries)
			if (entry.present() && entry.kind().equals("directory"))
			{
				Path target = targetRoot.resolve(entry.path()).normalize();
				if (!entry.permissions().isEmpty())
					Files.setPosixFilePermissions(target, entry.permissions());
				Files.setLastModifiedTime(target, FileTime.fromMillis(entry.lastModifiedMillis()));
				if (!metadataIdentity(target).equals(entry.metadataIdentity()))
					throw new IOException("unsupported_metadata: directory snapshot differs at " + entry.path());
			}
	}

	/**
	 * Restores captured metadata on a symbolic link and verifies the no-follow result.
	 *
	 * @param link materialized symbolic link
	 * @param entry captured manifest entry
	 * @throws IOException if the link metadata cannot be restored or verified
	 */
	private static void restoreAndVerifySymlinkMetadata(Path link, Entry entry) throws IOException
	{
		try
		{
			BasicFileAttributeView view = Files.getFileAttributeView(link, BasicFileAttributeView.class, NO_FOLLOW);
			if (view == null)
				throw new UnsupportedOperationException("Basic file attributes are unavailable");
			view.setTimes(FileTime.fromMillis(entry.lastModifiedMillis()), null, null);
			BasicFileAttributes attributes = view.readAttributes();
			if (!attributes.isSymbolicLink() || attributes.lastModifiedTime().toMillis() != entry.lastModifiedMillis() ||
				!metadataIdentity(link).equals(entry.metadataIdentity()))
				throw new IOException("restored symbolic-link metadata differs from the manifest");
		}
		catch (IOException | SecurityException | UnsupportedOperationException e)
		{
			throw new IOException("unsupported_metadata: snapshot materialization differs at " + entry.path(), e);
		}
	}

	/**
	 * Returns the immutable source identity.
	 *
	 * @return snapshot SHA-256 identity
	 */
	public String identity()
	{
		return identity;
	}

	/**
	 * Returns manifest entries.
	 *
	 * @return immutable selected path entries
	 */
	public List<Entry> entries()
	{
		return entries;
	}

	/**
	 * Validates captured ancestor-directory metadata before a replay begins.
	 *
	 * @param project project root
	 * @throws IOException if any captured directory changed since prepare
	 */
	public void verifyDirectories(Path project) throws IOException
	{
		Path root = project.toRealPath();
		for (Entry entry : entries)
			if (entry.present() && entry.kind().equals("directory"))
			{
				Path directory = root;
				if (!entry.path().equals("."))
					directory = root.resolve(entry.path()).normalize();
				BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class, NO_FOLLOW);
				if (!attributes.isDirectory() ||
					!metadataIdentity(directory).equals(entry.metadataIdentity()) ||
					!posixPermissions(directory).equals(entry.permissions()))
					throw new IOException("Stale parent directory metadata: " + entry.path());
			}
	}

	/**
	 * Resolves a manifest entry to its content object.
	 *
	 * @param entry manifest entry
	 * @return object file path
	 */
	Path objectPath(Entry entry)
	{
		return objectDirectory.resolve(entry.sha256());
	}

	/**
	 * Returns a captured preimage or an explicit absent entry for a new path.
	 *
	 * @param path project-relative path
	 * @return captured entry
	 */
	Entry entry(String path)
	{
		for (Entry entry : entries)
			if (entry.path().equals(path))
				return entry;
		return new Entry(path, false, "absent", "", false, Set.of(), 0, "", "");
	}

	/**
	 * Captures one source entry and its content object.
	 *
	 * @param root real project root
	 * @param path resolved source path
	 * @param source project-relative path
	 * @param selected all selected path names
	 * @param objectDirectory content-addressed object directory
	 * @return captured entry
	 * @throws IOException if the entry is unsafe or cannot be copied
	 */
	private static Entry captureEntry(Path root, Path path, String source, Set<String> selected,
		Path objectDirectory) throws IOException
	{
		if (Files.notExists(path, NO_FOLLOW))
			return new Entry(source, false, "absent", "", false, Set.of(), 0, "", "");
		BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, NO_FOLLOW);
		if (before.isSymbolicLink())
		{
			Path link = Files.readSymbolicLink(path);
			if (link.isAbsolute())
				throw new IOException("Selected symbolic link is absolute: " + source);
			Path resolvedLink = path.getParent().resolve(link).normalize();
				String linkSource = root.relativize(resolvedLink).toString().replace('\\', '/');
				boolean linkIsSelected = selected.contains(linkSource);
				if (!resolvedLink.startsWith(root) || !linkIsSelected)
				throw new IOException("Selected symbolic link leaves selected source: " + source);
			byte[] bytes = link.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
			String hash = digest(bytes);
			writeObject(objectDirectory, hash, bytes);
			return new Entry(source, true, "symlink", hash, false, Set.of(), before.lastModifiedTime().toMillis(),
				link.toString(), metadataIdentity(path));
		}
		if (!before.isRegularFile())
		{
			if (before.isDirectory())
				return new Entry(source, true, "directory", "", false, posixPermissions(path),
					before.lastModifiedTime().toMillis(), "", metadataIdentity(path));
			throw new IOException("Selected source is not a regular file, directory, or leaf symlink: " + source);
		}
		long linkCount = FileLinks.linkCount(path);
		if (linkCount > 1)
			throw new IOException("Selected source is hard linked: " + source);
		MessageDigest messageDigest = newDigest();
		Path temporary = Files.createTempFile(objectDirectory, "object-", ".tmp");
		String hash;
		try
		{
			try (InputStream input = Files.newInputStream(path);
				DigestInputStream digestInput = new DigestInputStream(input, messageDigest);
				OutputStream output = Files.newOutputStream(temporary))
			{
				byte[] buffer = new byte[COPY_BUFFER_SIZE];
				while (true)
				{
					int count = digestInput.read(buffer);
					if (count == -1)
						break;
					output.write(buffer, 0, count);
				}
			}
			hash = HexFormat.of().formatHex(messageDigest.digest());
			Path object = objectDirectory.resolve(hash);
			try
			{
				Files.move(temporary, object, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (AtomicMoveNotSupportedException _)
			{
				Files.move(temporary, object);
			}
			catch (java.nio.file.FileAlreadyExistsException _)
			{
				Files.delete(temporary);
			}
		}
		catch (IOException | RuntimeException e)
		{
			Files.deleteIfExists(temporary);
			throw e;
		}
		Set<PosixFilePermission> permissions = posixPermissions(path);
		boolean executable = permissions.contains(PosixFilePermission.OWNER_EXECUTE) ||
			Files.isExecutable(path);
		return new Entry(source, true, "file", hash, executable, permissions,
			before.lastModifiedTime().toMillis(), "", metadataIdentity(path));
	}

	/**
	 * Rechecks a captured source entry after the complete source set was copied.
	 *
	 * @param root real project root
	 * @param entry captured manifest entry
	 * @throws IOException if presence or content changed during capture
	 */
	private static void verifyUnchanged(Path root, Entry entry) throws IOException
	{
		Path path = root;
		if (!entry.path().equals("."))
			path = resolveSource(root, entry.path());
		boolean exists = Files.exists(path, NO_FOLLOW);
		if (exists != entry.present())
			throw new IOException("Source changed during capture: " + entry.path());
		if (!exists)
			return;
		BasicFileAttributes currentAttributes = Files.readAttributes(path, BasicFileAttributes.class, NO_FOLLOW);
		if (currentAttributes.lastModifiedTime().toMillis() != entry.lastModifiedMillis())
			throw new IOException("Source metadata changed during capture: " + entry.path());
		if (!metadataIdentity(path).equals(entry.metadataIdentity()))
			throw new IOException("Source metadata changed during capture: " + entry.path());
		if (entry.kind().equals("directory"))
			return;
		String current;
		if (entry.kind().equals("symlink"))
			current = digest(Files.readSymbolicLink(path).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
		else
		{
			Set<PosixFilePermission> currentPermissions = posixPermissions(path);
			boolean isExecutable = currentPermissions.contains(PosixFilePermission.OWNER_EXECUTE) ||
				Files.isExecutable(path);
			if (!currentPermissions.equals(entry.permissions()) || isExecutable != entry.executable())
				throw new IOException("Source metadata changed during capture: " + entry.path());
			current = digest(path);
		}
		if (!current.equals(entry.sha256()))
			throw new IOException("Source changed during capture: " + entry.path());
	}

	/**
	 * Resolves a safe project-relative source path without following an ancestor symlink.
	 *
	 * @param root real project root
	 * @param source project-relative path
	 * @return resolved path
	 * @throws IOException if the path is unsafe
	 */
	private static Path resolveSource(Path root, String source) throws IOException
	{
		Path relative = Path.of(source);
		if (relative.isAbsolute() || source.contains("\\") || source.indexOf('\0') >= 0)
			throw new IOException("Unsafe source path: " + source);
		for (Path segment : relative)
			if (segment.toString().equals(".") || segment.toString().equals("..") ||
				segment.toString().equalsIgnoreCase(".git"))
				throw new IOException("Unsafe source path: " + source);
		Path result = root.resolve(relative).normalize();
		if (!result.startsWith(root))
			throw new IOException("Source path escapes the project: " + source);
		Path current = root;
		for (Path segment : root.relativize(result))
		{
			current = current.resolve(segment);
			if (!current.equals(result) && Files.isSymbolicLink(current))
				throw new IOException("Source ancestor is a symbolic link: " + source);
		}
		return result;
	}

	/**
	 * Stores a small content object by its digest.
	 *
	 * @param objectDirectory content-addressed object directory
	 * @param hash object digest
	 * @param bytes object content
	 * @throws IOException if the object cannot be written
	 */
	private static void writeObject(Path objectDirectory, String hash, byte[] bytes) throws IOException
	{
		Path object = objectDirectory.resolve(hash);
		if (Files.exists(object))
			return;
		Files.write(object, bytes);
	}

	/**
	 * Reads POSIX permission bits where the provider supports them.
	 *
	 * @param path source file
	 * @return immutable permissions, or an empty set
	 */
	private static Set<PosixFilePermission> posixPermissions(Path path)
	{
		try
		{
			return Set.copyOf(Files.getPosixFilePermissions(path, NO_FOLLOW));
		}
		catch (IOException | UnsupportedOperationException _)
		{
			return Set.of();
		}
	}

	/**
	 * Enables the owner's executable bit on providers without recorded POSIX modes.
	 *
	 * @param path materialized file
	 * @throws IOException if executable mode cannot be set
	 */
	private static void makeExecutable(Path path) throws IOException
	{
		try
		{
			Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
			permissions.add(PosixFilePermission.OWNER_EXECUTE);
			Files.setPosixFilePermissions(path, permissions);
		}
		catch (UnsupportedOperationException e)
		{
			if (!path.toFile().setExecutable(true, true))
				throw new IOException("Cannot restore executable mode for " + path, e);
		}
	}

	/**
	 * Computes the SHA-256 of a file with bounded memory.
	 *
	 * @param path file to digest
	 * @return lowercase hexadecimal digest
	 * @throws IOException if the file cannot be read
	 */
	private static String digest(Path path) throws IOException
	{
		MessageDigest digest = newDigest();
		try (InputStream input = Files.newInputStream(path))
		{
			byte[] buffer = new byte[COPY_BUFFER_SIZE];
			while (true)
			{
				int count = input.read(buffer);
				if (count == -1)
					break;
				digest.update(buffer, 0, count);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	/**
	 * Computes the SHA-256 of a byte array.
	 *
	 * @param bytes bytes to digest
	 * @return lowercase hexadecimal digest
	 */
	private static String digest(byte[] bytes)
	{
		MessageDigest digest = newDigest();
		return HexFormat.of().formatHex(digest.digest(bytes));
	}

	/**
	 * Creates the platform-required SHA-256 digest.
	 *
	 * @return new digest instance
	 */
	private static MessageDigest newDigest()
	{
		try
		{
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new AssertionError("SHA-256 is required by the Java platform", e);
		}
	}

	/**
	 * Serializes a manifest entry as one JSON line.
	 *
	 * @param entry manifest entry
	 * @return one compact JSON object
	 */
	private static String toJsonLine(Entry entry)
	{
		String permissions = PosixFilePermissions.toString(entry.permissions());
		return "{\"path\":" + quote(entry.path()) + ",\"present\":" + entry.present() + ",\"kind\":" +
			quote(entry.kind()) + ",\"sha256\":" + quote(entry.sha256()) + ",\"executable\":" +
			entry.executable() + ",\"permissions\":" + quote(permissions) + ",\"last_modified_ms\":" +
			entry.lastModifiedMillis() + ",\"link_text\":" + quote(entry.linkText()) + ",\"metadata_id\":" +
			quote(entry.metadataIdentity()) + "}";
	}

	/**
	 * Captures the identity of stable metadata views supported by this filesystem entry.
	 *
	 * @param path entry to inspect without following symbolic links
	 * @return digest of stable supported metadata
	 * @throws IOException if a supported metadata view cannot be read
	 */
	public static String metadataIdentity(Path path) throws IOException
	{
		Map<String, String> attributes = new TreeMap<>();
		FileStore store = Files.getFileStore(path.getParent());
		boolean symbolicLink = Files.isSymbolicLink(path);
		if (store.supportsFileAttributeView("owner"))
		{
			FileOwnerAttributeView view = Files.getFileAttributeView(path, FileOwnerAttributeView.class, NO_FOLLOW);
			attributes.put("owner", view.getOwner().getName());
		}
		if (store.supportsFileAttributeView("posix"))
		{
			PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class, NO_FOLLOW);
			PosixFileAttributes value = view.readAttributes();
			attributes.put("group", value.group().getName());
			attributes.put("permissions", value.permissions().toString());
		}
		if (!symbolicLink && store.supportsFileAttributeView("acl"))
		{
			AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class, NO_FOLLOW);
			attributes.put("acl", view.getAcl().toString());
		}
		if (!symbolicLink && store.supportsFileAttributeView("dos"))
		{
			DosFileAttributeView view = Files.getFileAttributeView(path, DosFileAttributeView.class, NO_FOLLOW);
			DosFileAttributes value = view.readAttributes();
			attributes.put("dos", value.isReadOnly() + ":" + value.isHidden() + ":" + value.isArchive() + ":" +
				value.isSystem());
		}
		if (!symbolicLink && store.supportsFileAttributeView("user"))
		{
			UserDefinedFileAttributeView view = Files.getFileAttributeView(path, UserDefinedFileAttributeView.class,
				NO_FOLLOW);
			for (String name : view.list())
			{
				int size = view.size(name);
				if (size > 1_048_576)
					throw new IOException("unsupported_metadata: user attribute exceeds supported bound at " + path +
						" (" + name + ")");
				ByteBuffer buffer = ByteBuffer.allocate(size);
				view.read(name, buffer);
				attributes.put("user:" + name, HexFormat.of().formatHex(buffer.array()));
			}
		}
		return digest(attributes.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	/**
	 * Quotes a string as a JSON string value.
	 *
	 * @param value string value
	 * @return quoted value
	 */
	private static String quote(String value)
	{
		StringBuilder result = new StringBuilder("\"");
		for (int index = 0; index < value.length(); index += 1)
		{
			char current = value.charAt(index);
			if (current == '"' || current == '\\')
				result.append('\\');
			if (current < 0x20)
				result.append(String.format("\\u%04x", (int) current));
			else
				result.append(current);
		}
		return result.append('"').toString();
	}

	/**
	 * One selected path in the immutable source manifest.
	 *
	 * @param path project-relative path
	 * @param present whether the path existed at capture
	 * @param kind entry type
	 * @param sha256 content or link identity
	 * @param executable whether the semantic executable bit is set
	 * @param permissions POSIX permission bits when available
	 * @param lastModifiedMillis last-modified time captured from the source
	 * @param linkText symbolic-link destination text
	 * @param metadataIdentity stable supported metadata identity
	 */
	public record Entry(String path, boolean present, String kind, String sha256, boolean executable,
		Set<PosixFilePermission> permissions, long lastModifiedMillis, String linkText, String metadataIdentity)
	{
		/**
		 * Copies the permission collection into the immutable manifest value.
		 *
		 * @param path project-relative path
		 * @param present whether the path existed at capture
		 * @param kind entry type
		 * @param sha256 content or link identity
		 * @param executable whether the semantic executable bit is set
		 * @param permissions POSIX permission bits when available
		 * @param lastModifiedMillis last-modified time captured from the source
		 * @param linkText symbolic-link destination text
		 * @param metadataIdentity stable supported metadata identity
		 */
		public Entry
		{
			permissions = Set.copyOf(permissions);
			metadataIdentity = java.util.Objects.requireNonNull(metadataIdentity);
		}
	}
}
