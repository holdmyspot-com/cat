package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;
import io.github.cowwoc.cat.common.optimizer.contract.JudgeProfile;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;

import java.util.Optional;

/**
 * Turns a trial's evidence into a judge session and the judge's output into a verdict, for the {@link Driver}.
 * <p>
 * This is the seam that the judge prompt fills: the driver supplies the contract's frozen judge profile as data and
 * never writes prompt text itself.
 */
public interface JudgeProtocol
{
	/**
	 * Returns the judge session for one trial. The driver sends the same request again when it reruns the judge
	 * after an invalid result.
	 *
	 * @param profile the contract's frozen judge profile
	 * @param instance the trial's instance
	 * @param evidence the trial session's result, which the judge reads without changing it
	 * @return a request whose role is {@link SessionRole#JUDGE}
	 * @throws NullPointerException if any of the arguments are null
	 */
	SessionRequest request(JudgeProfile profile, TrialInstance instance, SessionResult.Completed evidence);

	/**
	 * Extracts the verdict from a judge session.
	 *
	 * @param result the judge session's result
	 * @return the verdict, or empty if the output does not match the judge's result schema
	 * @throws NullPointerException if {@code result} is null
	 */
	Optional<Verdict> verdict(SessionResult.Completed result);
}
