package com.holdmyspot.cat.common.optimizer.workspace.test;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry.RegularFile;
import com.holdmyspot.cat.common.optimizer.workspace.ForkTree;
import com.holdmyspot.cat.common.test.ProgramFixture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the project trees that the workspace tests record, stage and scan.
 */
final class ProjectFixtures
{
	/**
	 * The permissions of an ordinary non-executable file.
	 */
	static final Set<PosixFilePermission> READ_WRITE = PosixFilePermissions.fromString("rw-r--r--");
	/**
	 * The permissions of an executable file.
	 */
	static final Set<PosixFilePermission> EXECUTABLE = PosixFilePermissions.fromString("rwxr-xr-x");

	/**
	 * Prevents instantiation.
	 */
	private ProjectFixtures()
	{
	}

	/**
	 * A project with links, the external files that some links reach, and the fork tree recorded from them.
	 *
	 * @param base the project directory
	 * @param external the directory that holds the external files
	 * @param campaign the campaign storage directory
	 * @param tree the recorded fork tree
	 */
	record LinkedProject(Path base, Path external, Path campaign, ForkTree tree)
	{
	}

	/**
	 * Writes a UTF-8 file, creating its parent directories, and sets its permissions.
	 *
	 * @param file the file to write
	 * @param text the file's text
	 * @param permissions the file's permissions
	 * @return {@code file}
	 * @throws IOException if the file cannot be written
	 */
	static Path writeFile(Path file, String text, Set<PosixFilePermission> permissions) throws IOException
	{
		Files.createDirectories(file.getParent());
		Files.writeString(file, text, UTF_8);
		PortableFiles.setMode(file, permissions);
		return file;
	}

	/**
	 * Returns a non-executable regular-file entry holding UTF-8 text.
	 *
	 * @param text the file's text
	 * @return the entry
	 */
	static RegularFile file(String text)
	{
		return file(text, READ_WRITE);
	}

	/**
	 * Returns a regular-file entry holding UTF-8 text.
	 *
	 * @param text the file's text
	 * @param permissions the file's permissions
	 * @return the entry
	 */
	static RegularFile file(String text, Set<PosixFilePermission> permissions)
	{
		return new RegularFile(content(text).hash(), permissions);
	}

	/**
	 * Returns file content holding UTF-8 text.
	 *
	 * @param text the file's text
	 * @return the content
	 */
	static FileContent content(String text)
	{
		return new FileContent(text.getBytes(UTF_8));
	}


	/**
	 * Creates absolute links in a project.
	 *
	 * @param base the project directory
	 * @param links each link's path relative to {@code base}, and its absolute destination
	 * @return the links' paths relative to {@code base}
	 * @throws IOException if a link cannot be created
	 */
	static Set<Path> createLinks(Path base, Map<String, Path> links) throws IOException
	{
		Set<Path> paths = new HashSet<>();
		for (Map.Entry<String, Path> link : links.entrySet())
		{
			Path location = base.resolve(link.getKey());
			Files.createDirectories(location.getParent());
			Files.createSymbolicLink(location, link.getValue());
			paths.add(Path.of(link.getKey()));
		}
		return paths;
	}

	/**
	 * Takes away the permission to list a directory while leaving its entries reachable by name. A file system without
	 * POSIX permissions gets an access-control entry that denies the current user the listing instead.
	 *
	 * @param directory a directory
	 * @throws IOException if the directory's permissions cannot be changed
	 */
	static void denyListing(Path directory) throws IOException
	{
		if (PortableFiles.hasModes(directory))
		{
			Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("--x--x--x"));
			return;
		}
		AclFileAttributeView view = Files.getFileAttributeView(directory, AclFileAttributeView.class);
		List<AclEntry> entries = new ArrayList<>(view.getAcl());
		entries.addFirst(listingDenial(directory));
		view.setAcl(entries);
	}

	/**
	 * Gives back the permission that {@link #denyListing(Path)} took away, so that the directory can be deleted.
	 *
	 * @param directory a directory
	 * @throws IOException if the directory's permissions cannot be changed
	 */
	static void allowListing(Path directory) throws IOException
	{
		if (PortableFiles.hasModes(directory))
		{
			Files.setPosixFilePermissions(directory, EXECUTABLE);
			return;
		}
		AclFileAttributeView view = Files.getFileAttributeView(directory, AclFileAttributeView.class);
		List<AclEntry> entries = new ArrayList<>(view.getAcl());
		entries.remove(listingDenial(directory));
		view.setAcl(entries);
	}

	/**
	 * Returns the access-control entry that denies the current user the listing of a directory.
	 *
	 * @param directory a directory
	 * @return the entry
	 * @throws IOException if the current user cannot be looked up
	 */
	private static AclEntry listingDenial(Path directory) throws IOException
	{
		UserPrincipal user = directory.getFileSystem().getUserPrincipalLookupService().
			lookupPrincipalByName(System.getProperty("user.name"));
		return AclEntry.newBuilder().
			setType(AclEntryType.DENY).
			setPrincipal(user).
			setPermissions(AclEntryPermission.LIST_DIRECTORY).
			build();
	}
	/**
	 * Creates a project that holds ordinary files, a fixture directory, files and a directory that links reach, an
	 * in-project file link, an in-project directory link, an external file link and an external directory link, plus
	 * a CAT session directory, and records all of it as a fork tree.
	 *
	 * @param root the directory that owns the fixture
	 * @return the project and its fork tree
	 * @throws IOException if the fixture cannot be created
	 */
	static LinkedProject linkedProject(Path root) throws IOException
	{
		Path base = Files.createDirectories(root.resolve("project"));
		Path external = Files.createDirectories(root.resolve("external"));
		Path campaign = root.resolve("campaign");
		writeFile(base.resolve("prompt.md"), "prompt", READ_WRITE);
		// Windows records a file as executable only by its name, and Linux only by the mode set here
		writeFile(base.resolve(ProgramFixture.name("run")), ProgramFixture.TEXT, EXECUTABLE);
		writeFile(base.resolve("rules/a.md"), "rule a", READ_WRITE);
		writeFile(base.resolve("rules/b.md"), "rule b", READ_WRITE);
		writeFile(base.resolve("fixtures/data.txt"), "fixture data", READ_WRITE);
		writeFile(base.resolve(".cat/rules/r.md"), "cat rule", READ_WRITE);
		writeFile(base.resolve(".cat/sessions/state.json"), "{}", READ_WRITE);
		writeFile(external.resolve("shared.md"), "shared", READ_WRITE);
		writeFile(external.resolve("dir/x.md"), "external x", READ_WRITE);
		writeFile(external.resolve("dir/y.md"), "external y", READ_WRITE);
		Files.createSymbolicLink(base.resolve("alias.md"), Path.of("prompt.md"));
		Files.createSymbolicLink(base.resolve("docs"), Path.of("rules"));
		Files.createSymbolicLink(base.resolve("shared.md"), external.resolve("shared.md"));
		Files.createSymbolicLink(base.resolve("ext-dir"), external.resolve("dir"));

		Set<Path> paths = new HashSet<>();
		for (String relative : new String[]
			{
				"prompt.md", ProgramFixture.name("run"), "rules", "fixtures", ".cat", "alias.md", "docs", "shared.md", "ext-dir"
			})
		{
			paths.add(Path.of(relative));
		}
		paths.add(external.resolve("shared.md"));
		paths.add(external.resolve("dir"));
		ForkTree tree = ForkTree.record(base, campaign, paths);
		return new LinkedProject(base, external, campaign, tree);
	}
}
