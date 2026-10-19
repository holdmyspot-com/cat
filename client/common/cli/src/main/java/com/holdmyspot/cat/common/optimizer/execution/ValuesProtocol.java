package com.holdmyspot.cat.common.optimizer.execution;

import com.holdmyspot.cat.common.optimizer.campaign.FailureGroup;
import com.holdmyspot.cat.common.optimizer.contract.AddedTestCase;
import com.holdmyspot.cat.common.optimizer.contract.AddedTestCases;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.InvalidProposalException;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;

import java.util.List;

/**
 * Creates the request for the contract author session that writes the test cases of a formal failure group's
 * reproduction round, and validates the test cases that it writes, for the {@link Driver}.
 * <p>
 * The session receives the group's test with only its development test cases, never the salt or a held-out test case,
 * together with the group's cell, failed requirement, behavior and formal failure count, and the test cases that the
 * group's earlier rounds tried with their reproduction counts, never a trial's reply.
 */
public interface ValuesProtocol
{
	/**
	 * The fewest new test cases that the session writes for a round.
	 */
	int MINIMUM_TEST_CASES = 4;

	/**
	 * Creates the session request.
	 *
	 * @param contract the confirmed contract
	 * @param known the test cases of the contract's tests beyond the confirmed ones: those added to development and
	 *   those that reproduction rounds tried or try
	 * @param group the failure group whose current round awaits test cases
	 * @return a request whose role is {@link SessionRole#CONTRACT_AUTHOR}
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if the contract lacks the group's test, cell or requirement
	 */
	SessionRequest request(Contract contract, AddedTestCases known, FailureGroup group);

	/**
	 * Validates the session's test cases.
	 *
	 * @param result the session's result
	 * @param contract the confirmed contract
	 * @param known the test cases of the contract's tests beyond the confirmed ones: those added to development and
	 *   those that reproduction rounds tried or try
	 * @param attempt the index of the attempt whose failure group the test cases address
	 * @param group the failure group whose current round awaits test cases
	 * @return the new test cases, at least {@value #MINIMUM_TEST_CASES}
	 * @throws NullPointerException if any of the arguments are null
	 * @throws InvalidProposalException if the final response is not exactly one JSON object, bare or alone inside one
	 *   Markdown code fence, violates the output schema, gives fewer than {@value #MINIMUM_TEST_CASES} distinct test
	 *   cases, gives a value for a variable that the test does not declare or no value for one that it declares, gives
	 *   a variable two values, repeats the values of an existing or earlier test case, or gives a test case the
	 *   expected outcome of a held-out test case
	 */
	List<AddedTestCase> testCases(SessionResult.Completed result, Contract contract, AddedTestCases known,
		int attempt, FailureGroup group) throws InvalidProposalException;
}
