package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The times that a launcher took to become ready in earlier runs of this process, so that a report of a run that did
 * not get there can say how long a start normally takes.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class StartupHistory
{
	/** The number of the most recent runs that a summary covers. */
	private static final int KEPT_RUNS = 20;
	private static final Map<List<Path>, StartupHistory> SHARED = new ConcurrentHashMap<>();
	private final Deque<Duration> timesToReady = new ArrayDeque<>();

	/**
	 * What earlier runs show.
	 *
	 * @param runs    the number of earlier runs that the figures cover
	 * @param median  the middle time to ready, and the slower of the two middle ones for an even number of runs
	 * @param slowest the longest time to ready
	 */
	public record Summary(int runs, Duration median, Duration slowest)
	{
	}

	/**
	 * Creates an empty history.
	 */
	public StartupHistory()
	{
	}

	/**
	 * Returns the history that every run with the same sandbox program and Java executable shares in this process.
	 *
	 * @param sandbox the path of the sandbox program
	 * @param java    the path of the Java executable
	 * @return the shared history
	 * @throws NullPointerException if any argument is null
	 */
	public static StartupHistory shared(Path sandbox, Path java)
	{
		requireThat(sandbox, "sandbox").isNotNull();
		requireThat(java, "java").isNotNull();
		return SHARED.computeIfAbsent(List.of(sandbox, java), key -> new StartupHistory());
	}

	/**
	 * Records that a run's launcher became ready.
	 *
	 * @param timeToReady the time from launching the sandbox to the launcher's ready line
	 * @throws NullPointerException if {@code timeToReady} is null
	 */
	public void recordReady(Duration timeToReady)
	{
		requireThat(timeToReady, "timeToReady").isNotNull();
		synchronized (timesToReady)
		{
			timesToReady.addLast(timeToReady);
			if (timesToReady.size() > KEPT_RUNS)
				timesToReady.removeFirst();
		}
	}

	/**
	 * Summarizes the most recent runs.
	 *
	 * @return the summary, or empty if no run reached ready
	 */
	public Optional<Summary> summary()
	{
		List<Duration> sorted;
		synchronized (timesToReady)
		{
			sorted = new ArrayList<>(timesToReady);
		}
		if (sorted.isEmpty())
			return Optional.empty();
		Collections.sort(sorted);
		return Optional.of(new Summary(sorted.size(), sorted.get(sorted.size() / 2), sorted.getLast()));
	}
}
