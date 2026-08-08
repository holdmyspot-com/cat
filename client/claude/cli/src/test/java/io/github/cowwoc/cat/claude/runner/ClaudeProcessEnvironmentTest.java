package io.github.cowwoc.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests {@link ClaudeProcessEnvironment}.
 */
public final class ClaudeProcessEnvironmentTest
{
	/**
	 * Verifies the nested Claude process retains each available path directory once and omits unavailable path entries.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void removesMissingAndDuplicatePathEntries() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-path-"));
			Path first = Files.createDirectory(root.resolve("first"));
			Path second = Files.createDirectory(root.resolve("second"));
			Path missing = root.resolve("missing");
			Path file = Files.createFile(root.resolve("file"));
			Map<String, String> environment = new HashMap<>();
			environment.put("PATH", String.join(File.pathSeparator, first.toString(), missing.toString(), second.toString(),
				first.toString(), file.toString()));

			ClaudeProcessEnvironment.removeUnavailablePathEntries(environment);

			requireThat(environment.get("PATH"), "path").isEqualTo(first + File.pathSeparator + second);
		}
	}
}
