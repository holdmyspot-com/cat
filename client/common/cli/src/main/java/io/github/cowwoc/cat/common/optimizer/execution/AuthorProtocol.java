package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Turns an author brief into an author session, for the {@link Driver}.
 * <p>
 * This is the seam that the author prompt fills. The driver decides what the author may see ({@link AuthorBrief})
 * and validates what the author changed; the protocol phrases the request and reads the author's report of the edits
 * it needed but did not make.
 */
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

	/**
	 * Reads the edits that an author reported its requirements need but that it did not make.
	 *
	 * @param result the author session's result
	 * @return the needed edits in report order, or empty if the final response is not a well-formed report
	 * @throws NullPointerException if {@code result} is null
	 */
	Optional<List<NeededEdit>> neededEdits(SessionResult.Completed result);
}
