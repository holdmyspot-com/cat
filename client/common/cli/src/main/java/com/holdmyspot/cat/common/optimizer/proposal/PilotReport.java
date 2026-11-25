package com.holdmyspot.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.AssertionKind;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.Part;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfiles;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgram;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgramProblem;
import com.holdmyspot.cat.common.optimizer.execution.GuardPilot;
import com.holdmyspot.cat.common.optimizer.execution.PilotResult;
import com.holdmyspot.cat.common.optimizer.execution.PilotTrial;
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
 * <b>Thread Safety:</b> This class is thread-safe.
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
	 * @param controlRefused {@code true} if the control source cannot be built, so no control trial could run
	 * @return the Markdown text, which starts with a paragraph and holds one {@code ###} section per prompt test
	 * @throws NullPointerException if any of the arguments are null
	 */
	static String render(Contract contract, Map<String, PilotResult> pilots, boolean controlRefused)
	{
		requireThat(contract, "contract").isNotNull();
		requireThat(pilots, "pilots").isNotNull();
		StringBuilder text = new StringBuilder(INITIAL_CAPACITY);
		Map<Harness, TrialProfiles> scopes = contract.profiles().byEvaluator();
		boolean shared = scopes.size() > 1;
		text.append("Before presenting this proposal, CAT ran at least ").append(GuardPilot.TRIALS).
			append(" control trials of each prompt test in its weakest cell");
		if (shared)
			text.append(" on each applicable evaluator");
		text.append(", and at least ").append(GuardPilot.TRIALS_PER_CLASS).
			append(" of each of its boundary classes, drawing only development test cases, and decided them with the " +
				"proposed checks and judge. Control trials run the incumbent with the target prompt files emptied. " +
				"Pilot trials enter no count and spend no allowance.\n");
		for (PromptTest test : contract.tests())
		{
			if (!shared)
			{
				scopedTest(text, contract, test, contract.profiles(), pilots.get(test.id()), null, controlRefused);
				continue;
			}
			for (Map.Entry<Harness, TrialProfiles> scope : scopes.entrySet())
			{
				TrialProfiles local = scope.getValue();
				if (local.cellsOf(test.id()).isEmpty())
					continue;
				Harness evaluator = scope.getKey();
				PilotResult pilot = pilots.get(PilotResult.key(test.id(), evaluator));
				scopedTest(text, contract, test, local, pilot, evaluator, controlRefused);
			}
		}
		return text.toString();
	}

	/**
	 * Appends one applicable pilot or explains why it has no result.
	 *
	 * @param text the text to append to
	 * @param contract the proposed contract
	 * @param test the prompt test
	 * @param profiles the evaluator's trial profiles
	 * @param pilot the result, or {@code null} if the pilot has no result
	 * @param evaluator the evaluator that qualifies a shared result, or {@code null} for a single evaluator
	 * @param controlRefused {@code true} if the control source cannot be built
	 */
	private static void scopedTest(StringBuilder text, Contract contract, PromptTest test, TrialProfiles profiles,
		PilotResult pilot, Harness evaluator, boolean controlRefused)
	{
		if (pilot != null)
		{
			test(text, contract, test, pilot);
			return;
		}
		text.append("\n### `").append(test.id()).append('`');
		if (evaluator != null)
			text.append(" on ").append(evaluator.productName());
		text.append("\n\nNo pilot: ").append(whyNoPilot(contract, test, profiles, controlRefused)).append('\n');
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
		int infrastructureAttempts = 0;
		for (PilotTrial trial : pilot.trials())
			infrastructureAttempts += trial.infrastructureAttempts().size();
		text.append("\n### `").append(test.id()).append("` in cell `").append(pilot.cellId()).append("`\n\n").
			append(count(pilot.trials().size(), "trial", "trials")).append(" decided, ").
			append(pilot.failedTrials().size() + pilot.problemTrials()).append(" ended without a decision");
		if (infrastructureAttempts > 0)
		{
			text.append(", ").append(count(infrastructureAttempts, "infrastructure attempt", "infrastructure attempts")).
				append(" before the trials were decided");
		}
		text.append(".\n");
		if (pilot.paused())
			text.append("\nThe pilot paused before it finished: ").append(pilot.stopReason()).append('\n');
		else if (pilot.stopReason() != null)
		{
			text.append("\nThe pilot stopped before it finished: ").append(pilot.stopReason()).
				append(" The proposal cannot be confirmed until the pilot finishes: ").
				append("run `start` again to finish it.\n");
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
			failuresByClass(text, pilot, id);
		}
		checkProgramProblems(text, pilot);
		if (pilot.failedTrials().isEmpty())
			return;
		// Each distinct reason once, in the order the trials ended
		Map<String, Integer> trialsByReason = new LinkedHashMap<>();
		for (String reason : pilot.failedTrials())
			trialsByReason.merge(reason, 1, Integer::sum);
		text.append("\nWhy trials ended without a decision\n\n");
		for (Map.Entry<String, Integer> entry : trialsByReason.entrySet())
		{
			text.append("- ").append(entry.getKey()).append(" (").append(count(entry.getValue(), "trial", "trials")).
				append(")\n");
		}
	}

	/**
	 * Appends the trials that failed an assertion, by the boundary class of their test cases, so that an assertion that
	 * fails only on one kind of input shows where. A pilot whose test cases have no class lists nothing, and neither
	 * does a class in which no trial failed the assertion.
	 *
	 * @param text the text to append to
	 * @param pilot the test's pilot
	 * @param assertionId the assertion
	 */
	private static void failuresByClass(StringBuilder text, PilotResult pilot, String assertionId)
	{
		Map<String, Integer> decided = pilot.decidedByClass(assertionId);
		if (decided.keySet().stream().allMatch(String::isEmpty))
			return;
		for (Map.Entry<String, Integer> failures : pilot.failuresByClass(assertionId).entrySet())
		{
			String boundaryClass = failures.getKey();
			text.append("  - ");
			if (boundaryClass.isEmpty())
				text.append("no class");
			else
				text.append("class `").append(boundaryClass).append('`');
			text.append(": ").append(failures.getValue()).append(" of ").append(count(decided.get(boundaryClass), "trial",
				"trials")).append(" failed it\n");
		}
	}

	/**
	 * Appends the check programs whose two runs on a pilot trial had different outcomes or took too long, with the
	 * outcome, time and output of both runs, so that the user and the contract author can see why the program cannot be
	 * trusted.
	 *
	 * @param text  the text to append to
	 * @param pilot the test's pilot
	 */
	private static void checkProgramProblems(StringBuilder text, PilotResult pilot)
	{
		if (pilot.checkProgramProblems().isEmpty())
			return;
		text.append("\nCheck programs that cannot be trusted, which block confirmation until a revision fixes them\n\n");
		for (CheckProgramProblem problem : pilot.checkProgramProblems())
		{
			text.append("- ").append(problem.describe()).append('\n');
			run(text, 1, problem.first());
			run(text, 2, problem.second());
		}
	}

	/**
	 * Appends one run of a check program as a nested list item, with its output in a code block.
	 *
	 * @param text   the text to append to
	 * @param number the run's number, starting at 1
	 * @param run    the run
	 */
	private static void run(StringBuilder text, int number, CheckProgram.Result run)
	{
		text.append("  - Run ").append(number).append(": ").append(CheckProgramProblem.outcomeName(run.outcome())).
			append(" after ").append(CheckProgramProblem.seconds(run.elapsed()));
		String output = run.observed();
		if (output.isEmpty())
		{
			text.append(", with no output\n");
			return;
		}
		text.append(", with this output:\n\n");
		String fence = "`".repeat(Math.max(3, Markdown.longestBacktickRun(output) + 1));
		text.append("    ").append(fence).append('\n');
		for (String line : output.split("\n", -1))
			text.append("    ").append(line).append('\n');
		text.append("    ").append(fence).append("\n\n");
	}

	/**
	 * Returns why a prompt test has no pilot.
	 *
	 * @param contract the proposed contract
	 * @param test the prompt test
	 * @param profiles the evaluator's trial profiles
	 * @param controlRefused {@code true} if the control source cannot be built, so no control trial could run
	 * @return the reason, as a sentence
	 */
	private static String whyNoPilot(Contract contract, PromptTest test, TrialProfiles profiles, boolean controlRefused)
	{
		if (GuardPilot.pilotCell(profiles, test.id()).isEmpty())
			return "its weakest cell depends on a model whose family is unranked.";
		if (contract.split().testCases(test, Part.DEVELOPMENT).isEmpty())
			return "it has no development test case.";
		if (controlRefused)
			return "CAT cannot build the control source; the Control source section says why.";
		return "CAT has not run it.";
	}

	/**
	 * Returns a count with its noun.
	 *
	 * @param count the count
	 * @param singular the noun for a count of one
	 * @param plural the noun for any other count
	 * @return text such as {@code 1 trial} or {@code 0 trials}
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
