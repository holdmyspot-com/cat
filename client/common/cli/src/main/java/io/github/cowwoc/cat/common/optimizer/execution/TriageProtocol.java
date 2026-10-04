package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.campaign.Impact;
import io.github.cowwoc.cat.common.optimizer.campaign.Triage;
import io.github.cowwoc.cat.common.optimizer.contract.CheckResult;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;

import java.util.List;
import java.util.Optional;

/**
 * Creates a triage session request from a failed development trial's evidence and parses the failure's
 * {@link Impact impact}, for the {@link Driver}.
 * <p>
 * The driver requests a triage after the trial's deterministic checks or its judge failed it, and records the trial's
 * outcome once the triage decides the impact.
 */
public interface TriageProtocol
{
	/**
	 * Creates the triage session request for one failed trial. The driver sends the same request again when it reruns
	 * the triage after an invalid result.
	 *
	 * @param profile the harness's role profile, under which the session runs
	 * @param instance the trial's instance
	 * @param evidence the trial session's result, which the triage reads without changing it
	 * @param results the results of the test's deterministic checks; empty if the test has none or a check could not
	 *   run before the failure
	 * @param evidenceSection the rendered trial activity and file changes
	 * @param failure the judge's rationale for the failure, or the record of each failed deterministic check
	 * @return a request whose role is {@link SessionRole#TRIAGE}
	 * @throws NullPointerException if any of the arguments are null
	 */
	SessionRequest request(TrialProfile profile, TrialInstance instance, SessionResult.Completed evidence,
		List<CheckResult> results, String evidenceSection, String failure);

	/**
	 * Extracts the impact and its rationale from a triage session.
	 *
	 * @param result the triage session's result
	 * @return the impact, or empty if the output does not match the triage's result schema
	 * @throws NullPointerException if {@code result} is null
	 */
	Optional<Triage> triage(SessionResult.Completed result);
}
