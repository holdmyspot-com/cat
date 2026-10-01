package io.github.cowwoc.cat.common.optimizer.inventory;

import static io.github.cowwoc.cat.common.optimizer.inventory.Inventories.EXECUTABLE;
import static io.github.cowwoc.cat.common.optimizer.inventory.Inventories.READ_WRITE;
import static io.github.cowwoc.cat.common.optimizer.inventory.Inventories.file;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.git.Git;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.SymbolicLink;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;

import org.testng.annotations.Test;

/**
 * Verifies that the inventory scanner records a real file tree without following symbolic links.
 */
public final class InventoryScannerTest
{
	/**
	 * Creates the test.
	 */
	public InventoryScannerTest()
	{
	}

	/**
	 * Verifies that a scan records regular files with their content hashes and modes, ignored files, symbolic links with
	 * their stored destinations, and recorded external files; that it does not descend through a directory link; and
	 * that it omits the Git metadata directory and CAT's work directory.
	 *
	 * @throws IOException if the fixture cannot be created or scanned
	 */
	@Test
	public void recordsTreeWithoutFollowingLinks() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-inventory-project-"));
			Path external = cleanup.add(Files.createTempDirectory("cat-inventory-external-"));
			Path shared = writeFile(external.resolve("shared.md"), "shared", READ_WRITE);
			Git git = new Git(project);
			git.run("init", "--quiet");
			writeFile(project.resolve(".gitignore"), "ignored.md\n", READ_WRITE);
			writeFile(project.resolve("ignored.md"), "ignored", READ_WRITE);
			// git check-ignore fails unless Git ignores the file, which makes this fixture's ignored file real
			git.run("check-ignore", "--quiet", "ignored.md");
			writeFile(project.resolve("prompt.md"), "prompt", EXECUTABLE);
			writeFile(project.resolve("sub/inner.md"), "inner", READ_WRITE);
			writeFile(project.resolve(".cat/work/state.json"), "{}", READ_WRITE);
			Files.createSymbolicLink(project.resolve("alias.md"), Path.of("prompt.md"));
			Files.createSymbolicLink(project.resolve("docs"), Path.of("sub"));
			Files.createSymbolicLink(project.resolve("outside.md"), shared);

			FileInventory inventory = InventoryScanner.scan(project, Set.of(shared));

			requireThat(inventory.root(), "root").isEqualTo(project.toAbsolutePath().normalize());
			requireThat(inventory.entries(), "entries").isEqualTo(Map.of(
				Path.of(".gitignore"), file("ignored.md\n"),
				Path.of("ignored.md"), file("ignored"),
				Path.of("prompt.md"), file("prompt", EXECUTABLE),
				Path.of("sub/inner.md"), file("inner"),
				Path.of("alias.md"), new SymbolicLink(Path.of("prompt.md")),
				Path.of("docs"), new SymbolicLink(Path.of("sub")),
				Path.of("outside.md"), new SymbolicLink(shared),
				shared, file("shared")));
		}
	}

	/**
	 * Verifies that a recorded external path that no longer exists is absent from the inventory.
	 *
	 * @throws IOException if the fixture cannot be created or scanned
	 */
	@Test
	public void omitsMissingExternalPath() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-inventory-project-"));
			Path external = cleanup.add(Files.createTempDirectory("cat-inventory-external-"));

			FileInventory inventory = InventoryScanner.scan(project, Set.of(external.resolve("missing.md")));

			requireThat(inventory.entries(), "entries").isEmpty();
		}
	}

	/**
	 * Verifies that the scanner rejects an external path inside the project, because the project scan already
	 * records it.
	 *
	 * @throws IOException if the fixture cannot be created or scanned
	 */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*inside\\.md.*")
	public void rejectsExternalPathInsideProject() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-inventory-project-"));

			InventoryScanner.scan(project, Set.of(project.resolve("inside.md")));
		}
	}

	/**
	 * Writes a UTF-8 file, creating its parent directories, and sets its permissions.
	 *
	 * @param path the file to write
	 * @param text the file's text
	 * @param permissions the file's permissions
	 * @return {@code path}
	 * @throws IOException if the file cannot be written
	 */
	private static Path writeFile(Path path, String text, Set<PosixFilePermission> permissions) throws IOException
	{
		Files.createDirectories(path.getParent());
		Files.writeString(path, text, UTF_8);
		Files.setPosixFilePermissions(path, permissions);
		return path;
	}
}
