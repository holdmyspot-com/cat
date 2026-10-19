package com.holdmyspot.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.AssertionKind;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.Part;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.execution.GuardPilot;
import com.holdmyspot.cat.common.optimizer.execution.PilotResult;
import com.holdmyspot.cat.common.optimizer.report.EnumNames;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders the guard pilot's results: how often each assertion of each prompt test passed in control trials, which run
 * the incumbent with the target prompt files emptied. The proposal shows them to the user, and the contract author's
 * revision brief carries them.
 * <p>
 * <b>Thread Safety:</b> This class is stateless and thread-safe.
 */
final class PilotReport
{
	/**
	 * The number of significant digits of a pass rate.
	 */
	private static final MathContext PERCENT_DIGITS = new MathContext(3);
	/**
	 * The initial capacity of the rendered text, which holds a paragraph and a few lines per prompt test.
	 */
	private static final int INITIAL_CAPACITY = 2048;

	/**
	 * Prevents instantiation.
	 */
	private PilotReport()
	{
	}

	/**
	 * Returns the results of each prompt test's pilot, in contract order. For each assertion, the text shows how many
	 * trials passed it among those that decided it, and warns when a guard assertion passed in fewer than the minimum
	 * pass rate of those trials. A test without a pilot says why.
	 *
	 * @param contract the proposed contract that the pilots ran
	 * @param pilots the pilot of each prompt test that has one, by prompt test
	 * @return the Markdown text, which starts with a paragraph and holds one {@code ###} section per prompt test
	 * @throws NullPointerException if any of the arguments are null
	 */
	static String render(Contract contract, Map<String, PilotResult> pilots)
	{
		requireThat(contract, "contract").isNotNull();
		requireThat(pilots, "pilots").isNotNull();
		StringBuilder text = new StringBuilder(INITIAL_CAPACITY);
		text.append("Before presenting this proposal, CAT ran ").append(GuardPilot.TRIALS).
			append(" control trials of each prompt test in its weakest cell, drawing only development test cases, and " +
				"decided them with the proposed checks and judge. Control trials run the incumbent with the target " +
				"prompt files emptied. Pilot trials enter no count and spend no allowance.\n");
		for (PromptTest test : contract.tests())
		{
			PilotResult pilot = pilots.get(test.id());
			if (pilot == null)
			{
				text.append("\n### `").append(test.id()).append("`\n\nNo pilot: ").append(whyNoPilot(contract, test)).
					append('\n');
				continue;
			}
			test(text, contract, test, pilot);
		}
		return text.toString();
	}

	/**
	 * Appends the results of one prompt test's pilot.
	 *
	 * @param text the text to append to
	 * @param contract the proposed contract
	 * @param test the prompt test
	 * @param pilot the test's pilot
	 */
	private static void test(StringBuilder text, Contract contract, PromptTest test, PilotResult pilot)
	{
		text.append("\n### `").append(test.id()).append("` in cell `").append(pilot.cellId()).append("`\n\n").
			append(count(pilot.trials().size(), "trial", "trials")).append(" decided, ").
			append(count(pilot.failedRuns().size(), "failed run", "failed runs")).append(".\n");
		if (pilot.stopReason() != null)
		{
			text.append("\nThe pilot stopped before it finished: ").append(pilot.stopReason()).
				append(" Run `start` again to finish it.\n");
		}
		text.append('\n');
		BigDecimal minimumPassRate = contract.minimumPassRate();
		for (Assertion assertion : test.allAssertions())
		{
			String id = assertion.id();
			text.append("- `").append(id).append("` (").append(EnumNames.lowerCase(assertion.kind())).append("): ");
			int decided = pilot.decided(id);
			if (decided == 0)
			{
				text.append("decided in no trial\n");
				continue;
			}
			int passes = pilot.passes(id);
			text.append(passes).append(" of ").append(decided).append(" passed (").append(percent(passes, decided)).
				append("%)");
			if (assertion.kind() == AssertionKind.GUARD && pilot.isBelow(id, minimumPassRate))
			{
				text.append(". Warning: below the minimum pass rate of ").
					append(minimumPassRate.movePointRight(2).stripTrailingZeros().toPlainString()).
					append("%. A guard should pass almost always without the target prompts, so the assertion may be " +
						"misclassified, its test cases may be too hard, or the agent may really fail it without the " +
						"prompts. A control comparison on it needs many pairs or ends the certification attempt.");
			}
			text.append('\n');
		}
		if (pilot.failedRuns().isEmpty())
			return;
		// Each distinct reason once, in the order that the runs failed
		Map<String, Integer> runsByReason = new LinkedHashMap<>();
		for (String reason : pilot.failedRuns())
			runsByReason.merge(reason, 1, Integer::sum);
		text.append("\nWhy the failed runs ended without a decision:\n\n");
		for (Map.Entry<String, Integer> entry : runsByReason.entrySet())
		{
			text.append("- ").append(entry.getKey()).append(" (").append(count(entry.getValue(), "run", "runs")).
				append(")\n");
		}
	}

	/**
	 * Returns why a prompt test has no pilot.
	 *
	 * @param contract the proposed contract
	 * @param test the prompt test
	 * @return the reason, as a sentence
	 */
	private static String whyNoPilot(Contract contract, PromptTest test)
	{
		if (GuardPilot.pilotCell(contract, test.id()).isEmpty())
			return "its weakest cell depends on a model whose family is unranked.";
		if (contract.split().testCases(test, Part.DEVELOPMENT).isEmpty())
			return "it has no development test case.";
		return "CAT has not run it.";
	}

	/**
	 * Returns a count with its noun.
	 *
	 * @param count the count
	 * @param singular the noun for a count of one
	 * @param plural the noun for any other count
	 * @return text such as {@code 1 failed run} or {@code 0 failed runs}
	 */
	private static String count(int count, String singular, String plural)
	{
		if (count == 1)
			return count + " " + singular;
		return count + " " + plural;
	}

	/**
	 * Returns a pass rate as a percentage with three significant digits, keeping trailing zeros.
	 *
	 * @param passes the number of trials that passed
	 * @param decided the number of trials that decided the assertion, which is positive
	 * @return text such as {@code 75.0}, {@code 100} or {@code 0}
	 */
	private static String percent(int passes, int decided)
	{
		if (passes == 0)
			return "0";
		BigDecimal percent = BigDecimal.valueOf(100L * passes).divide(BigDecimal.valueOf(decided), PERCENT_DIGITS);
		percent = percent.setScale(Math.max(percent.scale(),
			percent.scale() + PERCENT_DIGITS.getPrecision() - percent.precision()));
		return percent.toPlainString();
	}
}
