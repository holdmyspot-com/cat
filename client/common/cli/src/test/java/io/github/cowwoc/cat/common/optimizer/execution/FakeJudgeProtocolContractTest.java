package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.execution.contract.JudgeProtocolContract;

import org.testng.annotations.Test;

/**
 * Runs the compatible checks of {@link JudgeProtocolContract} against {@link FakeJudgeProtocol}. Its scripted
 * request and literal-verdict behavior remain specific to the execution fixtures.
 */
public final class FakeJudgeProtocolContractTest
{
	/**
	 * Creates the test.
	 */
	public FakeJudgeProtocolContractTest()
	{
	}

	/**
	 * Verifies that the request selects the frozen judge profile and final trial reply.
	 */
	@Test
	public void requestSelectsFrozenProfile()
	{
		// Requirement: R-E13
		contract().requestSelectsFrozenProfile();
	}

	/**
	 * Verifies that the request protects effects and transcript evidence.
	 */
	@Test
	public void requestProtectsEvidence()
	{
		// Requirement: R-E13
		contract().requestProtectsEvidence();
	}

	/**
	 * Verifies that a request needs a requirement requiring judgment.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*requirements.*")
	public void rejectsNoRequirements()
	{
		// Requirement: R-E13
		contract().rejectsNoRequirements();
	}

	/**
	 * Verifies that malformed final output has no judgment.
	 */
	@Test
	public void rejectsMalformedOutput()
	{
		// Requirement: R-E13
		contract().rejectsMalformedOutput();
	}

	/**
	 * Verifies that earlier output cannot rescue invalid final output.
	 */
	@Test
	public void readsOnlyFinalResponse()
	{
		// Requirement: R-E13
		contract().readsOnlyFinalResponse();
	}

	/**
	 * Verifies that absent responses have no judgment.
	 */
	@Test
	public void rejectsAbsentResponses()
	{
		// Requirement: R-E13
		contract().rejectsAbsentResponses();
	}

	/**
	 * Creates shared checks over a fresh adapter.
	 *
	 * @return the contract
	 */
	private static JudgeProtocolContract contract()
	{
		return new JudgeProtocolContract(new FakeJudgeProtocol());
	}
}
