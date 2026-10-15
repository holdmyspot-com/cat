package io.github.cowwoc.cat.common.optimizer.workspace.test;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.PortableFiles;
import io.github.cowwoc.cat.common.optimizer.inventory.FileContent;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.workspace.ForkTree;
import io.github.cowwoc.cat.common.test.ProgramFixture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
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
	 * Creates a project that holds ordinary files, a fixture directory, files and a directory that links reach, an
	 * in-project file link, an in-project directory link, an external file link and an external directory link, plus
	 * a CAT work directory, and records all of it as a fork tree.
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
		writeFile(base.resolve(".cat/work/state.json"), "{}", READ_WRITE);
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
