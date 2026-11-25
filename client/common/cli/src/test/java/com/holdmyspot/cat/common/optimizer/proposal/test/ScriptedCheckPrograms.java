package com.holdmyspot.cat.common.optimizer.proposal.test;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.contract.TrialRecord;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgram;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgramEvaluation;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stands in for the check program runner, which compiles and runs each program in a new JVM inside Bubblewrap. Its
 * runs of a program on an example are the scripted runs of the example's first reply, and it records each call.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class ScriptedCheckPrograms implements CheckProgramEvaluation
{
	/**
	 * How long a scripted run takes unless a test says otherwise.
	 */
	public static final Duration QUICK = Duration.ofMillis(20);
	private final Map<String, List<CheckProgram.Result>> runsByReply;
	private final List<Call> calls = new CopyOnWriteArrayList<>();

	/**
	 * One call of the runner.
	 *
	 * @param testId       the prompt test
	 * @param assertionIds the assertions whose programs were to run
	 * @param testCase     the test case that fills the programs' arguments
	 * @param replies      the replies of the record
	 * @param checkFiles   the text of each check file of the test, by its path below {@code checks}
	 * @param runs         the number of runs of each program
	 */
	public record Call(String testId, Set<String> assertionIds, TestCase testCase, List<List<String>> replies,
		Map<String, String> checkFiles, int runs)
	{
	}

	/**
	 * Creates a runner.
	 *
	 * @param runsByReply the runs of every program on a record, by the record's first reply; a record with another
	 *                    first reply has two failing runs
	 */
	public ScriptedCheckPrograms(Map<String, List<CheckProgram.Result>> runsByReply)
	{
		this.runsByReply = Map.copyOf(runsByReply);
	}

	/**
	 * Returns a quick run.
	 *
	 * @param outcome  the run's outcome
	 * @param observed the run's output
	 * @return the run
	 */
	public static CheckProgram.Result run(CheckProgram.Outcome outcome, String observed)
	{
		return new CheckProgram.Result(outcome, observed, QUICK);
	}

	/**
	 * Returns two quick runs with the same outcome and different outputs.
	 *
	 * @param outcome the outcome of both runs
	 * @param name    names the example in the outputs
	 * @return the runs
	 */
	public static List<CheckProgram.Result> agreeing(CheckProgram.Outcome outcome, String name)
	{
		return List.of(run(outcome, "first run on " + name), run(outcome, "second run on " + name));
	}

	@Override
	public Map<String, List<CheckProgram.Result>> run(PromptTest test, Set<String> assertionIds, TestCase testCase,
		TrialRecord record, int runs)
	{
		Map<String, String> checkFiles = new TreeMap<>();
		for (Map.Entry<String, FileContent> file : test.draft().orElseThrow().checkFiles().entrySet())
			checkFiles.put(file.getKey(), new String(file.getValue().bytes(), UTF_8));
		calls.add(new Call(test.id(), Set.copyOf(assertionIds), testCase, record.replies(), checkFiles, runs));
		String reply = record.replies().getFirst().getFirst();
		List<CheckProgram.Result> scripted = runsByReply.getOrDefault(reply,
			agreeing(CheckProgram.Outcome.FAIL, "an unscripted reply"));
		Map<String, List<CheckProgram.Result>> results = new LinkedHashMap<>();
		for (String assertionId : assertionIds)
			results.put(assertionId, scripted);
		return results;
	}

	/**
	 * Returns the calls so far.
	 *
	 * @return the calls, in call order
	 */
	public List<Call> calls()
	{
		return List.copyOf(calls);
	}
}
