package com.holdmyspot.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.HarnessProcessTermination;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;

/**
 * Tests which processes stopping a child's process tree stops, from a snapshot of the process table.
 */
public final class HarnessProcessTerminationTest
{
	/**
	 * Creates the test fixture.
	 */
	public HarnessProcessTerminationTest()
	{
	}

	/**
	 * Verifies that stopping a child stops the child, its children and their children, each after its parent, and no
	 * process outside its tree: not its parent, its siblings, or their children.
	 */
	@Test
	public void stopsChildAndItsDescendantsOnly()
	{
		// 1 runs the run's runner, which started the requested runner 2 and its sibling 5
		Map<Long, Long> parents = Map.of(2L, 1L, 3L, 2L, 4L, 3L, 5L, 1L, 6L, 5L, 7L, 2L);

		List<Long> tree = HarnessProcessTermination.tree(2, parents);

		requireThat(tree.getFirst(), "first").isEqualTo(2L);
		requireThat(tree, "tree").containsExactly(List.of(2L, 3L, 4L, 7L));
		requireThat(tree.indexOf(4L), "grandchild").isGreaterThan(tree.indexOf(3L));
	}

	/**
	 * Verifies that a child without descendants stops alone.
	 */
	@Test
	public void stopsLoneChild()
	{
		requireThat(HarnessProcessTermination.tree(9, Map.of(9L, 1L, 2L, 1L)), "tree").isEqualTo(List.of(9L));
	}

	/**
	 * Verifies that a snapshot whose reused identifiers link a process back to the child's tree still names each
	 * process once.
	 */
	@Test
	public void namesEachProcessOnceWhenSnapshotLoops()
	{
		requireThat(HarnessProcessTermination.tree(2, Map.of(2L, 3L, 3L, 2L)), "tree").isEqualTo(List.of(2L, 3L));
	}
}
