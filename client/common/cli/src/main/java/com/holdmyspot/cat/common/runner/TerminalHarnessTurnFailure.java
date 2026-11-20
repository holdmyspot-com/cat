package com.holdmyspot.cat.common.runner;

import java.io.IOException;
import java.io.Serial;
import java.util.Objects;

/** An IOException raised after a harness has reported a terminal failed turn. */
public final class TerminalHarnessTurnFailure extends IOException
{
	@Serial
	private static final long serialVersionUID = 1L;

	private final int exitCode;
	private final String standardOutput;

	/**
	 * Creates a failure that retains the harness's terminal turn result for managed-session recording.
	 *
	 * @param result the terminal failed result
	 */
	public TerminalHarnessTurnFailure(HarnessProcessResult result)
	{
		super(Objects.requireNonNull(result, "result").standardError());
		if (!result.terminal() || result.completed())
			throw new IllegalArgumentException("A terminal turn failure must be terminal and incomplete");
		exitCode = result.exitCode();
		standardOutput = result.standardOutput();
	}

	/**
	 * Returns the terminal failed result.
	 *
	 * @return the terminal failed result
	 */
	public HarnessProcessResult result()
	{
		return new HarnessProcessResult(exitCode, standardOutput, getMessage(), false, true);
	}
}
