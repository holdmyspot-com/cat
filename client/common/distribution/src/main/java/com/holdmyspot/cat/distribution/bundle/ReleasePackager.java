package com.holdmyspot.cat.distribution.bundle;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.Platform;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipParameters;

/**
 * Packages a harness's assembled CAT plugin directory into a reproducible archive and writes its checksum manifest.
 */
public final class ReleasePackager
{
	private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = permissions("rwx", "r-x", "r-x");
	private static final Set<PosixFilePermission> SYMBOLIC_LINK_PERMISSIONS = permissions("rwx", "rwx", "rwx");
	private static final Set<PosixFilePermission> EXECUTABLE_FILE_PERMISSIONS = permissions("rwx", "r-x", "r-x");
	private static final Set<PosixFilePermission> FILE_PERMISSIONS = permissions("rw-", "r--", "r--");

	private final String version;

	/**
	 * Creates a release packager.
	 *
	 * @param version the CAT version
	 * @throws NullPointerException if {@code version} is null
	 * @throws IllegalArgumentException if {@code version} is blank
	 */
	public ReleasePackager(String version)
	{
		requireThat(version, "version").isNotBlank();
		this.version = version;
	}

	/**
	 * Packages a plugin artifact as a deterministic archive, replacing an existing archive at its generated path.
	 *
	 * @param harness supported harness identifier used in the generated archive filename
	 * @param platform distribution platform whose identifier is included in the generated archive filename
	 * @param pluginRoot assembled plugin directory whose complete contents are archived
	 * @param releaseDirectory directory that receives the generated archive, creating it when needed
	 * @return the created archive file
	 * @throws NullPointerException if {@code harness}, {@code platform}, {@code pluginRoot}, or {@code releaseDirectory}
	 *         are null
	 * @throws IllegalArgumentException if {@code harness} is blank or unsupported
	 * @throws IOException if the archive cannot be created
	 */
	public Path packageArtifact(String harness, Platform platform, Path pluginRoot, Path releaseDirectory)
		throws IOException
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(platform, "platform").isNotNull();
		requireThat(pluginRoot, "pluginRoot").isNotNull();
		requireThat(releaseDirectory, "releaseDirectory").isNotNull();
		if (!harness.equals("claude") && !harness.equals("codex"))
			throw new IllegalArgumentException("Unsupported harness: " + harness);
		if (!Files.isDirectory(pluginRoot, NOFOLLOW_LINKS))
			throw new IOException("Plugin root is not a directory: " + pluginRoot);

		Files.createDirectories(releaseDirectory);
		String baseName = "cat-" + harness + "-v" + version + "-" + platform.id();
		Path archive = releaseDirectory.resolve(baseName + ".tar.gz");
		GzipParameters gzipParameters = new GzipParameters();
		gzipParameters.setModificationInstant(Instant.EPOCH);
		try (OutputStream file = Files.newOutputStream(archive);
			GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(file, gzipParameters);
			TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip))
		{
			tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
			tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
			try (Stream<Path> stream = Files.walk(pluginRoot))
			{
				for (Path path : stream.sorted(Comparator.comparing(candidate -> pluginRoot.relativize(candidate).toString())).
					toList())
				{
					Path relative = pluginRoot.relativize(path);
					String relativeName = "/";
					if (!relative.toString().isEmpty())
						relativeName = "/" + relative.toString().replace(path.getFileSystem().getSeparator(), "/");
					String entryName = baseName + relativeName;
					if (Files.isDirectory(path, NOFOLLOW_LINKS) && !entryName.endsWith("/"))
						entryName = entryName.concat("/");
					TarArchiveEntry entry = createEntry(path, entryName);
					normalizeMetadata(entry, path);
					tar.putArchiveEntry(entry);
					if (Files.isRegularFile(path, NOFOLLOW_LINKS))
						Files.copy(path, tar);
					tar.closeArchiveEntry();
				}
			}
		}
		return archive;
	}

	/**
	 * Replaces a checksum manifest with checksums for release archives.
	 *
	 * @param archives generated release archives whose SHA-256 checksums are recorded
	 * @param manifest SHA256SUMS file replaced with checksums for {@code archives}, sorted by archive filename
	 * @return the manifest path
	 * @throws NullPointerException if {@code archives} or {@code manifest} are null
	 * @throws IOException if the manifest cannot be written
	 */
	public Path writeChecksumManifest(Collection<Path> archives, Path manifest) throws IOException
	{
		requireThat(archives, "archives").isNotNull();
		requireThat(manifest, "manifest").isNotNull();

		List<Path> sorted = new ArrayList<>(archives);
		sorted.sort(Comparator.comparing(path -> path.getFileName().toString()));
		List<String> lines = new ArrayList<>(sorted.size());
		for (Path archive : sorted)
			lines.add(sha256(archive) + "  " + archive.getFileName());
		Files.createDirectories(manifest.toAbsolutePath().normalize().getParent());
		Files.write(manifest, lines);
		return manifest;
	}

	/**
	 * Normalizes archive-entry metadata for reproducible output.
	 *
	 * @param entry TAR entry whose timestamp, owner, and permissions are normalized
	 * @param source filesystem path whose executable status determines the entry's file permissions
	 */
	private static void normalizeMetadata(TarArchiveEntry entry, Path source)
	{
		boolean executable = Files.isExecutable(source);
		entry.setModTime(0);
		entry.setUserId(0);
		entry.setGroupId(0);
		entry.setUserName("");
		entry.setGroupName("");
		if (entry.isDirectory())
			entry.setMode(mode(DIRECTORY_PERMISSIONS));
		else if (entry.isSymbolicLink())
			entry.setMode(mode(SYMBOLIC_LINK_PERMISSIONS));
		else
		{
			Set<PosixFilePermission> permissions = FILE_PERMISSIONS;
			if (executable)
				permissions = EXECUTABLE_FILE_PERMISSIONS;
			entry.setMode(mode(permissions));
		}
	}

	/**
	 * Creates an immutable POSIX permission set from readable owner, group, and other permissions.
	 *
	 * @param owner the owner's {@code rwx} permissions
	 * @param group the group's {@code rwx} permissions
	 * @param others all other users' {@code rwx} permissions
	 * @return the parsed POSIX permissions
	 */
	private static Set<PosixFilePermission> permissions(String owner, String group, String others)
	{
		return Set.copyOf(PosixFilePermissions.fromString(owner + group + others));
	}

	/**
	 * Converts POSIX permissions into the mode bits required by a TAR archive entry.
	 *
	 * @param permissions the POSIX permissions to convert
	 * @return the corresponding POSIX mode bits
	 */
	private static int mode(Set<PosixFilePermission> permissions)
	{
		return permissions.stream().mapToInt(ReleasePackager::permissionBit).
			reduce(0, (left, right) -> left | right);
	}

	/**
	 * Returns the archive-mode bit represented by one POSIX permission.
	 *
	 * @param permission the POSIX permission
	 * @return the matching archive-mode bit
	 */
	private static int permissionBit(PosixFilePermission permission)
	{
		return switch (permission)
		{
			case OWNER_READ -> 0400;
			case OWNER_WRITE -> 0200;
			case OWNER_EXECUTE -> 0100;
			case GROUP_READ -> 0040;
			case GROUP_WRITE -> 0020;
			case GROUP_EXECUTE -> 0010;
			case OTHERS_READ -> 0004;
			case OTHERS_WRITE -> 0002;
			case OTHERS_EXECUTE -> 0001;
		};
	}

	/**
	 * Creates an archive entry for a filesystem path.
	 *
	 * @param path the filesystem path
	 * @param entryName the archive-entry name
	 * @return the archive entry
	 * @throws IOException if symbolic-link or file metadata cannot be read
	 */
	private static TarArchiveEntry createEntry(Path path, String entryName) throws IOException
	{
		TarArchiveEntry entry;
		if (Files.isDirectory(path, NOFOLLOW_LINKS))
			entry = new TarArchiveEntry(entryName, TarArchiveEntry.LF_DIR);
		else if (Files.isSymbolicLink(path))
		{
			entry = new TarArchiveEntry(entryName, TarArchiveEntry.LF_SYMLINK);
			entry.setLinkName(Files.readSymbolicLink(path).toString());
		}
		else
		{
			entry = new TarArchiveEntry(entryName);
			entry.setSize(Files.size(path));
		}
		return entry;
	}

	/**
	 * Calculates the SHA-256 digest of an archive.
	 *
	 * @param archive generated release archive whose SHA-256 digest is calculated
	 * @return the lowercase hexadecimal digest
	 * @throws IOException if the archive cannot be read
	 */
	private static String sha256(Path archive) throws IOException
	{
		MessageDigest digest;
		try
		{
			digest = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new AssertionError("Every Java implementation must provide SHA-256", e);
		}
		try (InputStream input = Files.newInputStream(archive))
		{
			byte[] buffer = new byte[64 * 1024];
			int read = input.read(buffer);
			while (read != -1)
			{
				digest.update(buffer, 0, read);
				read = input.read(buffer);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}
}
