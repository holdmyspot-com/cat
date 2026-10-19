package com.holdmyspot.cat.common.optimizer.evidence.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.evidence.FileChanges;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import org.testng.annotations.Test;

/**
 * Verifies that every trial file change is compared with its staged source.
 */
public final class FileChangesTest
{
	/**
	 * Creates the tests.
	 */
	public FileChangesTest()
	{
	}

	/**
	 * Verifies that created, modified and deleted files are listed with unified text diffs.
	 *
	 * @throws IOException if the fixture cannot be written or compared
	 */
	@Test
	public void listsCreatedModifiedDeleted() throws IOException
	{
		// Requirement: R-E21
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path source = cleanup.add(Files.createTempDirectory("cat-evidence-source-"));
			Path effects = cleanup.add(Files.createTempDirectory("cat-evidence-effects-"));
			write(source.resolve("modified.txt"), "shared\nbefore\n");
			write(source.resolve("deleted.txt"), "deleted\n");
			write(source.resolve("unchanged.txt"), "same\n");
			write(effects.resolve("modified.txt"), "shared\nafter\n");
			write(effects.resolve("created.txt"), "created\n");
			write(effects.resolve("unchanged.txt"), "same\n");

			String rendered = FileChanges.render(source, effects);

			requireThat(rendered, "rendered").contains("Created: created.txt").contains("Modified: modified.txt").
				contains("Deleted: deleted.txt").contains("--- /dev/null\n+++ b/created.txt\n").
				contains("--- a/deleted.txt\n+++ /dev/null\n").contains("-before\n+after\n").
				doesNotContain("unchanged.txt");
		}
	}

	/**
	 * Verifies that binary and oversized changes name their exact effects paths without embedding their contents.
	 *
	 * @throws IOException if the fixture cannot be written or compared
	 */
	@Test
	public void namesBinaryAndOversizedFiles() throws IOException
	{
		// Requirement: R-E21
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path source = cleanup.add(Files.createTempDirectory("cat-evidence-source-"));
			Path effects = cleanup.add(Files.createTempDirectory("cat-evidence-effects-"));
			Files.write(effects.resolve("image.bin"), new byte[] {0, 1, 2});
			write(effects.resolve("large.txt"), "oversized content\n".repeat(15_000));

			String rendered = FileChanges.render(source, effects);

			requireThat(rendered, "rendered").contains("Created: image.bin").contains("binary").
				contains(effects.resolve("image.bin").toString()).contains("Created: large.txt").contains("oversized").
				contains(effects.resolve("large.txt").toString()).doesNotContain("oversized content");
		}
	}

	/**
	 * Verifies that missing final newlines are represented in a unified diff.
	 *
	 * @throws IOException if the fixture cannot be written or compared
	 */
	@Test
	public void marksMissingFinalNewline() throws IOException
	{
		// Requirement: R-E21
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path source = cleanup.add(Files.createTempDirectory("cat-evidence-source-"));
			Path effects = cleanup.add(Files.createTempDirectory("cat-evidence-effects-"));
			write(source.resolve("note.txt"), "before");
			write(effects.resolve("note.txt"), "after");

			requireThat(FileChanges.render(source, effects), "rendered").
				contains("-before\n\\ No newline at end of file\n+after\n\\ No newline at end of file\n");
		}
	}

	/**
	 * Verifies that Git metadata and CAT's session directories follow the staged-source exclusions.
	 *
	 * @throws IOException if the fixture cannot be written or compared
	 */
	@Test
	public void excludesOptimizerState() throws IOException
	{
		// Requirement: R-E21
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path source = cleanup.add(Files.createTempDirectory("cat-evidence-source-"));
			Path effects = cleanup.add(Files.createTempDirectory("cat-evidence-effects-"));
			write(effects.resolve(".cat/sessions/state.json"), "{}");
			write(effects.resolve(".git/config"), "metadata");

			requireThat(FileChanges.render(source, effects), "rendered").isEqualTo("No file changes.\n");
		}
	}

	/**
	 * Verifies that mode-only changes and symbolic-link changes are listed without following links.
	 *
	 * @throws IOException if the fixture cannot be written or compared
	 */
	@Test
	public void listsModesAndSymbolicLinks() throws IOException
	{
		// Requirement: R-E21
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path source = cleanup.add(Files.createTempDirectory("cat-evidence-source-"));
			Path effects = cleanup.add(Files.createTempDirectory("cat-evidence-effects-"));
			write(source.resolve("run.sh"), "echo ready\n");
			write(effects.resolve("run.sh"), "echo ready\n");
			// Only a file system with POSIX permissions can change a file's mode without changing its name
			boolean hasModes = PortableFiles.hasModes(effects);
			if (hasModes)
				Files.setPosixFilePermissions(effects.resolve("run.sh"), Set.of(PosixFilePermission.OWNER_READ));
			Files.createSymbolicLink(effects.resolve("link.txt"), Path.of("missing.txt"));

			String rendered = FileChanges.render(source, effects);

			requireThat(rendered, "rendered").contains("Created: link.txt").contains("+missing.txt");
			if (hasModes)
				requireThat(rendered, "rendered").contains("Modified: run.sh").contains("Permissions:");
			else
				requireThat(rendered, "rendered").doesNotContain("run.sh");
		}
	}

	/**
	 * Writes one UTF-8 fixture file with its parent directories.
	 *
	 * @param file the destination
	 * @param text the file contents
	 * @throws IOException if the fixture cannot be written
	 */
	private static void write(Path file, String text) throws IOException
	{
		Files.createDirectories(file.getParent());
		Files.writeString(file, text, UTF_8);
	}
}
