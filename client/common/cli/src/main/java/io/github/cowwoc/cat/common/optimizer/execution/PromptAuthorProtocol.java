package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Creates a prompt author session request from the contract and outcomes in a {@link PromptAuthorBrief}, for the
 * {@link Driver}.
 * <p>
 * The driver selects the contract and outcomes the prompt author may see and validates its edits. The protocol writes
 * the request and reads the prompt author's report of edits it needed but did not make.
 */
public interface PromptAuthorProtocol
{
	/**
	 * Reads the complete evidence manifest and needed edits from the prompt author's final response.
	 *
	 * @param result the completed session
	 * @return the report, or empty if its shape or required values are invalid
	 */
	Optional<PromptAuthorReport> report(SessionResult.Completed result);
	/**
	 * Creates the session request for one prompt author attempt.
	 *
	 * @param brief what the prompt author may see
	 * @param copy the existing project directory containing the working candidate that the prompt author edits
	 * @return a request whose role is {@link SessionRole#PROMPT_AUTHOR}
	 * @throws NullPointerException if any of the arguments are null
	 */
	SessionRequest request(PromptAuthorBrief brief, Path copy);

	/**
	 * Reads the edits that a prompt author reported its requirements need but that it did not make.
	 *
	 * @param result the prompt author session's result
	 * @return the needed edits in report order, or empty if the final response is not a well-formed report
	 * @throws NullPointerException if {@code result} is null
	 */
	default Optional<List<NeededEdit>> neededEdits(SessionResult.Completed result)
	{
		return report(result).map(PromptAuthorReport::neededEdits);
	}
}
