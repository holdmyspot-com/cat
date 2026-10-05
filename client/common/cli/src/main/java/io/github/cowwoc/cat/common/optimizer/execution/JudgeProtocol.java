package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.contract.CheckResult;
import io.github.cowwoc.cat.common.optimizer.contract.JudgeProfile;
import io.github.cowwoc.cat.common.optimizer.contract.Requirement;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
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
 * judge only after every deterministic check of the trial passed and a requirement that needs the judge's
 * interpretation remains.
 */
public interface JudgeProtocol
{
	/**
	 * Creates the judge session request for one trial. The driver sends the same request again when it reruns the judge
	 * after an invalid result.
	 *
	 * @param profile the contract's frozen judge profile
	 * @param instance the trial's instance
	 * @param evidence the trial session's result, which the judge reads without changing it
	 * @param requirements the semantic requirements of the trial's test, which are the only requirements that the
	 *   judge decides
	 * @param results the results of the test's deterministic checks, which the judge receives as evidence but cannot
	 *   override
	 * @param evidenceSection the rendered trial activity and file changes; empty when the frozen template does not
	 *   name it
	 * @return a request whose role is {@link SessionRole#JUDGE}
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code requirements} is empty
	 */
	SessionRequest request(JudgeProfile profile, TrialInstance instance, SessionResult.Completed evidence,
		List<Requirement> requirements, List<CheckResult> results, String evidenceSection);

	/**
	 * Extracts the verdict, its rationale and the behavior of each failure from a judge session.
	 *
	 * @param result the judge session's result
	 * @param decided the requirements that the judge decided, which are the only requirements that a failure may name
	 * @return the judgment, or empty if the output does not match the judge's result schema, or a failure names a
	 *   requirement outside {@code decided} or a behavior that its requirement does not admit
	 * @throws NullPointerException if any of the arguments are null
	 */
	Optional<Judgment> judgment(SessionResult.Completed result, List<Requirement> decided);
}
