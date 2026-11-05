package com.holdmyspot.cat.common.installer.engine;

import com.holdmyspot.cat.common.installer.activation.CommandResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Runs one data migration script of a release.
 */
@FunctionalInterface
public interface MigrationRunner
{
	/**
	 * Runs a script and waits for it to finish.
	 *
	 * @param script    the absolute path of the script, a POSIX shell script or, on Windows, a PowerShell script
	 * @param variables the environment variables that the script receives in addition to the installer's environment
	 * @return the script's exit status and output
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the script cannot be started, or does not finish in time
	 */
	CommandResult run(Path script, Map<String, String> variables) throws IOException;
}
