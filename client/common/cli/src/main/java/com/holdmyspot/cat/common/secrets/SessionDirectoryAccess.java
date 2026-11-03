package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.SessionDirectory;
import com.holdmyspot.cat.common.SessionHarness;
import com.holdmyspot.cat.common.SessionIgnore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exposes how {@link SessionDirectory} opens a session's directory from given Git answers to the tests of CAT's
 * modules.
 */
@FunctionalInterface
public interface SessionDirectoryAccess
{
	/**
	 * Returns a session's CAT session directory, as {@link SessionDirectory#open} does, deciding whether to add the CAT
	 * session directories to the project's {@code .gitignore} from the answers that a given Git gives.
	 *
	 * @param projectRoot            the session's project
	 * @param sessionId              the harness's session ID, a UUID in canonical form
	 * @param harness                the harness whose session owns the directory
	 * @param configurationDirectory the harness's configuration directory
	 * @param now                    the current time, which a new marker records
	 * @param json                   the JSON mapper that writes and reads markers
	 * @param git                    answers what Git ignores in the project, asked only when this call creates the
	 *                               directory
	 * @return the CAT session directory
	 * @throws NullPointerException if any argument is null
	 * @throws IOException          if {@code sessionId} is not a UUID in canonical form, or if the directory or marker
	 *                              cannot be created
	 */
	SessionDirectory open(Path projectRoot, String sessionId, SessionHarness harness, Path configurationDirectory,
		Instant now, JsonMapper json, SessionIgnore.GitQueries git) throws IOException;
}
