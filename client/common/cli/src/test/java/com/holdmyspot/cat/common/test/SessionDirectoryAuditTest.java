package com.holdmyspot.cat.common.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.SessionDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.testng.annotations.Test;

/**
 * Verifies that {@link SessionDirectory} is the only main source file of the {@code common}, {@code claude} and
 * {@code codex} command-line modules that spells the directory that holds the CAT session directories, or the directory
 * {@code .cat/work} that held them in earlier versions of CAT, so that every other file takes the CAT session
 * directories and the paths inside them from that class. The build passes the {@code client} directory in the
 * {@code cat.client.directory} system property.
 */
public final class SessionDirectoryAuditTest
{
	/**
	 * The modules, relative to the {@code client} directory, whose main sources the audit scans.
	 */
	private static final List<String> MODULES = List.of("common/cli", "claude/cli", "codex/cli");
	/**
	 * The directory, relative to a project, that held the CAT session directories in earlier versions of CAT.
	 */
	private static final String FORMER_ROOT = ".cat/work";
	/**
	 * The only main source file that may spell either directory, relative to the {@code client} directory.
	 */
	private static final Path OWNER = Path.of("common/cli/src/main/java/com/holdmyspot/cat/common/" +
		"SessionDirectory.java");

	/**
	 * Creates a test instance.
	 */
	public SessionDirectoryAuditTest()
	{
	}

	/**
	 * Verifies that no main source file other than {@link SessionDirectory} spells the directory that holds the CAT
	 * session directories or {@code .cat/work}, and that the scan reads that file and finds both in it.
	 *
	 * @throws IOException if a source file cannot be read
	 */
	@Test
	public void onlySessionDirectorySpellsRoots() throws IOException
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
				else
				{
					String content = Files.readString(file);
					if (content.contains(SessionDirectory.ROOT) || content.contains(FORMER_ROOT))
						spelling.add(client.relativize(file));
				}
			}
		}
		requireThat(ownerScanned, "ownerScanned").isTrue();
		requireThat(Files.readString(owner), "owner").contains(SessionDirectory.ROOT).contains(FORMER_ROOT);
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
