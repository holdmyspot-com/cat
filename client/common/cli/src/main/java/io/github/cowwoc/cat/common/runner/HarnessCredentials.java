package io.github.cowwoc.cat.common.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Copies an explicit allow-list of harness credentials into an isolated home.
 */
public final class HarnessCredentials
{
	/**
	 * Copies regular credential files without traversing symbolic links, replacing same-named files in the isolated home.
	 *
	 * @param source the host harness configuration directory
	 *
	 * @param destination the owned isolated harness home
	 *
	 * @param names the credential filenames to copy when present
	 *
	 * @throws IOException if a present credential is not a regular non-symbolic-link file or cannot be copied
	 */
	public static void copyAllowedFiles(Path source, Path destination, Set<String> names) throws IOException
	{
		requireThat(source, "source").isNotNull();
		requireThat(destination, "destination").isNotNull();
		requireThat(names, "names").isNotNull();
		if (!Files.isDirectory(source, NOFOLLOW_LINKS) || Files.isSymbolicLink(source))
			return;
		Files.createDirectories(destination);
		for (String name : names)
		{
			requireThat(name, "name").isNotBlank();
			Path credential = source.resolve(name);
			if (!Files.exists(credential, NOFOLLOW_LINKS))
				continue;
			if (Files.isSymbolicLink(credential) || !Files.isRegularFile(credential, NOFOLLOW_LINKS))
				throw new IOException("Credential is not a regular file: " + credential);
			Files.copy(credential, destination.resolve(name), REPLACE_EXISTING, COPY_ATTRIBUTES);
			setOwnerOnlyPermissions(destination.resolve(name));
		}
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private HarnessCredentials()
	{
	}

	/**
	 * Restricts a copied credential to its owner where POSIX permissions are available.
	 *
	 * @param file the copied credential file
	 *
	 * @throws IOException if permissions cannot be set on a POSIX filesystem
	 */
	private static void setOwnerOnlyPermissions(Path file) throws IOException
	{
		if (file.getFileSystem().supportedFileAttributeViews().contains("posix"))
		{
			Files.setPosixFilePermissions(file, Set.of(OWNER_READ, OWNER_WRITE));
		}
	}
}
