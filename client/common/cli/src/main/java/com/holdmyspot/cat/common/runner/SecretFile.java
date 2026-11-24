package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Decides whether a file that holds a secret keeps it from every operating-system user but its owner, and gives a new
 * file or folder for a secret that access as it is created. Such a file must be a regular file that is not a symbolic
 * link, and deny group and other access: on Linux and macOS its mode grants group and other nothing, and on Windows its
 * access list grants access to no one but its owner, {@code SYSTEM} and the Administrators group. The decision uses
 * only the file's metadata; it never opens the file.
 */
public final class SecretFile
{
	/**
	 * The permissions that a file that holds a secret must not grant, each with the words that name it in a refusal.
	 */
	private static final Map<PosixFilePermission, String> SHARED_PERMISSIONS = Map.of(
		PosixFilePermission.GROUP_READ, "group read", PosixFilePermission.GROUP_WRITE, "group write",
		PosixFilePermission.GROUP_EXECUTE, "group execute", PosixFilePermission.OTHERS_READ, "other read",
		PosixFilePermission.OTHERS_WRITE, "other write", PosixFilePermission.OTHERS_EXECUTE, "other execute");
	/**
	 * The order in which a refusal names the permissions that a file must not grant.
	 */
	private static final List<PosixFilePermission> SHARED_ORDER = List.of(PosixFilePermission.GROUP_READ,
		PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
		PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);
	/**
	 * The names, in lower case and without their domain, of the Windows accounts besides a file's owner that a file
	 * that holds a secret may grant access to: the operating system itself and the local administrators.
	 */
	private static final Set<String> WINDOWS_TRUSTED = Set.of("system", "administrators");
	/**
	 * The mode of a new file that holds a secret on a file system with POSIX permissions: its owner alone may read and
	 * write it.
	 */
	private static final Set<PosixFilePermission> OWNER_FILE_MODE = PosixFilePermissions.fromString("rw-------");
	/**
	 * The mode of a new folder that holds secrets on a file system with POSIX permissions: its owner alone may list,
	 * enter and change it.
	 */
	private static final Set<PosixFilePermission> OWNER_FOLDER_MODE = PosixFilePermissions.fromString("rwx------");

	/**
	 * The metadata of a file that the decision uses.
	 *
	 * @param symbolicLink {@code true} if the path is a symbolic link
	 * @param regularFile  {@code true} if the path is a regular file, without following a link
	 * @param permissions  the file's POSIX permissions, or {@code null} on a file system without them
	 * @param owner        the name of the file's owner, or {@code null} on a file system without access lists
	 * @param grantees     the names of the users and groups to which the file's access list allows any access, or
	 *                     {@code null} on a file system without access lists
	 */
	public record Attributes(boolean symbolicLink, boolean regularFile, Set<PosixFilePermission> permissions,
		String owner, List<String> grantees)
	{
	}

	/**
	 * Prevents construction.
	 */
	private SecretFile()
	{
	}

	/**
	 * Reads the metadata of a file, without opening the file or following a link.
	 *
	 * @param file the file
	 * @return the metadata
	 * @throws NullPointerException if {@code file} is null
	 * @throws IOException          if the metadata cannot be read
	 */
	public static Attributes read(Path file) throws IOException
	{
		requireThat(file, "file").isNotNull();
		BasicFileAttributes basic = Files.readAttributes(file, BasicFileAttributes.class, NOFOLLOW_LINKS);
		if (basic.isSymbolicLink() || !basic.isRegularFile())
			return new Attributes(basic.isSymbolicLink(), basic.isRegularFile(), null, null, null);
		Set<String> views = file.getFileSystem().supportedFileAttributeViews();
		if (views.contains("posix"))
		{
			PosixFileAttributes posix = Files.readAttributes(file, PosixFileAttributes.class, NOFOLLOW_LINKS);
			return new Attributes(false, true, posix.permissions(), null, null);
		}
		if (views.contains("acl"))
		{
			AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class, NOFOLLOW_LINKS);
			Set<String> grantees = new LinkedHashSet<>();
			for (AclEntry entry : acl.getAcl())
			{
				if (entry.type() == AclEntryType.ALLOW)
					grantees.add(entry.principal().getName());
			}
			return new Attributes(false, true, null, acl.getOwner().getName(), List.copyOf(grantees));
		}
		return new Attributes(false, true, null, null, null);
	}

	/**
	 * Returns what keeps a file from holding a secret safely.
	 *
	 * @param file       the file, which the reason names
	 * @param attributes the file's metadata
	 * @return the reason, which names the file and what to change, or empty if the file holds a secret safely
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static Optional<String> problem(Path file, Attributes attributes)
	{
		requireThat(file, "file").isNotNull();
		requireThat(attributes, "attributes").isNotNull();
		if (attributes.symbolicLink())
		{
			return Optional.of(file + " is a symbolic link, whose target anyone who may write its folder can change. " +
				"Replace the link with the file that it leads to, readable by its owner alone.");
		}
		if (!attributes.regularFile())
			return Optional.of(file + " is not a regular file. Name a regular file that its owner alone may read.");
		if (attributes.permissions() != null)
			return posixProblem(file, attributes.permissions());
		if (attributes.grantees() != null)
			return aclProblem(file, attributes.owner(), attributes.grantees());
		return Optional.of("The file system of " + file + " reports neither POSIX permissions nor an access list, so " +
			"CAT cannot tell who may read it. Move it to a file system that reports them.");
	}

	/**
	 * Returns the access that a new file that holds a secret gets as it is created, so that it is never readable by
	 * another user: on a file system with POSIX permissions, mode {@code 0600}; on one with access lists, an access list
	 * that grants the owner of the folder that holds it, alone.
	 *
	 * @param folder the existing folder that will hold the file
	 * @return the attribute to pass to the call that creates the file
	 * @throws NullPointerException if {@code folder} is null
	 * @throws IOException          if the file system reports neither POSIX permissions nor access lists, or the
	 *                              folder's owner cannot be read
	 */
	public static FileAttribute<?> ownerOnlyFile(Path folder) throws IOException
	{
		return ownerOnly(folder, OWNER_FILE_MODE, Set.of());
	}

	/**
	 * Returns the access that a new folder that holds secrets gets as it is created, so that no other user may list or
	 * enter it: on a file system with POSIX permissions, mode {@code 0700}; on one with access lists, an access list that
	 * grants the owner of its parent folder, alone, and that the folder's new files and folders inherit.
	 *
	 * @param parent the existing folder that will hold the new folder
	 * @return the attribute to pass to the call that creates the folder
	 * @throws NullPointerException if {@code parent} is null
	 * @throws IOException          if the file system reports neither POSIX permissions nor access lists, or the
	 *                              parent's owner cannot be read
	 */
	public static FileAttribute<?> ownerOnlyFolder(Path parent) throws IOException
	{
		return ownerOnly(parent, OWNER_FOLDER_MODE, EnumSet.of(AclEntryFlag.FILE_INHERIT,
			AclEntryFlag.DIRECTORY_INHERIT));
	}

	/**
	 * Returns the access that a new file or folder that holds a secret gets as it is created.
	 *
	 * @param parent   the existing folder that will hold it, whose owner the access list names
	 * @param mode     the mode on a file system with POSIX permissions
	 * @param aclFlags the inheritance of the access list's entry on a file system with access lists
	 * @return the attribute
	 * @throws IOException if the file system reports neither, or the parent's owner cannot be read
	 */
	private static FileAttribute<?> ownerOnly(Path parent, Set<PosixFilePermission> mode, Set<AclEntryFlag> aclFlags)
		throws IOException
	{
		requireThat(parent, "parent").isNotNull();
		Set<String> views = parent.getFileSystem().supportedFileAttributeViews();
		if (views.contains("posix"))
			return PosixFilePermissions.asFileAttribute(mode);
		if (views.contains("acl"))
		{
			AclEntry owner = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(parent)).
				setPermissions(EnumSet.allOf(AclEntryPermission.class)).setFlags(aclFlags).build();
			return new FileAttribute<List<AclEntry>>()
			{
				@Override
				public String name()
				{
					return "acl:acl";
				}

				@Override
				public List<AclEntry> value()
				{
					return List.of(owner);
				}
			};
		}
		throw new IOException("The file system of " + parent + " reports neither POSIX permissions nor an access " +
			"list, so CAT cannot create a file there that its owner alone may read.");
	}

	/**
	 * Returns what keeps a file with POSIX permissions from holding a secret safely.
	 *
	 * @param file        the file
	 * @param permissions the file's permissions
	 * @return the reason, or empty if the file grants group and other nothing
	 */
	private static Optional<String> posixProblem(Path file, Set<PosixFilePermission> permissions)
	{
		StringJoiner granted = new StringJoiner(", ");
		for (PosixFilePermission permission : SHARED_ORDER)
		{
			if (permissions.contains(permission))
				granted.add(SHARED_PERMISSIONS.get(permission));
		}
		if (granted.length() == 0)
			return Optional.empty();
		return Optional.of(file + " holds a secret, but its mode grants " + granted + ". Clear those permissions, " +
			"for example with chmod go= " + file + ", so that its owner alone may use it.");
	}

	/**
	 * Returns what keeps a file with an access list from holding a secret safely.
	 *
	 * @param file     the file
	 * @param owner    the name of the file's owner
	 * @param grantees the names of the users and groups to which the access list allows access
	 * @return the reason, or empty if the list grants access to no one but the owner, {@code SYSTEM} and
	 *   Administrators
	 */
	private static Optional<String> aclProblem(Path file, String owner, List<String> grantees)
	{
		List<String> others = new ArrayList<>();
		for (String grantee : grantees)
		{
			String account = grantee.substring(grantee.lastIndexOf('\\') + 1).toLowerCase(Locale.ROOT);
			boolean trusted = grantee.equalsIgnoreCase(owner) || WINDOWS_TRUSTED.contains(account);
			if (!trusted)
				others.add(grantee);
		}
		if (others.isEmpty())
			return Optional.empty();
		return Optional.of(file + " holds a secret, but its access list also grants access to " +
			String.join(", ", others) + ". Remove those entries so that only its owner, SYSTEM and Administrators may " +
			"use it.");
	}
}
