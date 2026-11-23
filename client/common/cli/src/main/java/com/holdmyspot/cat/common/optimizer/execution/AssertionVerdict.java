package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.FailedAssertion;
import com.holdmyspot.cat.common.optimizer.campaign.Verdict;

import java.util.ArrayList;
import java.util.List;

/**
 * A judge's decision of one assertion.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param assertion the assertion's identifier
 * @param behavior  the behavior through which the trial failed the assertion, or {@code null} if it passed
 * @param rationale the evidence that decided it, in the judge's words
 */
public record AssertionVerdict(String assertion, String behavior, String rationale)
{
	/**
	 * Creates a decision.
	 *
	 * @param assertion the assertion's identifier
	 * @param behavior  the behavior through which the trial failed the assertion, or {@code null} if it passed
	 * @param rationale the evidence that decided it, in the judge's words
	 * @throws NullPointerException     if {@code assertion} or {@code rationale} are null
	 * @throws IllegalArgumentException if {@code assertion} or {@code rationale} are blank, or {@code behavior} is
	 *                                  blank
	 */
	public AssertionVerdict
	{
		requireThat(assertion, "assertion").isNotBlank();
		requireThat(rationale, "rationale").isNotBlank();
		if (behavior != null)
			requireThat(behavior, "behavior").isNotBlank();
	}

	/**
	 * Returns the verdict on the assertion.
	 *
	 * @return {@link Verdict#FAIL} if the trial failed the assertion, otherwise {@link Verdict#PASS}
	 */
	public Verdict verdict()
	{
		if (behavior == null)
			return Verdict.PASS;
		return Verdict.FAIL;
	}

	/**
	 * Returns the judgment of a trial from the decision of each assertion that the judge decided: the trial fails
	 * when any assertion failed, and the rationale lists each decision in order.
	 *
	 * @param decisions the decisions, in the order that the rationale lists them
	 * @return the judgment
	 * @throws NullPointerException     if {@code decisions} is null
	 * @throws IllegalArgumentException if {@code decisions} is empty
	 */
	public static Judgment judgment(List<AssertionVerdict> decisions)
	{
		requireThat(decisions, "decisions").isNotEmpty();
		List<FailedAssertion> failures = new ArrayList<>();
		StringBuilder rationale = new StringBuilder();
		for (AssertionVerdict decision : decisions)
		{
			if (!rationale.isEmpty())
				rationale.append('\n');
			rationale.append("- `").append(decision.assertion()).append("`: ");
			if (decision.behavior() == null)
				rationale.append("pass. ");
			else
			{
				rationale.append("fail (").append(decision.behavior()).append("). ");
				failures.add(new FailedAssertion(decision.assertion(), decision.behavior()));
			}
			rationale.append(decision.rationale());
		}
		Verdict verdict;
		if (failures.isEmpty())
			verdict = Verdict.PASS;
		else
			verdict = Verdict.FAIL;
		return new Judgment(verdict, rationale.toString(), failures);
	}
}
