package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;

/**
 * Thrown when a check program's run ends before its source launcher is ready. The failure names the last start-up stage
 * that CAT observed and, where CAT can tell the cause, the cause; every other case is an infrastructure failure.
 */
public final class CheckProgramStartFailure extends IOException
{
	private static final long serialVersionUID = 1L;

	/** What kept the launcher from becoming ready. */
	public enum Reason
	{
		/** The sandbox program exited with its own error, or stalled while it was setting up. */
		SANDBOX_CANNOT_START,
		/** A program of the launcher is missing or not executable, or the launcher exited before it was ready. */
		LAUNCHER_UNUSABLE,
		/** The launcher's process tree held the same processes in one stage and used almost no processor time. */
		LAUNCHER_STALLED,
		/** None of the above: a loaded or slow machine, a slow first start, a time limit shorter than a start. */
		NOT_READY
	}

	private final Reason reason;

	/**
	 * Creates a failure.
	 *
	 * @param reason  what kept the launcher from becoming ready
	 * @param message the report, which names the cause or, for {@link Reason#NOT_READY}, the evidence that tells a short
	 *                limit from a slow machine
	 * @throws NullPointerException if any argument is null
	 */
	public CheckProgramStartFailure(Reason reason, String message)
	{
		super(message);
		requireThat(reason, "reason").isNotNull();
		this.reason = reason;
	}

	/**
	 * Returns what kept the launcher from becoming ready.
	 *
	 * @return the reason
	 */
	public Reason reason()
	{
		return reason;
	}

	/**
	 * Tells whether the failure is an infrastructure failure that names no cause.
	 *
	 * @return {@code true} if the reason is {@link Reason#NOT_READY}
	 */
	public boolean isInfrastructure()
	{
		return reason == Reason.NOT_READY;
	}
}
