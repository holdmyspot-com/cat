package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.campaign.FailureGroup;
import io.github.cowwoc.cat.common.optimizer.contract.AddedCombination;
import io.github.cowwoc.cat.common.optimizer.contract.AddedValues;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.InvalidProposalException;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;

import java.util.List;

/**
 * Creates the request for the deriver session that writes the key-value combinations of a formal failure group's
 * reproduction round, and validates the combinations that it writes, for the {@link Driver}.
 * <p>
 * The session receives the group's test with only the development values of its key dimensions, never the salt or a
 * held-out value, together with the group's cell, failed requirement, behavior and formal failure count, and the
 * combinations that the group's earlier rounds tried with their reproduction counts, never a trial's reply.
 */
public interface ValuesProtocol
{
	/**
	 * The fewest new key-value combinations that the session writes for a round.
	 */
	int MINIMUM_COMBINATIONS = 4;

	/**
	 * Creates the session request.
	 *
	 * @param contract the confirmed contract
	 * @param known the combinations of the contract's tests beyond the confirmed values: those added to development
	 *   and those that reproduction rounds tried or try
	 * @param group the failure group whose current round awaits values
	 * @return a request whose role is {@link SessionRole#DERIVER}
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if the contract lacks the group's test, cell or requirement
	 */
	SessionRequest request(Contract contract, AddedValues known, FailureGroup group);

	/**
	 * Validates the session's combinations.
	 *
	 * @param result the session's result
	 * @param contract the confirmed contract
	 * @param known the combinations of the contract's tests beyond the confirmed values: those added to development
	 *   and those that reproduction rounds tried or try
	 * @param attempt the index of the attempt whose failure group the values address
	 * @param group the failure group whose current round awaits values
	 * @return the new combinations, at least {@value #MINIMUM_COMBINATIONS}
	 * @throws NullPointerException if any of the arguments are null
	 * @throws InvalidProposalException if the final response is not exactly one JSON object, bare or alone inside one
	 *   Markdown code fence, violates the output schema, gives fewer than {@value #MINIMUM_COMBINATIONS} distinct
	 *   combinations, gives a value for a dimension that is not one of the test's key dimensions, gives one value two
	 *   labels or texts, or gives a value equal to an existing value of its dimension
	 */
	List<AddedCombination> combinations(SessionResult.Completed result, Contract contract, AddedValues known,
		int attempt, FailureGroup group) throws InvalidProposalException;
}
