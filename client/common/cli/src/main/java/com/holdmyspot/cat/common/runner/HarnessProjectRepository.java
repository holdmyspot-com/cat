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
	/**
	 * The author and committer date of every baseline commit, in Git's internal format (2000-01-01T00:00:00Z).
	 */
	private static final String BASELINE_DATE = "@946684800 +0000";

	static
	{
		HarnessProjectRepositorySecrets.setHarnessProjectRepositoryAccess(new HarnessProjectRepositoryAccess()
		{
			@Override
			public Map<String, String> environment(Map<String, String> parentEnvironment, Path gitHome)
			{
				return HarnessProjectRepository.environment(parentEnvironment, gitHome);
			}

			@Override
			public HarnessProjectRepository.Identity identity(String gitName, String gitEmail, String hostName,
				String hostEmail)
			{
				return HarnessProjectRepository.identity(gitName, gitEmail, hostName, hostEmail);
			}

			@Override
			public String identityValue(List<String> command, int exitCode, String output, String error)
				throws IOException
			{
				return HarnessProjectRepository.identityValue(command, exitCode, output, error);
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

		String hostName = null;
		if (gitName == null)
			hostName = readIdentity(sourceProject, parentEnvironment, executables, "user.name");
		String hostEmail = null;
		if (gitEmail == null)
			hostEmail = readIdentity(sourceProject, parentEnvironment, executables, "user.email");
		Identity identity = identity(gitName, gitEmail, hostName, hostEmail);
		Path gitHome = runDirectory.resolve("git-home");
		Files.createDirectories(gitHome);
		Map<String, String> environment = environment(parentEnvironment, gitHome);
		for (List<String> command : commands(identity.name(), identity.email()))
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
				return identityValue(command, exitCode, output, error);
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
	 * Chooses the identity of the copy's commits. A name or email that the launch names is used as given. Otherwise the
	 * host's is used. If either value is still missing, both are the fixture's.
	 *
	 * @param gitName   the Git user name that the launch names, or {@code null} if it names none
	 * @param gitEmail  the Git user email that the launch names, or {@code null} if it names none
	 * @param hostName  the source project's Git user name, or {@code null} if it has none or was not read
	 * @param hostEmail the source project's Git user email, or {@code null} if it has none or was not read
	 * @return the identity
	 */
	static Identity identity(String gitName, String gitEmail, String hostName, String hostEmail)
	{
		String name = gitName;
		if (name == null)
			name = hostName;
		String email = gitEmail;
		if (email == null)
			email = hostEmail;
		boolean incompleteIdentity = name == null || email == null;
		if (incompleteIdentity)
			return new Identity("CAT fixture", "cat-fixture@example.invalid");
		return new Identity(name, email);
	}

	/**
	 * Interprets the result of {@code git config --get} for an identity key.
	 *
	 * @param command  the command that ran
	 * @param exitCode the command's exit status
	 * @param output   the command's standard output
	 * @param error    the command's standard error
	 * @return the configured value without its final line feed, or {@code null} if Git reports a missing value with
	 *   status one and no output
	 * @throws IOException if the command failed
	 */
	static String identityValue(List<String> command, int exitCode, String output, String error) throws IOException
	{
		boolean missing = exitCode == 1 && output.isEmpty() && error.isEmpty();
		if (missing)
			return null;
		if (exitCode != 0)
			throw new IOException("Git identity command failed: " + command + ": " + error.strip());
		if (output.endsWith("\n"))
			return output.substring(0, output.length() - 1);
		return output;
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
	 * Returns the environment that isolates the baseline's Git commands from the user's configuration. The baseline's
	 * author and committer dates are fixed, so the same fixture and identity always give the same commit hash. The
	 * hash appears in the session's Git status, so a stable hash keeps that text the same from one session to the
	 * next.
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
		environment.put("GIT_AUTHOR_DATE", BASELINE_DATE);
		environment.put("GIT_COMMITTER_DATE", BASELINE_DATE);
		return environment;
	}

	/**
	 * The Git identity of the copy's commits.
	 *
	 * @param name  the user name
	 * @param email the user email
	 */
	public record Identity(String name, String email)
	{
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private HarnessProjectRepository()
	{
	}
}
