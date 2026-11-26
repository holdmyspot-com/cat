package com.holdmyspot.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.execution.ProcessSnapshot;
import com.holdmyspot.cat.common.optimizer.execution.StartupSample;
import com.holdmyspot.cat.common.optimizer.execution.StartupStage;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.testng.annotations.Test;

/**
 * Verifies that the stage of a check program's run comes from the programs of its process tree.
 */
public final class StartupStageTest
{
	private static final Path SANDBOX = Path.of("/usr/bin/bwrap");
	private static final Path ENV = Path.of("/usr/bin/env");
	private static final Path JAVA = Path.of("/opt/runtime/bin/java");
	private static final Duration AT = Duration.ofMillis(750);

	/**
	 * Creates the test.
	 */
	public StartupStageTest()
	{
	}

	/**
	 * A tree whose every program is the sandbox program is still in sandbox setup, and its processor time is the sum
	 * of its processes'.
	 */
	@Test
	public void treeOfSandboxProgramsIsSandboxSetup()
	{
		// Requirement: R-L23
		StartupSample sample = StartupSample.of(AT, List.of(snapshot(10, SANDBOX, 30), snapshot(11, SANDBOX, 20)),
			SANDBOX);

		requireThat(sample.stage(), "stage").isEqualTo(StartupStage.SANDBOX_SETUP);
		requireThat(sample.processIds(), "processIds").isEqualTo(Set.of(10L, 11L));
		requireThat(sample.processorTime(), "processorTime").isEqualTo(Optional.of(Duration.ofMillis(50)));
		requireThat(sample.at(), "at").isEqualTo(AT);
	}

	/**
	 * A tree that runs a program other than the sandbox program has been handed over by the sandbox: the next
	 * program of the chain, or the Java runtime, runs.
	 */
	@Test
	public void treeWithAnotherProgramIsLauncherStarting()
	{
		// Requirement: R-L23
		for (Path handedOverTo : List.of(ENV, JAVA))
		{
			StartupSample sample = StartupSample.of(AT, List.of(snapshot(10, SANDBOX, 30), snapshot(11, handedOverTo, 20)),
				SANDBOX);

			requireThat(sample.stage(), "stage").isEqualTo(StartupStage.LAUNCHER_STARTING);
		}
	}

	/**
	 * A program path that the host does not show makes the stage not observed, because the process could be any
	 * program. Missing processor time of a process leaves the total unknown.
	 */
	@Test
	public void hiddenProgramOrTimeIsNotObserved()
	{
		// Requirement: R-L23
		ProcessSnapshot hiddenProgram = new ProcessSnapshot(11, Optional.empty(), Optional.of(Duration.ofMillis(20)));
		ProcessSnapshot hiddenTime = new ProcessSnapshot(12, Optional.of(SANDBOX), Optional.empty());

		StartupSample program = StartupSample.of(AT, List.of(snapshot(10, SANDBOX, 30), hiddenProgram), SANDBOX);
		StartupSample time = StartupSample.of(AT, List.of(snapshot(10, SANDBOX, 30), hiddenTime), SANDBOX);

		requireThat(program.stage(), "programStage").isEqualTo(StartupStage.NOT_OBSERVED);
		requireThat(time.stage(), "timeStage").isEqualTo(StartupStage.SANDBOX_SETUP);
		requireThat(time.processorTime(), "processorTime").isEqualTo(Optional.empty());
	}

	/**
	 * A tree with no live process shows nothing.
	 */
	@Test
	public void emptyTreeIsNotObserved()
	{
		// Requirement: R-L23
		StartupSample sample = StartupSample.of(AT, List.of(), SANDBOX);

		requireThat(sample.stage(), "stage").isEqualTo(StartupStage.NOT_OBSERVED);
		requireThat(sample.processIds(), "processIds").isEmpty();
	}

	/**
	 * Creates a process that runs a program.
	 *
	 * @param processId the process ID
	 * @param program   the program
	 * @param cpuMillis the processor time used, in milliseconds
	 * @return the snapshot
	 */
	private static ProcessSnapshot snapshot(long processId, Path program, long cpuMillis)
	{
		return new ProcessSnapshot(processId, Optional.of(program), Optional.of(Duration.ofMillis(cpuMillis)));
	}
}
