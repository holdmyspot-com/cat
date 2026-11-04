package com.holdmyspot.cat.claude.updater.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.updater.activation.CommandResult;
import com.holdmyspot.cat.common.updater.activation.HarnessCli;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Stands in for the recorded Claude Code program: records each argument vector and answers with scripted results.
 * <p>
 * <b>Thread Safety:</b> This class is not thread-safe.
 */
public final class RecordingCli implements HarnessCli
{
	private final Deque<CommandResult> answers;
	private final List<List<String>> calls = new ArrayList<>();

	/**
	 * Creates a fake program.
	 *
	 * @param answers the results to return, one per call, in call order
	 * @throws NullPointerException if {@code answers} is null
	 */
	public RecordingCli(List<CommandResult> answers)
	{
		this.answers = new ArrayDeque<>(answers);
	}

	@Override
	public CommandResult run(List<String> arguments)
	{
		requireThat(arguments, "arguments").isNotNull();
		calls.add(List.copyOf(arguments));
		requireThat(answers, "answers").isNotEmpty();
		return answers.removeFirst();
	}

	/**
	 * Returns the argument vectors that the program received.
	 *
	 * @return the calls in order
	 */
	public List<List<String>> calls()
	{
		return List.copyOf(calls);
	}
}
