package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.campaign.FailureSummary;
import io.github.cowwoc.cat.common.optimizer.contract.AddedCombination;
import io.github.cowwoc.cat.common.optimizer.contract.AddedValues;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.InvalidProposalException;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;

import java.util.List;

/**
 * Creates the request for the deriver session that adds development values after a certification attempt ended with
 * formal failures, and validates the combinations that it writes, for the {@link Driver}.
 * <p>
 * The session receives each failed test with only the development values of its key dimensions, never the salt or a
 * held-out value, together with each failure's cell and failed requirements.
 */
public interface ValuesProtocol
{
	/**
	 * The fewest new key-value combinations that the session writes for each failed test.
	 */
	int MINIMUM_COMBINATIONS = 4;

	/**
	 * Creates the session request.
	 *
	 * @param contract the confirmed contract
	 * @param added the combinations added to the contract's tests before
	 * @param failures the formal failures of the attempt that ended, which must not be empty
	 * @return a request whose role is {@link SessionRole#DERIVER}
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code failures} is empty or names a test that the contract lacks
	 */
	SessionRequest request(Contract contract, AddedValues added, List<FailureSummary> failures);

	/**
	 * Validates the session's combinations.
	 *
	 * @param result the session's result
	 * @param contract the confirmed contract
	 * @param added the combinations added to the contract's tests before
	 * @param attempt the index of the attempt that ended
	 * @param failures the formal failures of the attempt that ended
	 * @return the new combinations, at least {@value #MINIMUM_COMBINATIONS} for each failed test
	 * @throws NullPointerException if any of the arguments are null
	 * @throws InvalidProposalException if the final response is not exactly one JSON object, bare or alone inside one
	 *   Markdown code fence, violates the output schema, omits a failed test or names another one, gives a test fewer
	 *   than {@value #MINIMUM_COMBINATIONS} distinct combinations, gives a value for a dimension that is not one of the
	 *   test's key dimensions, gives one value two labels or texts, or gives a value equal to an existing value of its
	 *   dimension
	 */
	List<AddedCombination> combinations(SessionResult.Completed result, Contract contract, AddedValues added,
		int attempt, List<FailureSummary> failures) throws InvalidProposalException;
}
