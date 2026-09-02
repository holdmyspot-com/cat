package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates the independent Git baseline for a copied harness project.
 */
final class HarnessProjectRepository
{
	/**
	 * Makes a copied project a clean repository whose baseline is exactly the copied fixture.
	 *
	 * @param project the copied project directory
	 * @param runDirectory the owned run directory that may hold setup-only state
	 * @throws IOException if Git cannot create the independent baseline
	 */
	static void initialize(Path project, Path runDirectory) throws IOException
	{
		requireThat(project, "project").isNotNull();
		requireThat(runDirectory, "runDirectory").isNotNull();
		Path gitHome = runDirectory.resolve("git-home");
		Files.createDirectories(gitHome);
		Map<String, String> environment = new HashMap<>(HarnessEnvironment.scrub(System.getenv()));
		environment.put("HOME", gitHome.toString());
		environment.put("GIT_CONFIG_NOSYSTEM", "1");
		environment.put("GIT_TERMINAL_PROMPT", "0");
		HarnessCommand.execute(List.of("git", "-c", "maintenance.auto=false", "init", "--quiet"), project, environment);
		HarnessCommand.execute(List.of("git", "-c", "maintenance.auto=false", "add", "--all"), project, environment);
		HarnessCommand.execute(List.of("git", "-c", "maintenance.auto=false", "-c", "user.name=CAT fixture", "-c",
			"user.email=cat-fixture@example.invalid", "-c", "commit.gpgSign=false", "commit", "--quiet",
			"--allow-empty", "--no-verify", "-m", "CAT fixture baseline"), project, environment);
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private HarnessProjectRepository()
	{
	}
}
