package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.contract.BuiltInToolResult;
import io.github.cowwoc.cat.common.optimizer.contract.JudgeProfile;
import io.github.cowwoc.cat.common.optimizer.contract.JudgedAssertion;
import io.github.cowwoc.cat.common.optimizer.contract.DrawnTestCase;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;

import java.util.List;
import java.util.Optional;

/**
 * Creates a judge session request from a trial's evidence and parses its verdict, rationale and failure behaviors, for
 * the {@link Driver}.
 * <p>
 * The driver supplies the contract's frozen judge profile; the protocol writes the prompt. The driver requests a
 * judge only after every built-in tool of the trial passed. The judge decides each assertion without a {@code tool},
 * and always the assertion that the reply's result matches the test case's expected outcome.
 */
public interface JudgeProtocol
{
	/**
	 * Creates the judge session request for one trial. The driver sends the same request again when it reruns the judge
	 * after an invalid result.
	 *
	 * @param profile the contract's frozen judge profile
	 * @param testCase the trial's test case
	 * @param evidence the trial session's result, which the judge reads without changing it
	 * @param assertions the assertions of the trial's test that the judge decides
	 * @param results the results of the test's built-in tools, which the judge receives as evidence but cannot
	 *   override
	 * @param evidenceSection the rendered trial activity and file changes; empty when the frozen template does not
	 *   name it
	 * @return a request whose role is {@link SessionRole#JUDGE}
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code assertions} is empty
	 */
	SessionRequest request(JudgeProfile profile, DrawnTestCase testCase, SessionResult.Completed evidence,
		List<JudgedAssertion> assertions, List<BuiltInToolResult> results, String evidenceSection);

	/**
	 * Extracts the verdict, rationale and behavior of each assertion from a judge session, and derives the trial's
	 * verdict: it fails when the judge failed any assertion.
	 *
	 * @param result the judge session's result
	 * @param decided the assertions that the judge decided
	 * @return the judgment, or empty if the output does not match the judge's result schema, does not decide each
	 *   assertion of {@code decided} exactly once, decides another assertion, or names a behavior that its assertion
	 *   does not admit
	 * @throws NullPointerException if any of the arguments are null
	 */
	Optional<Judgment> judgment(SessionResult.Completed result, List<JudgedAssertion> decided);
}
