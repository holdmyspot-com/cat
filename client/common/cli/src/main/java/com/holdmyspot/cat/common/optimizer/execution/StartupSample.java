package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * One look at a check program's process tree while its source launcher is not ready.
 *
 * @param at             the time since the sandbox was launched
 * @param stage          the stage that the programs of the tree show
 * @param processIds     the IDs of the tree's processes
 * @param processorTime  the processor time that the whole tree has used, or empty if the host does not show it for a
 *                       process of the tree
 */
public record StartupSample(Duration at, StartupStage stage, Set<Long> processIds, Optional<Duration> processorTime)
{
	/**
	 * Creates a sample.
	 *
	 * @param at            the time since the sandbox was launched
	 * @param stage         the stage that the programs of the tree show
	 * @param processIds    the IDs of the tree's processes
	 * @param processorTime the processor time that the whole tree has used, or empty if the host does not show it
	 * @throws NullPointerException if any argument is null or {@code processIds} holds null
	 */
	public StartupSample
	{
		requireThat(at, "at").isNotNull();
		requireThat(stage, "stage").isNotNull();
		processIds = Set.copyOf(processIds);
		requireThat(processorTime, "processorTime").isNotNull();
	}

	/**
	 * Decides the stage of a process tree and totals its processor time.
	 *
	 * @param at      the time since the sandbox was launched
	 * @param tree    the root process and its descendants
	 * @param sandbox the path of the sandbox program
	 * @return the sample
	 * @throws NullPointerException if any argument is null or {@code tree} holds null
	 */
	public static StartupSample of(Duration at, List<ProcessSnapshot> tree, Path sandbox)
	{
		requireThat(at, "at").isNotNull();
		requireThat(tree, "tree").isNotNull();
		requireThat(sandbox, "sandbox").isNotNull();
		if (tree.isEmpty())
			return new StartupSample(at, StartupStage.NOT_OBSERVED, Set.of(), Optional.empty());

		Set<Long> processIds = new HashSet<>();
		boolean programsKnown = true;
		boolean onlySandbox = true;
		boolean timeKnown = true;
		Duration total = Duration.ZERO;
		for (ProcessSnapshot process : tree)
		{
			processIds.add(process.processId());
			if (process.program().isEmpty())
				programsKnown = false;
			else if (!process.program().get().equals(sandbox))
				onlySandbox = false;
			if (process.cpuTime().isEmpty())
				timeKnown = false;
			else
				total = total.plus(process.cpuTime().get());
		}
		StartupStage stage;
		if (!programsKnown)
			stage = StartupStage.NOT_OBSERVED;
		else if (onlySandbox)
			stage = StartupStage.SANDBOX_SETUP;
		else
			stage = StartupStage.LAUNCHER_STARTING;
		Optional<Duration> processorTime = Optional.empty();
		if (timeKnown)
			processorTime = Optional.of(total);
		return new StartupSample(at, stage, processIds, processorTime);
	}

	/**
	 * Looks at a running process and its descendants.
	 *
	 * @param root    the sandbox process that CAT launched
	 * @param at      the time since the sandbox was launched
	 * @param sandbox the path of the sandbox program
	 * @return the sample
	 * @throws NullPointerException if any argument is null
	 */
	public static StartupSample observe(ProcessHandle root, Duration at, Path sandbox)
	{
		requireThat(root, "root").isNotNull();
		List<ProcessSnapshot> tree = Stream.concat(Stream.of(root), root.descendants()).filter(ProcessHandle::isAlive).
			map(ProcessSnapshot::of).toList();
		return of(at, tree, sandbox);
	}
}
