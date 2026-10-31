package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The concurrency windows of the units of work that adopted an outcome, and the halving of every window after runs
 * that ended without an outcome.
 * <p>
 * Each run of a provider error, crash, rate-limit rejection or refusal halves every window, down to 1. A full window
 * of trials that complete with an outcome, counted against the campaign's window when each completes, undoes one
 * halving.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param windows the window of each unit of work that adopted an outcome; a unit without one uses its starting window
 * @param halvings the halvings in effect, at most {@value #MAXIMUM_HALVINGS}
 * @param completions the trials completed with an outcome since the last halving or the last undone halving
 */
public record ConcurrencyWindows(Map<WorkUnit, Window> windows, int halvings, int completions)
{
	/**
	 * The halvings after which every window is 1.
	 */
	public static final int MAXIMUM_HALVINGS = 4;
	/**
	 * No window and no halving.
	 */
	public static final ConcurrencyWindows EMPTY = new ConcurrencyWindows(Map.of(), 0, 0);

	/**
	 * Creates concurrency windows.
	 *
	 * @param windows the window of each unit of work that adopted an outcome
	 * @param halvings the halvings in effect
	 * @param completions the trials completed with an outcome since the last halving or the last undone halving
	 * @throws NullPointerException if {@code windows} is null or contains null
	 * @throws IllegalArgumentException if {@code halvings} is outside [0, {@value #MAXIMUM_HALVINGS}] or
	 *   {@code completions} is negative
	 */
	public ConcurrencyWindows
	{
		requireThat(windows, "windows").isNotNull();
		requireThat(halvings, "halvings").isBetween(0, true, MAXIMUM_HALVINGS, true);
		requireThat(completions, "completions").isNotNegative();
		windows = Map.copyOf(windows);
	}

	/**
	 * Returns a unit's window, or its starting window if it has adopted no outcome.
	 *
	 * @param unit the unit of work
	 * @param spare the further failures that the unit can absorb before the next one decides it, used when it has no
	 *   window
	 * @param ceiling the campaign's ceiling
	 * @return the window
	 * @throws NullPointerException if {@code unit} is null
	 */
	public Window window(WorkUnit unit, int spare, int ceiling)
	{
		requireThat(unit, "unit").isNotNull();
		Window window = windows.get(unit);
		if (window == null)
			return Window.start(spare, ceiling);
		return window;
	}

	/**
	 * Returns the most trials of a unit that may run at once: its window, at most the ceiling, halved once for each
	 * halving in effect and at least 1.
	 *
	 * @param unit the unit of work
	 * @param spare the further failures that the unit can absorb before the next one decides it, used when it has no
	 *   window
	 * @param ceiling the campaign's ceiling
	 * @return the limit
	 * @throws NullPointerException if {@code unit} is null
	 */
	public int limit(WorkUnit unit, int spare, int ceiling)
	{
		return halve(Math.min(window(unit, spare, ceiling).size(), ceiling));
	}

	/**
	 * Applies the halvings in effect to a limit.
	 *
	 * @param limit a limit before the halvings
	 * @return the limit halved once for each halving in effect, at least 1
	 */
	public int halve(int limit)
	{
		return Math.max(1, limit >> halvings);
	}

	/**
	 * Returns these windows with a unit's window replaced.
	 *
	 * @param unit the unit of work
	 * @param window its window
	 * @return the updated windows
	 * @throws NullPointerException if any argument is null
	 */
	public ConcurrencyWindows with(WorkUnit unit, Window window)
	{
		requireThat(unit, "unit").isNotNull();
		requireThat(window, "window").isNotNull();
		Map<WorkUnit, Window> updated = new HashMap<>(windows);
		updated.put(unit, window);
		return new ConcurrencyWindows(updated, halvings, completions);
	}

	/**
	 * Returns these windows without the units that a filter rejects.
	 *
	 * @param current selects the units whose work can still continue
	 * @return the updated windows
	 * @throws NullPointerException if {@code current} is null
	 */
	public ConcurrencyWindows retain(Predicate<WorkUnit> current)
	{
		requireThat(current, "current").isNotNull();
		Map<WorkUnit, Window> retained = new HashMap<>();
		for (Map.Entry<WorkUnit, Window> entry : windows.entrySet())
		{
			if (current.test(entry.getKey()))
				retained.put(entry.getKey(), entry.getValue());
		}
		if (retained.size() == windows.size())
			return this;
		return new ConcurrencyWindows(retained, halvings, completions);
	}

	/**
	 * Returns these windows after a run ended without an outcome because of the provider or the harness: one more
	 * halving, and no completed trial since.
	 *
	 * @return the updated windows
	 */
	public ConcurrencyWindows afterInfrastructureAttempt()
	{
		return new ConcurrencyWindows(windows, Math.min(MAXIMUM_HALVINGS, halvings + 1), 0);
	}

	/**
	 * Returns these windows after a trial completed with an outcome. Once a full window of trials completed since the
	 * last halving or the last undone halving, one halving is undone.
	 *
	 * @param window the campaign's window when the trial completed
	 * @return the updated windows
	 * @throws IllegalArgumentException if {@code window} is not positive
	 */
	public ConcurrencyWindows afterCompletion(int window)
	{
		requireThat(window, "window").isPositive();
		if (halvings == 0)
			return this;
		if (completions + 1 < window)
			return new ConcurrencyWindows(windows, halvings, completions + 1);
		return new ConcurrencyWindows(windows, halvings - 1, 0);
	}
}
