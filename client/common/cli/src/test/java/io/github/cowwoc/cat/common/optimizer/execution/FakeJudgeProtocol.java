package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;
import io.github.cowwoc.cat.common.optimizer.contract.JudgeProfile;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * A {@link JudgeProtocol} whose judge repeats the trial's last response, so a test decides each verdict through the
 * trial session's response: {@code pass}, {@code fail}, or anything else for a result that does not match the
 * schema.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class FakeJudgeProtocol implements JudgeProtocol
{
	/**
	 * The prefix of the judge's only turn, followed by the trial's last response.
	 */
	public static final String PREFIX = "Judge: ";

	/**
	 * Creates a protocol.
	 */
	public FakeJudgeProtocol()
	{
	}

	@Override
	public SessionRequest request(JudgeProfile profile, TrialInstance instance, SessionResult.Completed evidence)
	{
		return new SessionRequest(SessionRole.JUDGE, evidence.effects(), List.of(PREFIX +
			evidence.responses().getLast()), List.of(evidence.transcript()), Duration.ofMinutes(5));
	}

	@Override
	public Optional<Verdict> verdict(SessionResult.Completed result)
	{
		return switch (result.responses().getLast())
		{
			case "pass" -> Optional.of(Verdict.PASS);
			case "fail" -> Optional.of(Verdict.FAIL);
			default -> Optional.empty();
		};
	}
}
