package com.holdmyspot.cat.common.runner.launcher.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import com.holdmyspot.cat.common.runner.launcher.ProcessTable;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.Test;

/**
 * The behaviour that every {@link ProcessTable} implementation must provide, on which the session launcher's
 * requester proof rests: a live process shows in every answer, with its parent, its descendants and its command line,
 * and a process that ended, or an earlier process whose identifier a live one reuses, shows in none. Each
 * implementation's test extends this class.
 */
public abstract class ProcessTableContract
{
	/**
	 * The arguments with which the fixture starts its leaf process.
	 */
	protected static final List<String> LEAF_ARGUMENTS = List.of("--hold", "process-table-contract");

	/**
	 * Creates the contract tests.
	 */
	protected ProcessTableContract()
	{
	}

	/**
	 * Indicates whether the table must report the arguments of a live process. A table that cannot read the command
	 * line of another process reports none, which makes every reader that needs them refuse.
	 *
	 * @return {@code true} if {@link ProcessTable#arguments} must report the arguments of the leaf
	 */
	protected boolean reportsArguments()
	{
		return true;
	}

	/**
	 * Starts a leaf process two generations below a root: the root's child is the leaf's parent.
	 *
	 * @return the processes and the table that reports them
	 * @throws IOException if the leaf cannot be started
	 */
	protected abstract ProcessTree start() throws IOException;

	/**
	 * Three generations of live processes and the table that reports them.
	 */
	protected interface ProcessTree extends AutoCloseable
	{
		/**
		 * Returns the table.
		 *
		 * @return the table
		 */
		ProcessTable table();

		/**
		 * Returns the leaf's grandparent.
		 *
		 * @return the root
		 */
		ProcessIdentity root();

		/**
		 * Returns the leaf's parent.
		 *
		 * @return the middle process
		 */
		ProcessIdentity middle();

		/**
		 * Returns the leaf, whose command line ends with {@link #LEAF_ARGUMENTS}.
		 *
		 * @return the leaf
		 */
		ProcessIdentity leaf();

		/**
		 * Ends the leaf and waits until it has ended.
		 *
		 * @throws IOException if the leaf does not end
		 */
		void endLeaf() throws IOException;

		/**
		 * Ends the leaf if it still runs.
		 *
		 * @throws IOException if the leaf does not end
		 */
		@Override
		void close() throws IOException;
	}

	/**
	 * Verifies that a live process shows in every answer: it is alive and listed, its parent and grandparent are its
	 * ancestors, it is among both ancestors' descendants, and its command line ends with its arguments, which a table
	 * that cannot read them ({@link #reportsArguments()}) may leave unreported but never reports wrongly.
	 *
	 * @throws IOException if the leaf cannot be started or ended
	 */
	@Test
	public void liveProcessShowsInEveryAnswer() throws IOException
	{
		try (ProcessTree tree = start())
		{
			ProcessTable table = tree.table();
			ProcessIdentity leaf = tree.leaf();

			requireThat(table.isAlive(leaf), "isAlive(leaf)").isTrue();
			requireThat(table.all(), "all()").contains(leaf);
			requireThat(table.parent(leaf), "parent(leaf)").isEqualTo(Optional.of(tree.middle()));
			requireThat(table.parent(tree.middle()), "parent(middle)").isEqualTo(Optional.of(tree.root()));
			requireThat(table.descendants(tree.root()), "descendants(root)").contains(tree.middle()).contains(leaf);
			requireThat(table.descendants(tree.middle()), "descendants(middle)").contains(leaf).
				doesNotContain(tree.root());
			Optional<List<String>> reported = table.arguments(leaf);
			if (reportsArguments())
				requireThat(reported, "arguments(leaf)").isPresent();
			if (reported.isPresent())
			{
				List<String> arguments = reported.get();
				requireThat(arguments.size(), "arguments.size()").isGreaterThanOrEqualTo(LEAF_ARGUMENTS.size());
				requireThat(arguments.subList(arguments.size() - LEAF_ARGUMENTS.size(), arguments.size()), "arguments").
					isEqualTo(LEAF_ARGUMENTS);
			}
		}
	}

	/**
	 * Verifies that a process that ended shows in no answer, so that the launcher takes a request whose holder ended
	 * for abandoned.
	 *
	 * @throws IOException if the leaf cannot be started or ended
	 */
	@Test
	public void endedProcessShowsInNoAnswer() throws IOException
	{
		try (ProcessTree tree = start())
		{
			ProcessTable table = tree.table();
			ProcessIdentity leaf = tree.leaf();

			tree.endLeaf();

			requireAbsent(table, leaf);
			requireThat(table.descendants(tree.middle()), "descendants(middle)").doesNotContain(leaf);
		}
	}

	/**
	 * Verifies that an earlier process whose identifier a live process has shows in no answer, so that the launcher
	 * never takes the live process for the earlier one.
	 *
	 * @throws IOException if the leaf cannot be started or ended
	 */
	@Test
	public void earlierProcessShowsInNoAnswer() throws IOException
	{
		try (ProcessTree tree = start())
		{
			ProcessTable table = tree.table();
			ProcessIdentity earlier = new ProcessIdentity(tree.leaf().pid(), tree.leaf().start().minusSeconds(1));

			requireAbsent(table, earlier);
			requireThat(table.descendants(tree.middle()), "descendants(middle)").doesNotContain(earlier);
		}
	}

	/**
	 * Requires that a process show in no answer.
	 *
	 * @param table   the table
	 * @param process the process
	 */
	private static void requireAbsent(ProcessTable table, ProcessIdentity process)
	{
		requireThat(table.isAlive(process), "isAlive(process)").isFalse();
		requireThat(table.all(), "all()").doesNotContain(process);
		requireThat(table.parent(process), "parent(process)").isEmpty();
		requireThat(table.arguments(process), "arguments(process)").isEmpty();
		requireThat(table.descendants(process), "descendants(process)").isEmpty();
	}
}
