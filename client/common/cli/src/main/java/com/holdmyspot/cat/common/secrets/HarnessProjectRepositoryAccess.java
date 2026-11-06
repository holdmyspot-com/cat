package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.runner.HarnessProjectRepository;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Exposes {@link HarnessProjectRepository}'s isolated Git environment and identity decisions to the module's tests.
 */
public interface HarnessProjectRepositoryAccess
{
	/**
	 * Returns the environment that isolates the baseline's Git commands from the user's configuration.
	 *
	 * @param parentEnvironment the environment resolved by the production entry point
	 * @param gitHome           the run-owned directory that Git uses as its home
	 * @return the Git environment
	 */
	Map<String, String> environment(Map<String, String> parentEnvironment, Path gitHome);

	/**
	 * Chooses the identity of the copy's commits.
	 *
	 * @param gitName   the Git user name that the launch names, or {@code null} if it names none
	 * @param gitEmail  the Git user email that the launch names, or {@code null} if it names none
	 * @param hostName  the source project's Git user name, or {@code null} if it has none or was not read
	 * @param hostEmail the source project's Git user email, or {@code null} if it has none or was not read
	 * @return the identity
	 */
	HarnessProjectRepository.Identity identity(String gitName, String gitEmail, String hostName, String hostEmail);

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
	String identityValue(List<String> command, int exitCode, String output, String error) throws IOException;
}
