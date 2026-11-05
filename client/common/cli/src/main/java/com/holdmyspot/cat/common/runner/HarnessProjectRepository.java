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

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Creates the independent Git baseline for a copied harness project.
 */
public final class HarnessProjectRepository
{
	static
	{
		HarnessProjectRepositorySecrets.setHarnessProjectRepositoryAccess(new HarnessProjectRepositoryAccess()
		{
			@Override
			public Map<String, String> environment(Map<String, String> parentEnvironment, Path gitHome)
			{
				return HarnessProjectRepository.environment(parentEnvironment, gitHome);
			}
		});
	}

	/**
	 * Makes a copied project a clean repository whose baseline is exactly the copied fixture and that never starts
	 * background maintenance. Its baseline and later commits use the Git user name and email that the launch names,
	 * and otherwise the source project's, resolved with the parent environment. If either value is missing, both use
	 * {@code CAT fixture <cat-fixture@example.invalid>}.
	 *
	 * @param sourceProject     the source project whose Git identity is inherited
	 * @param project           the copied project directory
	 * @param runDirectory      the owned run directory that may hold setup-only state
	 * @param parentEnvironment the environment resolved by the production entry point
	 * @param executables       runs Git
	 * @param gitName           the Git user name that the launch names, or {@code null} to read the source
	 *                          project's
	 * @param gitEmail          the Git user email that the launch names, or {@code null} to read the source
	 *                          project's
	 * @throws NullPointerException if any of the arguments are null, other than {@code gitName} and {@code gitEmail}
	 * @throws IOException         if Git cannot resolve the identity or create the independent baseline
	 */
	public static void initialize(Path sourceProject, Path project, Path runDirectory,
		Map<String, String> parentEnvironment, ExecutableFiles executables, String gitName, String gitEmail)
		throws IOException
	{
		requireThat(sourceProject, "sourceProject").isNotNull();
		requireThat(project, "project").isNotNull();
		requireThat(runDirectory, "runDirectory").isNotNull();
		requireThat(parentEnvironment, "parentEnvironment").isNotNull();
		requireThat(executables, "executables").isNotNull();

		String name = gitName;
		if (name == null)
			name = readIdentity(sourceProject, parentEnvironment, executables, "user.name");
		String email = gitEmail;
		if (email == null)
			email = readIdentity(sourceProject, parentEnvironment, executables, "user.email");
		boolean incompleteIdentity = name == null || email == null;
		if (incompleteIdentity)
		{
			name = "CAT fixture";
			email = "cat-fixture@example.invalid";
		}
		Path gitHome = runDirectory.resolve("git-home");
		Files.createDirectories(gitHome);
		Map<String, String> environment = environment(parentEnvironment, gitHome);
		for (List<String> command : commands(name, email))
			HarnessCommand.execute(command, project, environment, executables);
	}

	/**
	 * Reads one Git identity value from the source project with the parent's configuration.
	 *
	 * @param sourceProject     the source project directory
	 * @param parentEnvironment the complete parent process environment
	 * @param executables       runs Git
	 * @param key               the Git identity configuration key
	 * @return the configured value, or null when Git reports a missing value with status one and no output
	 * @throws IOException if Git cannot run, fails, or is interrupted
	 */
	private static String readIdentity(Path sourceProject, Map<String, String> parentEnvironment,
		ExecutableFiles executables, String key) throws IOException
	{
		List<String> command = List.of("git", "config", "--get", key);
		try (Process process = HarnessProcessStarter.start(command, sourceProject, parentEnvironment, executables))
		{
			HarnessStandardError stderr = new HarnessStandardError(process.getErrorStream());
			try
			{
				// A configuration value must be retained intact to pass it as one argument to the copy's git config.
				String output = new String(process.getInputStream().readAllBytes(), UTF_8);
				int exitCode = process.waitFor();
				String error = stderr.await();
				boolean missing = exitCode == 1 && output.isEmpty() && error.isEmpty();
				if (missing)
					return null;
				if (exitCode != 0)
					throw new IOException("Git identity command failed: " + command + ": " + error.strip());
				if (output.endsWith("\n"))
					return output.substring(0, output.length() - 1);
				return output;
			}
			finally
			{
				stderr.close();
			}
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while running Git identity command: " + command, e);
		}
		catch (IOException e)
		{
			throw new IOException("Unable to resolve Git identity using " + command + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Returns the commands that configure the copy's identity and create its baseline, in order.
	 *
	 * @param name  the Git user name
	 * @param email the Git user email
	 * @return the baseline commands
	 */
	private static List<List<String>> commands(String name, String email)
	{
		return List.of(
			List.of("git", "init", "--quiet"),
			// Detached maintenance outlives the command that starts it and races the deletion of the run directory
			List.of("git", "config", "maintenance.auto", "false"),
			List.of("git", "config", "gc.auto", "0"),
			List.of("git", "config", "user.name", name),
			List.of("git", "config", "user.email", email),
			List.of("git", "add", "--all"),
			List.of("git", "-c", "commit.gpgSign=false", "commit", "--quiet", "--allow-empty", "--no-verify", "-m",
				"CAT fixture baseline"));
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
