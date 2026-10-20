package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.secrets.HarnessProjectRepositoryAccess;
import com.holdmyspot.cat.common.secrets.HarnessProjectRepositorySecrets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates the independent Git baseline for a copied harness project.
 */
public final class HarnessProjectRepository
{
	static
	{
		HarnessProjectRepositorySecrets.setAccess(new HarnessProjectRepositoryAccess()
		{
			@Override
			public List<List<String>> commands()
			{
				return COMMANDS;
			}

			@Override
			public Map<String, String> environment(Map<String, String> parentEnvironment, Path gitHome)
			{
				return HarnessProjectRepository.environment(parentEnvironment, gitHome);
			}
		});
	}

	/**
	 * The commands that create the baseline, in order.
	 */
	static final List<List<String>> COMMANDS = List.of(
		List.of("git", "init", "--quiet"),
		// Detached maintenance outlives the command that starts it and races the deletion of the run directory
		List.of("git", "config", "maintenance.auto", "false"),
		List.of("git", "config", "gc.auto", "0"),
		List.of("git", "add", "--all"),
		List.of("git", "-c", "user.name=CAT fixture", "-c", "user.email=cat-fixture@example.invalid", "-c",
			"commit.gpgSign=false", "commit", "--quiet", "--allow-empty", "--no-verify", "-m", "CAT fixture baseline"));

	/**
	 * Makes a copied project a clean repository whose baseline is exactly the copied fixture and that never starts
	 * background maintenance.
	 *
	 * @param project the copied project directory
	 * @param runDirectory the owned run directory that may hold setup-only state
	 * @param parentEnvironment the environment resolved by the production entry point
	 * @param executables runs Git
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException if Git cannot create the independent baseline
	 */
	public static void initialize(Path project, Path runDirectory, Map<String, String> parentEnvironment,
		ExecutableFiles executables) throws IOException
	{
		requireThat(project, "project").isNotNull();
		requireThat(runDirectory, "runDirectory").isNotNull();
		requireThat(parentEnvironment, "parentEnvironment").isNotNull();
		requireThat(executables, "executables").isNotNull();

		Path gitHome = runDirectory.resolve("git-home");
		Files.createDirectories(gitHome);
		Map<String, String> environment = environment(parentEnvironment, gitHome);
		for (List<String> command : COMMANDS)
			HarnessCommand.execute(command, project, environment, executables);
	}

	/**
	 * Returns the environment that isolates the baseline's Git commands from the user's configuration.
	 *
	 * @param parentEnvironment the environment resolved by the production entry point
	 * @param gitHome the run-owned directory that Git uses as its home
	 * @return the Git environment
	 */
	static Map<String, String> environment(Map<String, String> parentEnvironment, Path gitHome)
	{
		Map<String, String> environment = new HashMap<>(HarnessEnvironment.scrub(parentEnvironment));
		environment.put("HOME", gitHome.toString());
		environment.put("GIT_CONFIG_NOSYSTEM", "1");
		environment.put("GIT_TERMINAL_PROMPT", "0");
		return environment;
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private HarnessProjectRepository()
	{
	}
}
