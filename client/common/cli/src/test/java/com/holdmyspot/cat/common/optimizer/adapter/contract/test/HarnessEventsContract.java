package com.holdmyspot.cat.common.optimizer.adapter.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.adapter.HarnessEvents;

import java.util.List;
import java.util.Optional;

/**
 * The turn state that every {@link HarnessEvents} reader must derive from its harness's event lines, one check per
 * kind of event.
 * <p>
 * A test class gives the contract a {@link Subject} that creates the reader under test and writes each kind of event
 * in its harness's format. The real readers' subjects take their lines from recordings of their harness; the generic
 * test harness's subject uses its own format. The checks are plain methods rather than inherited test methods because
 * the package that holds them is not opened to the test framework.
 */
public final class HarnessEventsContract
{
	private final Subject subject;

	/**
	 * Creates the contract.
	 *
	 * @param subject the reader under test and its harness's event lines
	 * @throws NullPointerException if {@code subject} is null
	 */
	public HarnessEventsContract(Subject subject)
	{
		requireThat(subject, "subject").isNotNull();
		this.subject = subject;
	}

	/**
	 * A reader under test and the event lines of its harness.
	 */
	public interface Subject
	{
		/**
		 * Creates a reader that has read no event.
		 *
		 * @return the reader
		 */
		HarnessEvents newEvents();

		/**
		 * Returns a line that reports model output in the current turn.
		 *
		 * @return the line
		 */
		String modelOutput();

		/**
		 * Returns a line that the model did not produce, such as the user's message.
		 *
		 * @return the line
		 */
		String withoutOutput();

		/**
		 * Returns a line that reports that a hook started.
		 *
		 * @param id the hook run's identifier
		 * @return the line
		 */
		String hookStarted(String id);

		/**
		 * Returns a line that reports that a hook completed.
		 *
		 * @param id the hook run's identifier
		 * @return the line
		 */
		String hookCompleted(String id);

		/**
		 * Returns the lines of a turn that the provider rejected for a rate limit without naming when the limit resets.
		 *
		 * @return the lines, in the order that the harness prints them
		 */
		List<String> rejection();

		/**
		 * Returns the lines of a turn whose request the provider refused and that the harness gave up on.
		 *
		 * @return the lines, in the order that the harness prints them
		 */
		List<String> refusal();
	}

	/**
	 * Checks that model output marks the turn as having produced output, that other lines do not, and that a new
	 * turn starts without output.
	 */
	public void modelOutputMarksTheTurnUntilTheNextTurn()
	{
		HarnessEvents events = subject.newEvents();
		requireThat(events.outputProduced(), "initial").isFalse();

		events.accept(subject.withoutOutput());
		requireThat(events.outputProduced(), "withoutOutput").isFalse();

		events.accept(subject.modelOutput());
		requireThat(events.outputProduced(), "modelOutput").isTrue();

		events.startTurn();
		requireThat(events.outputProduced(), "nextTurn").isFalse();
	}

	/**
	 * Checks that a hook runs from its start until its completion, that one hook's completion leaves another running,
	 * and that a running hook keeps running when a new turn starts.
	 */
	public void aHookRunsFromItsStartUntilItsCompletion()
	{
		HarnessEvents events = subject.newEvents();
		requireThat(events.hookRunning(), "initial").isFalse();

		events.accept(subject.hookStarted("first"));
		requireThat(events.hookRunning(), "firstStarted").isTrue();

		events.accept(subject.hookStarted("second"));
		events.accept(subject.hookCompleted("first"));
		requireThat(events.hookRunning(), "firstCompleted").isTrue();

		events.startTurn();
		requireThat(events.hookRunning(), "nextTurn").isTrue();

		events.accept(subject.hookCompleted("second"));
		requireThat(events.hookRunning(), "secondCompleted").isFalse();
	}

	/**
	 * Checks that a rate-limit rejection without a reset time is a rejection without a reset time, and not a refusal.
	 */
	public void reportsARejectionWithoutAResetTime()
	{
		HarnessEvents events = subject.newEvents();

		for (String line : subject.rejection())
			events.accept(line);

		requireThat(events.rejection(), "rejection").isEqualTo(Optional.of(new HarnessEvents.Rejection(null)));
		requireThat(events.refused(), "refused").isFalse();
	}

	/**
	 * Checks that a refused request is a refusal, and not a rate-limit rejection.
	 */
	public void reportsARefusal()
	{
		HarnessEvents events = subject.newEvents();

		for (String line : subject.refusal())
			events.accept(line);

		requireThat(events.refused(), "refused").isTrue();
		requireThat(events.rejection(), "rejection").isEqualTo(Optional.empty());
	}
}
