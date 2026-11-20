package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.SessionDirectory;
import com.holdmyspot.cat.common.ToolScope;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Where a runner command keeps its nested sessions when the command line names no {@code --results}: the {@code temp}
 * directory of the CAT session directory of the harness session that runs the command. Outside a harness session there
 * is no such directory, and the command line must name {@code --results}. A command that names {@code --results} leaves
 * the session's CAT session directory alone.
 */
public final class HarnessTemporaryRoot
{
	private final SessionTemp sessionTemp;
	private final String missingSession;

	/**
	 * Creates a temporary root.
	 *
	 * @param sessionTemp opens the session's temporary directory
	 * @param missingSession why there is no session
	 */
	private HarnessTemporaryRoot(SessionTemp sessionTemp, String missingSession)
	{
		this.sessionTemp = sessionTemp;
		this.missingSession = missingSession;
	}

	/**
	 * Returns the temporary root of a harness session.
	 *
	 * @param sessionTemp the {@code temp} directory of the session's CAT session directory
	 * @return the temporary root
	 * @throws NullPointerException if {@code sessionTemp} is null
	 */
	public static HarnessTemporaryRoot of(Path sessionTemp)
	{
		requireThat(sessionTemp, "sessionTemp").isNotNull();
		Path normalized = sessionTemp.toAbsolutePath().normalize();
		return new HarnessTemporaryRoot(() -> Optional.of(normalized), "");
	}

	/**
	 * Returns the temporary root of a command that runs outside a harness session.
	 *
	 * @param missingSession why there is no session, such as which session variables are unset
	 * @return the temporary root, which requires {@code --results}
	 * @throws NullPointerException if {@code missingSession} is null
	 * @throws IllegalArgumentException if {@code missingSession} is blank
	 */
	public static HarnessTemporaryRoot withoutSession(String missingSession)
	{
		requireThat(missingSession, "missingSession").isNotBlank();
		return new HarnessTemporaryRoot(Optional::empty, missingSession);
	}

	/**
	 * Returns the temporary root of the harness session that a tool scope belongs to. The session's CAT session directory
	 * is opened, and created if absent, only when a command needs the default results folder.
	 *
	 * @param scope the command's tool scope, which must stay open while the command line is parsed
	 * @return the temporary root
	 * @throws NullPointerException if {@code scope} is null
	 * @throws IllegalStateException if {@code scope} is closed
	 */
	public static HarnessTemporaryRoot from(ToolScope scope)
	{
		requireThat(scope, "scope").isNotNull();
		return new HarnessTemporaryRoot(() -> scope.sessionDirectory().map(SessionDirectory::temp),
			scope.missingSession());
	}

	/**
	 * Returns the default root for isolated harness runs.
	 *
	 * @return {@code nested-sessions} in the session's temporary directory
	 * @throws InvalidArgumentsException if there is no harness session, so the command line must name
	 *   {@code --results}
	 * @throws UncheckedIOException if the session variable is not a UUID in canonical form, or if the session's CAT
	 *   session directory cannot be created
	 */
	public Path defaultResultsFolder()
	{
		Optional<Path> temp;
		try
		{
			temp = sessionTemp.open();
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
		if (temp.isEmpty())
			throw new InvalidArgumentsException("--results is required outside a harness session: " + missingSession);
		return temp.get().resolve("nested-sessions");
	}

	/**
	 * Returns the results folder that a command line selects.
	 *
	 * @param resultsFolder the {@code --results} value, or {@code null} when the command line names none
	 * @return the normalized absolute results folder
	 * @throws InvalidArgumentsException if {@code resultsFolder} is null and there is no harness session
	 * @throws UncheckedIOException if {@code resultsFolder} is null and the session's CAT session directory cannot be
	 *                              opened
	 */
	Path resultsFolder(String resultsFolder)
	{
		if (resultsFolder == null)
			return defaultResultsFolder();
		return Path.of(resultsFolder).toAbsolutePath().normalize();
	}

	/**
	 * Opens the session's temporary directory.
	 */
	@FunctionalInterface
	private interface SessionTemp
	{
		/**
		 * Opens the session's temporary directory.
		 *
		 * @return the directory, or empty outside a harness session
		 * @throws IOException if the session's CAT session directory cannot be opened
		 */
		Optional<Path> open() throws IOException;
	}
}
