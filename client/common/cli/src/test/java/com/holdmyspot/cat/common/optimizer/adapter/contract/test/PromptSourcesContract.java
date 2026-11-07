package com.holdmyspot.cat.common.optimizer.adapter.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.adapter.PromptSources;

import java.util.List;

/**
 * The observation that every {@link PromptSources} reader must make of its harness's event lines.
 * <p>
 * A test class gives the contract a {@link Subject} that creates the reader under test and writes event lines in its
 * harness's format. The checks are plain methods rather than inherited test methods because the package that holds
 * them is not opened to the test framework.
 */
public final class PromptSourcesContract
{
	private final Subject subject;

	/**
	 * Creates the contract.
	 *
	 * @param subject the reader under test and its harness's event lines
	 * @throws NullPointerException if {@code subject} is null
	 */
	public PromptSourcesContract(Subject subject)
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
		PromptSources newSources();

		/**
		 * Returns lines of a session that loaded no prompt source.
		 *
		 * @return the lines, in the order that the harness prints them
		 */
		List<String> loadingNothing();
	}

	/**
	 * Checks that a session that loaded no prompt source records the explicit value {@code none}, so that the
	 * observation is never blank.
	 */
	public void recordsAnExplicitValueWhenNothingLoads()
	{
		PromptSources sources = subject.newSources();

		for (String line : subject.loadingNothing())
			sources.accept(line);

		requireThat(sources.value(), "value").isEqualTo("none");
	}
}
