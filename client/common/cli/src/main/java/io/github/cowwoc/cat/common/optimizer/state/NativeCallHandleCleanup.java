package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.runner.ActiveCall;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunException;

/** Releases a native handle after its scheduler transition fails. */
final class NativeCallHandleCleanup
{
	/** Prevents construction. */
	private NativeCallHandleCleanup()
	{
	}

	/**
	 * Interrupts and closes a handle without replacing the original failure.
	 *
	 * @param call native handle that cannot be returned to its caller
	 * @param failure original transition failure
	 */
	static void interruptAndClose(ActiveCall call, Throwable failure)
	{
		try
		{
			call.interrupt();
		}
		catch (RuntimeException cleanup)
		{
			failure.addSuppressed(cleanup);
		}
		try
		{
			call.close();
		}
		catch (NestedRunException cleanup)
		{
			failure.addSuppressed(cleanup);
		}
	}
}
