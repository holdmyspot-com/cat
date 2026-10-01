package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;

import java.nio.file.Path;

/**
 * Turns an author brief into an author session, for the {@link Driver}.
 * <p>
 * This is the seam that the author prompt fills. The driver decides what the author may see ({@link AuthorBrief})
 * and validates what the author changed; the protocol only phrases the request.
 */
@FunctionalInterface
public interface AuthorProtocol
{
	/**
	 * Returns the author session for one author attempt.
	 *
	 * @param brief what the author may see
	 * @param copy the copy of the working candidate that the author edits
	 * @return a request whose role is {@link SessionRole#AUTHOR}
	 * @throws NullPointerException if any of the arguments are null
	 */
	SessionRequest request(AuthorBrief brief, Path copy);
}
