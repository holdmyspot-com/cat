package io.github.cowwoc.cat.common.optimization.evaluator;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.runner.HarnessModelProfile;
import io.github.cowwoc.cat.common.runner.ReasoningEffort;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Tests deterministic evaluator-protocol approval and cost-adjusted selection.
 */
public final class EvaluatorPolicyTest
{
	private final EvaluatorPolicy policy = new EvaluatorPolicy("evaluator-policy-v1", 0.97, 0.95, 0.01, 0.05,
		0.01, 2.5);

	/**
	 * Creates the test fixture.
	 */
	public EvaluatorPolicyTest()
	{
	}

	/**
	 * Verifies a protocol that passes every metric and mandatory adversarial control is approved.
	 */
	@Test
	public void approvesValidatedProtocol()
	{
		EvaluatorPolicy.Approval approval = policy.approve(report(980, 1, 19, 970, 100_000));

		requireThat(approval.status(), "status").isEqualTo(EvaluatorPolicy.ApprovalStatus.APPROVED);
	}

	/**
	 * Verifies a prompt-injection failure blocks protocol use even when aggregate accuracy is high.
	 */
	@Test
	public void rejectsMissingInjectionControl()
	{
		EvaluatorPolicy.CorpusReport report = report(990, 0, 10, 990, 100_000);
		Set<EvaluatorPolicy.Control> controls = EnumSet.copyOf(report.passedControls());
		controls.remove(EvaluatorPolicy.Control.PROMPT_INJECTION);
		report = new EvaluatorPolicy.CorpusReport(report.totalDecisions(), report.correctDecisions(),
			report.expectedFailingDecisions(), report.expectedPassingDecisions(), report.falsePasses(),
			report.falseFails(), report.repeatedPairs(), report.reproduciblePairs(), controls, report.observedTokens(),
			report.elapsed());

		EvaluatorPolicy.Approval approval = policy.approve(report);

		requireThat(approval.status(), "status").isEqualTo(EvaluatorPolicy.ApprovalStatus.REJECTED);
		requireThat(approval.failures(), "failures").containsExactly(
			java.util.List.of(EvaluatorPolicy.Failure.MANDATORY_CONTROL_FAILED));
	}

	/**
	 * Verifies adjudication is selected only when its validated improvement justifies its added token cost.
	 */
	@Test
	public void selectsMateriallyBetterAdjudication()
	{
		EvaluatorPolicy.Selection selection = policy.select(report(970, 2, 28, 960, 100_000),
			report(990, 1, 9, 980, 220_000));

		requireThat(selection.protocol(), "protocol").isEqualTo(EvaluatorPolicy.ProtocolKind.ADJUDICATED);
	}

	/**
	 * Verifies the cheaper single evaluator remains selected when adjudication adds no meaningful accuracy.
	 */
	@Test
	public void keepsSingleWithoutMaterialBenefit()
	{
		EvaluatorPolicy.Selection selection = policy.select(report(980, 1, 19, 970, 100_000),
			report(985, 1, 14, 975, 220_000));

		requireThat(selection.protocol(), "protocol").isEqualTo(EvaluatorPolicy.ProtocolKind.SINGLE);
	}

	/**
	 * Verifies false passes are measured against expected failures instead of being diluted by expected passes.
	 */
	@Test
	public void rejectsDilutedFalsePassRate()
	{
		EvaluatorPolicy.CorpusReport report = new EvaluatorPolicy.CorpusReport(1_000, 998, 10, 990, 2, 0,
			1_000, 1_000, EnumSet.allOf(EvaluatorPolicy.Control.class), 100_000, Duration.ofMinutes(2));

		EvaluatorPolicy.Approval approval = policy.approve(report);

		requireThat(approval.status(), "status").isEqualTo(EvaluatorPolicy.ApprovalStatus.REJECTED);
		requireThat(approval.failures(), "failures").contains(
			EvaluatorPolicy.Failure.FALSE_PASS_RATE_ABOVE_MAXIMUM);
	}

	/**
	 * Verifies an approved calibration token is bound to the exact frozen evaluator protocol.
	 */
	@Test
	public void bindsApprovalToExactProtocol()
	{
		EvaluatorProtocol protocol = protocol("semantic-v1");
		EvaluatorPolicy.ProtocolApproval approval = policy.approveSingleProtocol(protocol,
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			report(980, 1, 19, 970, 100_000));

		approval.verifyFor(protocol);
		requireThat(approval.approval().status(), "status").isEqualTo(EvaluatorPolicy.ApprovalStatus.APPROVED);
		requireThat(approval.corpusFingerprint(), "corpusFingerprint").isEqualTo(
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
	}

	/**
	 * Verifies a passing report for one protocol cannot approve a changed protocol.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*approval belongs to another evaluator protocol.*")
	public void rejectsApprovalForChangedProtocol()
	{
		EvaluatorPolicy.ProtocolApproval approval = policy.approveSingleProtocol(protocol("semantic-v1"),
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			report(980, 1, 19, 970, 100_000));

		approval.verifyFor(protocol("semantic-v2"));
	}

	/**
	 * Verifies an approved adjudicated protocol is not authorized when its measured improvement does not justify cost.
	 */
	@Test
	public void selectsSingleWithoutMaterialBenefit()
	{
		EvaluatorProtocol single = protocol("semantic-single-v1");
		EvaluatorProtocol adjudicated = EvaluatorProtocol.adjudicated("semantic-adjudicated-v1",
			new HarnessModelProfile("gpt-5.6-luna", ReasoningEffort.HIGH),
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", 64 * 1024);

		EvaluatorPolicy.ProtocolSelection selection = policy.selectProtocols(single,
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			report(980, 1, 19, 970, 100_000), adjudicated,
			"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
			report(985, 1, 14, 975, 220_000));

		requireThat(selection.selection().protocol(), "selectedProtocol").isEqualTo(EvaluatorPolicy.ProtocolKind.SINGLE);
		selection.approval().orElseThrow().verifyFor(single);
	}

	/**
	 * Creates a representative corpus report.
	 *
	 * @param correct correct decisions out of 1,000
	 * @param falsePasses false passing decisions out of 200 expected failures
	 * @param falseFails false failing decisions out of 800 expected passes
	 * @param reproduciblePairs agreeing repeated decisions out of 1,000
	 * @param tokens observed evaluator tokens
	 * @return the report
	 */
	private static EvaluatorPolicy.CorpusReport report(int correct, int falsePasses, int falseFails,
		int reproduciblePairs, long tokens)
	{
		return new EvaluatorPolicy.CorpusReport(1_000, correct, 200, 800, falsePasses, falseFails, 1_000,
			reproduciblePairs, EnumSet.allOf(EvaluatorPolicy.Control.class), tokens, Duration.ofMinutes(2));
	}

	/**
	 * Creates one frozen single-evaluator protocol.
	 *
	 * @param identifier the protocol identifier
	 * @return the protocol
	 */
	private static EvaluatorProtocol protocol(String identifier)
	{
		String prompt = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
		String rubric = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
		return EvaluatorProtocol.single(identifier,
			new HarnessModelProfile("gpt-5.6-luna", ReasoningEffort.HIGH), prompt, rubric, 64 * 1024);
	}
}
