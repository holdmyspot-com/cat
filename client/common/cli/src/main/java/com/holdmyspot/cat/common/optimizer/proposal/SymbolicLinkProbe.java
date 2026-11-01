package com.holdmyspot.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Checks whether the current process can create symbolic links for {@link RegressionScenarios}.
 */
@FunctionalInterface
public interface SymbolicLinkProbe
{
	/**
	 * Creates a probe that uses a fresh child of the command's CAT temporary directory.
	 *
	 * @param temporaryDirectory the command's CAT temporary directory
	 * @return a probe that creates and removes a symbolic link and its target
	 * @throws NullPointerException if {@code temporaryDirectory} is null
	 */
	static SymbolicLinkProbe inDirectory(Path temporaryDirectory)
	{
		requireThat(temporaryDirectory, "temporaryDirectory").isNotNull();
		return () ->
		{
			Files.createDirectories(temporaryDirectory);
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path directory = cleanup.add(Files.createTempDirectory(temporaryDirectory, "symbolic-link-probe-"));
				Path target = Files.createFile(directory.resolve("target"));
				Files.createSymbolicLink(directory.resolve("link"), target.getFileName());
			}
		};
	}

	/**
	 * Creates and removes a symbolic link to check the process's actual permission and filesystem support.
	 *
	 * @throws IOException if link creation or probe cleanup fails
	 * @throws UnsupportedOperationException if the filesystem cannot create symbolic links
	 */
	void createAndRemove() throws IOException;
}
