package io.github.cowwoc.cat.common.optimizer.execution.prompt;

import io.github.cowwoc.cat.common.optimizer.execution.contract.JudgeProtocolContract;

import java.nio.file.Path;

import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the shared {@link JudgeProtocolContract} against {@link JudgePrompt}.
 */
public final class JudgeProtocolContractTest
{
	/**
	 * Creates the test.
	 */
	public JudgeProtocolContractTest()
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
	 * Verifies that the request includes the profile and full evidence.
	 */
	@Test
	public void requestIncludesProfileAndEvidence()
	{
		// Requirement: R-E13
		contract().requestIncludesProfileAndEvidence();
	}

	/**
	 * Verifies that the judge works outside the trial effects.
	 */
	@Test
	public void requestIsolatesJudgeDirectory()
	{
		// Requirement: R-E13
		contract().requestIsolatesJudgeDirectory();
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
	 * Verifies that bare and fenced schema output retains its rationale.
	 */
	@Test
	public void acceptsSchemaOutput()
	{
		// Requirement: R-E13
		contract().acceptsSchemaOutput();
	}

	/**
	 * Verifies that a bare verdict word fails the result schema.
	 */
	@Test
	public void rejectsBareVerdicts()
	{
		// Requirement: R-E13
		contract().rejectsBareVerdicts();
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
		return new JudgeProtocolContract(new JudgePrompt(JsonMapper.builder().build(), Path.of("contract-evidence/judge")));
	}
}
