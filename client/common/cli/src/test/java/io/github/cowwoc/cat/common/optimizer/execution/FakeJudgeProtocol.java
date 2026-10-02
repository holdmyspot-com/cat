package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;
import io.github.cowwoc.cat.common.optimizer.contract.CheckResult;
import io.github.cowwoc.cat.common.optimizer.contract.JudgeProfile;
import io.github.cowwoc.cat.common.optimizer.contract.Requirement;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.execution.contract.JudgeProtocolContract;
import io.github.cowwoc.cat.common.optimizer.execution.prompt.JudgePrompt;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link JudgeProtocol} whose judge repeats the trial's last response, so a test decides each verdict through the
 * trial session's response: {@code pass}, {@code fail}, or anything else for a result that does not match the
 * schema. The protocol records what each judge request received.
 * <p>
 * {@link JudgeProtocolContract} checks profile selection, read-only evidence, the nonempty-requirements precondition,
 * and malformed or absent final responses against {@link JudgePrompt}. The abbreviated request and literal verdict
 * words are specific to {@link DriverHarness}, whose judge session repeats the trial response.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class FakeJudgeProtocol implements JudgeProtocol
{
	/**
	 * The prefix of the judge's only turn, followed by the trial's last response.
	 */
	public static final String PREFIX = "Judge: ";
	/**
	 * The start of every rationale, followed by the trial's last response.
	 */
	public static final String RATIONALE = "The trial agent answered: ";
	private final List<JudgeCall> calls = new CopyOnWriteArrayList<>();

	/**
	 * Creates a protocol.
	 */
	public FakeJudgeProtocol()
	{
	}

	@Override
	public SessionRequest request(JudgeProfile profile, TrialInstance instance, SessionResult.Completed evidence,
		List<Requirement> requirements, List<CheckResult> results)
	{
		requireThat(requirements, "requirements").isNotEmpty();

		calls.add(new JudgeCall(instance, requirements, results));
		List<Path> readOnly = new ArrayList<>();
		readOnly.add(evidence.effects());
		Path transcriptDirectory = evidence.transcript().getParent();
		if (transcriptDirectory != null && !readOnly.contains(transcriptDirectory))
			readOnly.add(transcriptDirectory);
		return new SessionRequest(SessionRole.JUDGE, evidence.effects(), List.of(PREFIX +
			evidence.responses().getLast()), readOnly, Duration.ofMinutes(5),
			profile.profile());
	}

	@Override
	public Optional<Judgment> judgment(SessionResult.Completed result)
	{
		if (result.responses().isEmpty())
			return Optional.empty();
		String response = result.responses().getLast();
		return switch (response)
		{
			case "pass" -> Optional.of(new Judgment(Verdict.PASS, RATIONALE + response));
			case "fail" -> Optional.of(new Judgment(Verdict.FAIL, RATIONALE + response));
			default -> Optional.empty();
		};
	}

	/**
	 * Returns what each judge request received.
	 *
	 * @return the requests, in the order they were made
	 */
	public List<JudgeCall> calls()
	{
		return List.copyOf(calls);
	}

	/**
	 * What one judge request received.
	 *
	 * @param instance the judged trial's instance
	 * @param requirements the requirements that the judge was asked to decide
	 * @param results the deterministic results that the judge received as evidence
	 */
	public record JudgeCall(TrialInstance instance, List<Requirement> requirements, List<CheckResult> results)
	{
		/**
		 * Creates a record of a judge request.
		 *
		 * @param instance the judged trial's instance
		 * @param requirements the requirements that the judge was asked to decide
		 * @param results the deterministic results that the judge received as evidence
		 * @throws NullPointerException if {@code requirements} or {@code results} are null
		 */
		public JudgeCall
		{
			requirements = List.copyOf(requirements);
			results = List.copyOf(results);
		}
	}
}
