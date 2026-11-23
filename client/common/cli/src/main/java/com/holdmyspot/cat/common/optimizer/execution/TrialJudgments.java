package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.FailedAssertion;
import com.holdmyspot.cat.common.optimizer.campaign.ShadowResult;
import com.holdmyspot.cat.common.optimizer.campaign.Verdict;
import com.holdmyspot.cat.common.optimizer.contract.EvidenceScope;
import com.holdmyspot.cat.common.optimizer.contract.JudgeProfile;
import com.holdmyspot.cat.common.optimizer.contract.JudgedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.execution.InFlight.Judging;
import com.holdmyspot.cat.common.optimizer.execution.InFlight.Pending;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The judging of one completed trial, whose judged assertions are decided one evidence scope at a time: each scope
 * that has an assertion to decide gets its own judge sessions, and the trial completes once every scope is decided,
 * with one verdict and one comparison per candidate judge.
 * <p>
 * Every scope is judged by the same judges in the same seats, chosen once for the trial, so that a candidate that
 * shadows the trial shadows each of its scopes' sessions and the trial counts once for it.
 * <p>
 * <b>Thread Safety:</b> This class is not thread-safe. The driver's thread is its only user.
 */
final class TrialJudgments
{
	private final Pending pending;
	private final JudgeProfile decider;
	private final Judging.Seat seat;
	private final List<TrialProfile> candidates;
	private final boolean spotCheck;
	private final boolean heldOut;
	private final Map<EvidenceScope, List<JudgedAssertion>> byScope = new EnumMap<>(EvidenceScope.class);
	private final Map<EvidenceScope, Decided> decided = new EnumMap<>(EvidenceScope.class);
	private boolean abandoned;

	/**
	 * Creates the judging of a trial whose scopes are not decided yet.
	 *
	 * @param pending    the completed trial
	 * @param decider    the judge whose verdicts count
	 * @param seat       the deciding judge's part in the trial's verdict
	 * @param candidates the candidates that judge each scope next to the frozen judge, in the order they start; empty
	 *                   if one judge decides
	 * @param spotCheck  {@code true} if the candidates spot-check a qualified candidate instead of shadowing it
	 * @param heldOut    {@code true} if the trial is not a development trial
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if the trial has no judged assertion
	 */
	TrialJudgments(Pending pending, JudgeProfile decider, Judging.Seat seat, List<TrialProfile> candidates,
		boolean spotCheck, boolean heldOut)
	{
		requireThat(pending, "pending").isNotNull();
		requireThat(decider, "decider").isNotNull();
		requireThat(seat, "seat").isNotNull();
		requireThat(candidates, "candidates").isNotNull();
		requireThat(pending.assertions(), "pending.assertions()").isNotEmpty();
		this.pending = pending;
		this.decider = decider;
		this.seat = seat;
		this.candidates = List.copyOf(candidates);
		this.spotCheck = spotCheck;
		this.heldOut = heldOut;
		for (JudgedAssertion assertion : pending.assertions())
			byScope.computeIfAbsent(assertion.scope(), _ -> new ArrayList<>()).add(assertion);
	}

	/**
	 * Returns the completed trial.
	 *
	 * @return the trial
	 */
	Pending pending()
	{
		return pending;
	}

	/**
	 * Returns the judge whose verdicts count.
	 *
	 * @return the judge
	 */
	JudgeProfile decider()
	{
		return decider;
	}

	/**
	 * Returns the deciding judge's part in the trial's verdict.
	 *
	 * @return the seat
	 */
	Judging.Seat seat()
	{
		return seat;
	}

	/**
	 * Returns the trial's judged assertions by scope.
	 *
	 * @return the assertions of each scope that has any, the {@code response} scope first, each in the order of its
	 *   test
	 */
	Map<EvidenceScope, List<JudgedAssertion>> byScope()
	{
		return new LinkedHashMap<>(byScope);
	}

	/**
	 * Returns a new group of the frozen judge and the trial's candidates, for the sessions of one scope.
	 *
	 * @param frozenProfile the frozen judge's profile
	 * @return the group, or {@code null} if one judge decides the trial
	 */
	ShadowGroup newGroup(TrialProfile frozenProfile)
	{
		if (candidates.isEmpty())
			return null;
		return new ShadowGroup(frozenProfile, candidates, spotCheck, heldOut);
	}

	/**
	 * Drops the trial's judging because a judge could not decide one of its scopes, so that the trial reruns and the
	 * other scopes' results are ignored.
	 */
	void abandon()
	{
		abandoned = true;
	}

	/**
	 * Indicates whether a judge's failure dropped the trial's judging.
	 *
	 * @return {@code true} if the judging is abandoned
	 */
	boolean isAbandoned()
	{
		return abandoned;
	}

	/**
	 * Records the verdict of one scope.
	 *
	 * @param scope    the scope
	 * @param judgment the verdict that counts for the scope's assertions
	 * @param judge    the profile of the judge whose verdict counts
	 * @param shadows  the comparisons of candidate judges with the frozen judge on the scope, empty if none judged it
	 * @return {@code true} if every scope of the trial is decided
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if the trial has no assertion of the scope
	 */
	boolean decide(EvidenceScope scope, Judgment judgment, TrialProfile judge, List<ShadowResult> shadows)
	{
		requireThat(scope, "scope").isNotNull();
		requireThat(judgment, "judgment").isNotNull();
		requireThat(judge, "judge").isNotNull();
		requireThat(shadows, "shadows").isNotNull();
		requireThat(byScope.containsKey(scope), "byScope.containsKey(scope)").withContext(scope, "scope").isTrue();
		decided.put(scope, new Decided(judgment, judge, List.copyOf(shadows)));
		return decided.keySet().equals(byScope.keySet());
	}

	/**
	 * Returns the trial's verdict: it fails when any scope failed, and the rationale and the failures list each
	 * scope's in turn, the {@code response} scope first.
	 *
	 * @return the verdict
	 * @throws IllegalStateException if a scope is not decided
	 */
	Judgment judgment()
	{
		requireDecided();
		List<Judgment> judgments = new ArrayList<>();
		for (Decided scope : decided.values())
			judgments.add(scope.judgment());
		return merged(judgments);
	}

	/**
	 * Returns the profile of the judge whose verdict counts for the trial.
	 *
	 * @param frozenProfile the frozen judge's profile
	 * @return the profile that decided every scope, or {@code frozenProfile} if the scopes were decided by different
	 *   judges, which happens only when the frozen judge replaced a failed candidate
	 * @throws IllegalStateException if a scope is not decided
	 */
	TrialProfile judge(TrialProfile frozenProfile)
	{
		requireDecided();
		TrialProfile judge = null;
		for (Decided scope : decided.values())
		{
			if (judge == null)
				judge = scope.judge();
			else if (!judge.equals(scope.judge()))
				return frozenProfile;
		}
		return judge;
	}

	/**
	 * Returns the comparison of each candidate with the frozen judge on the trial. A candidate that disagreed with
	 * the frozen judge in any scope disagreed on the trial, which counts once whatever number of its scopes the
	 * candidate judged.
	 *
	 * @return one comparison per candidate, in the order the candidates started; empty if no candidate judged a scope
	 * @throws IllegalStateException if a scope is not decided
	 */
	List<ShadowResult> shadows()
	{
		requireDecided();
		Map<TrialProfile, ShadowResult> byCandidate = new LinkedHashMap<>();
		for (Decided scope : decided.values())
		{
			for (ShadowResult result : scope.shadows())
			{
				byCandidate.merge(result.candidate(), result, (first, second) -> new ShadowResult(first.candidate(),
					first.spotCheck(), first.heldOut(), first.lenient() || second.lenient(),
					first.strict() || second.strict()));
			}
		}
		return List.copyOf(byCandidate.values());
	}

	/**
	 * Returns the verdict of a trial from the verdicts of its parts: it fails when any part failed, and the
	 * rationale and the failures list each part's in turn.
	 *
	 * @param judgments the parts' verdicts, in order
	 * @return the verdict
	 * @throws NullPointerException     if {@code judgments} is null
	 * @throws IllegalArgumentException if {@code judgments} is empty
	 */
	static Judgment merged(List<Judgment> judgments)
	{
		requireThat(judgments, "judgments").isNotEmpty();
		if (judgments.size() == 1)
			return judgments.getFirst();
		Verdict verdict = Verdict.PASS;
		StringBuilder rationale = new StringBuilder();
		List<FailedAssertion> failures = new ArrayList<>();
		for (Judgment judgment : judgments)
		{
			if (judgment.verdict() == Verdict.FAIL)
				verdict = Verdict.FAIL;
			if (!rationale.isEmpty())
				rationale.append('\n');
			rationale.append(judgment.rationale());
			failures.addAll(judgment.failures());
		}
		return new Judgment(verdict, rationale.toString(), failures);
	}

	/**
	 * Throws if a scope is not decided.
	 *
	 * @throws IllegalStateException if a scope is not decided
	 */
	private void requireDecided()
	{
		if (!decided.keySet().equals(byScope.keySet()))
			throw new IllegalStateException("Decided scopes " + decided.keySet() + " of " + byScope.keySet());
	}

	/**
	 * The verdict of one scope.
	 *
	 * @param judgment the verdict that counts for the scope's assertions
	 * @param judge    the profile of the judge whose verdict counts
	 * @param shadows  the comparisons of candidate judges with the frozen judge on the scope
	 */
	private record Decided(Judgment judgment, TrialProfile judge, List<ShadowResult> shadows)
	{
	}
}
