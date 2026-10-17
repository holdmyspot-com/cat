package io.github.cowwoc.cat.common.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.SessionWorkDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.testng.annotations.Test;

/**
 * Verifies that {@link SessionWorkDirectory} is the only main source file of the {@code common}, {@code claude} and
 * {@code codex} command-line modules that spells CAT's work root, so that every other file takes the work root and the
 * paths inside it from that class. The build passes the {@code client} directory in the {@code cat.client.directory}
 * system property.
 */
public final class WorkDirectoryAuditTest
{
	/**
	 * The modules, relative to the {@code client} directory, whose main sources the audit scans.
	 */
	private static final List<String> MODULES = List.of("common/cli", "claude/cli", "codex/cli");
	/**
	 * The only main source file that may spell the work root, relative to the {@code client} directory.
	 */
	private static final Path OWNER = Path.of("common/cli/src/main/java/io/github/cowwoc/cat/common/" +
		"SessionWorkDirectory.java");

	/**
	 * Creates a test instance.
	 */
	public WorkDirectoryAuditTest()
	{
	}

	/**
	 * Verifies that no main source file other than {@link SessionWorkDirectory} spells the work root, and that the scan
	 * reads that file.
	 *
	 * @throws IOException if a source file cannot be read
	 */
	@Test
	public void onlySessionWorkDirectorySpellsWorkRoot() throws IOException
	{
		// Requirement: R-W13
		Path client = clientDirectory();
		Path owner = client.resolve(OWNER);
		List<Path> spelling = new ArrayList<>();
		boolean ownerScanned = false;
		for (String module : MODULES)
		{
			for (Path file : listJavaFiles(client.resolve(module).resolve("src/main/java")))
			{
				if (file.equals(owner))
					ownerScanned = true;
				else if (Files.readString(file).contains(SessionWorkDirectory.WORK_ROOT))
					spelling.add(client.relativize(file));
			}
		}
		requireThat(ownerScanned, "ownerScanned").isTrue();
		requireThat(Files.readString(owner), "owner").contains(SessionWorkDirectory.WORK_ROOT);
		requireThat(spelling, "spelling").isEmpty();
	}

	/**
	 * Returns the {@code client} directory that the build passes in.
	 *
	 * @return the directory
	 */
	private static Path clientDirectory()
	{
		String value = System.getProperty("cat.client.directory");
		requireThat(value, "cat.client.directory").isNotNull();
		return Path.of(value).toAbsolutePath().normalize();
	}

	/**
	 * Returns the Java source files below a directory.
	 *
	 * @param directory the directory
	 * @return the files, or an empty list if the directory does not exist
	 * @throws IOException if the directory cannot be listed
	 */
	private static List<Path> listJavaFiles(Path directory) throws IOException
	{
		if (Files.notExists(directory))
			return List.of();
		try (Stream<Path> paths = Files.walk(directory))
		{
			return paths.filter(Files::isRegularFile).
				filter(path -> path.getFileName().toString().endsWith(".java")).
				sorted().
				toList();
		}
	}
}
