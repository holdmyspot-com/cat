package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.runner.ProcessTreeStop;
import com.holdmyspot.cat.common.test.FakeProcessHandle;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests that a runner launcher that starts a session itself stops the session's whole process tree when it ends,
 * through {@link ProcessTreeStop}.
 */
public final class ProcessTreeStopTest
{
	/**
	 * Creates the test.
	 */
	public ProcessTreeStopTest()
	{
	}

	/**
	 * Verifies that the launcher stops nothing while it runs, and that when it ends it asks every descendant to stop:
	 * its children and their children.
	 */
	@Test
	public void stopsEveryDescendantOnExit()
	{
		FakeProcessHandle grandchild = new FakeProcessHandle(30, "start-30", true);
		FakeProcessHandle child = new FakeProcessHandle(20, "start-20", true, grandchild);
		FakeProcessHandle sibling = new FakeProcessHandle(21, "start-21", true);
		FakeProcessHandle launcher = new FakeProcessHandle(10, "start-10", true, child, sibling);
		List<Runnable> hooks = new ArrayList<>();

		ProcessTreeStop.onExit(hooks::add, launcher);

		requireThat(hooks.size(), "hooks").isEqualTo(1);
		requireThat(child.stopRequested() || grandchild.stopRequested() || sibling.stopRequested(),
			"stoppedBeforeExit").isFalse();
		hooks.getFirst().run();
		requireThat(child.stopRequested(), "child").isTrue();
		requireThat(grandchild.stopRequested(), "grandchild").isTrue();
		requireThat(sibling.stopRequested(), "sibling").isTrue();
		requireThat(launcher.stopRequested(), "launcher").isFalse();
	}
}
