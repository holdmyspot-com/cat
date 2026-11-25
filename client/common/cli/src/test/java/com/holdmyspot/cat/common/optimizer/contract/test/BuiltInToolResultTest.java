package com.holdmyspot.cat.common.optimizer.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.AssertionKind;
import com.holdmyspot.cat.common.optimizer.contract.BuiltInToolResult;
import com.holdmyspot.cat.common.optimizer.contract.BuiltInToolResult.Outcome;
import com.holdmyspot.cat.common.optimizer.contract.OnToolFailure;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests failure classification for built-in tools that run check programs.
 */
public final class BuiltInToolResultTest
{
	private static final String CHECK = "check-program checks/Check.java";
	private static final String DIAGNOSTIC = "Compilation failed.\nThe source refers to an undefined name.";

	/**
	 * Creates the test.
	 */
	public BuiltInToolResultTest()
	{
	}

	/**
	 * Supplies the two policies and their respective trial failure and judge fallback decisions.
	 *
	 * @return the policy rows
	 */
	@DataProvider
	public static Object[][] failurePolicies()
	{
		return new Object[][]{{OnToolFailure.FAIL, true, false}, {OnToolFailure.JUDGE, false, true}};
	}

	/**
	 * Preserves the tool's description and failure policy while recording a separate behavior for a program error.
	 *
	 * @param policy the assertion's failure policy
	 * @param failsTrial whether a failed tool fails the trial
	 * @param leftToJudge whether a failed tool leaves the assertion to the judge
	 */
	@Test(dataProvider = "failurePolicies")
	public void programErrorFollowsToolFailurePolicy(OnToolFailure policy, boolean failsTrial, boolean leftToJudge)
	{
		// Requirement: R-L21
		Assertion assertion = new Assertion("service_ready", AssertionKind.EFFECT, CHECK, policy,
			"The service is ready.");

		BuiltInToolResult passed = new BuiltInToolResult(assertion, true, "The service is ready.");
		BuiltInToolResult failed = new BuiltInToolResult(assertion, false, "The service is missing.");
		BuiltInToolResult error = BuiltInToolResult.programError(assertion, DIAGNOSTIC);

		requireThat(passed.passed(), "passed").isTrue();
		requireThat(passed.failsTrial(), "passedFailsTrial").isFalse();
		requireThat(passed.leftToJudge(), "passedLeftToJudge").isFalse();
		requireThat(passed.describe(), "passedDescription").isEqualTo(
			"Assertion `service_ready`, `tool: check-program checks/Check.java`: passed. Observed: The service is ready.");
		requireThat(failed.behavior(), "ordinaryFailure").isEqualTo("check_program_failed");
		requireThat(failed.failsTrial(), "ordinaryFailsTrial").isEqualTo(failsTrial);
		requireThat(failed.leftToJudge(), "ordinaryLeftToJudge").isEqualTo(leftToJudge);
		requireThat(error.passed(), "errorPassed").isFalse();
		requireThat(error.failsTrial(), "errorFailsTrial").isEqualTo(failsTrial);
		requireThat(error.leftToJudge(), "errorLeftToJudge").isEqualTo(leftToJudge);
		requireThat(error.behavior(), "programError").isEqualTo("check_program_error");
		requireThat(error.observed(), "observed").isEqualTo(DIAGNOSTIC);
		requireThat(error.describe(), "errorDescription").isEqualTo(
			"Assertion `service_ready`, `tool: check-program checks/Check.java`: failed (check_program_error). " +
				"Observed: " + DIAGNOSTIC);
	}

	/**
	 * Reserves program errors for assertions with a check-program tool.
	 */
	@Test
	public void programErrorRequiresCheckProgram()
	{
		// Requirement: R-L21
		Assertion ordinary = new Assertion("reply_ready", AssertionKind.EFFECT, "exact-text Ready.",
			"The reply says Ready.");
		Assertion judged = new Assertion("judged_ready", AssertionKind.EFFECT, null, "The service is ready.");

		expectThrows(IllegalArgumentException.class, () -> BuiltInToolResult.programError(ordinary, DIAGNOSTIC));
		expectThrows(IllegalArgumentException.class, () -> new BuiltInToolResult(ordinary, Outcome.PROGRAM_ERROR,
			DIAGNOSTIC));
		expectThrows(IllegalArgumentException.class, () -> BuiltInToolResult.programError(judged, DIAGNOSTIC));
	}
}
