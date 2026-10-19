package com.holdmyspot.cat.common.optimizer.inventory;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * The recorded state of one path in a {@link FileInventory}: its kind and, depending on the kind, its content hash and
 * mode or its link destination.
 * <p>
 * Directories have no entry; an inventory records the files and links inside them.
 */
public sealed interface FileEntry extends PathState
{
	/**
	 * A regular file.
	 *
	 * @param contentHash the digest of the file's bytes
	 * @param permissions the file's mode
	 */
	record RegularFile(Sha256 contentHash, Set<PosixFilePermission> permissions) implements FileEntry
	{
		/**
		 * Creates a regular-file entry.
		 *
		 * @param contentHash the digest of the file's bytes
		 * @param permissions the file's mode
		 * @throws NullPointerException if {@code contentHash} or {@code permissions} are null
		 */
		public RegularFile
		{
			requireThat(contentHash, "contentHash").isNotNull();
			permissions = Set.copyOf(permissions);
		}
	}

	/**
	 * A symbolic link, recorded without following it.
	 *
	 * @param target the destination stored in the link, which may be relative to the link's directory
	 */
	record SymbolicLink(Path target) implements FileEntry
	{
		/**
		 * Creates a symbolic-link entry.
		 *
		 * @param target the destination stored in the link, which may be relative to the link's directory
		 * @throws NullPointerException if {@code target} is null
		 */
		public SymbolicLink
		{
			requireThat(target, "target").isNotNull();
		}
	}

	/**
	 * A file that is neither a regular file, a directory nor a symbolic link, such as a named pipe or a device.
	 */
	record SpecialFile() implements FileEntry
	{
	}
}
