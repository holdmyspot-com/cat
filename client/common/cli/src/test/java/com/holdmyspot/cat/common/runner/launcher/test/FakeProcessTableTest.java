package com.holdmyspot.cat.common.runner.launcher.test;

import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import com.holdmyspot.cat.common.runner.launcher.ProcessTable;
import java.util.List;
import java.util.Optional;

/**
 * Runs the {@link ProcessTable} contract against {@link FakeProcessTable}, so that the tests that hold their processes
 * in it see what the operating system's table would report.
 */
public final class FakeProcessTableTest extends ProcessTableContract
{
	/**
	 * Creates the test.
	 */
	public FakeProcessTableTest()
	{
	}

	@Override
	protected ProcessTree start()
	{
		FakeProcessTable table = new FakeProcessTable();
		ProcessIdentity root = table.start(1, Optional.empty(), List.of("root"));
		ProcessIdentity middle = table.start(2, Optional.of(root), List.of("middle"));
		ProcessIdentity leaf = table.start(3, Optional.of(middle), LEAF_ARGUMENTS);
		return new FakeTree(table, root, middle, leaf);
	}

	/**
	 * Three generations of processes in a fake table.
	 *
	 * @param table  the table
	 * @param root   the leaf's grandparent
	 * @param middle the leaf's parent
	 * @param leaf   the leaf
	 */
	private record FakeTree(FakeProcessTable table, ProcessIdentity root, ProcessIdentity middle,
		ProcessIdentity leaf) implements ProcessTree
	{
		@Override
		public void endLeaf()
		{
			table.end(leaf);
		}

		@Override
		public void close()
		{
			table.end(leaf);
		}
	}
}
