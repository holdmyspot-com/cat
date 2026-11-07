package com.holdmyspot.cat.common.installer.secrets;

import com.holdmyspot.cat.common.installer.cli.internal.ProcessHarnessCli;
import java.lang.invoke.MethodHandles;

/**
 * Supplies typed access to {@link ProcessHarnessCli}'s controlled process start for its test modules.
 */
public final class ProcessHarnessCliSecrets
{
	private static ProcessHarnessCliAccess processHarnessCliAccess;

	/**
	 * Prevents construction of this static facade.
	 */
	private ProcessHarnessCliSecrets()
	{
	}

	/**
	 * Registers access from the owning class's initializer.
	 *
	 * @param access the controlled process start
	 */
	public static void setProcessHarnessCliAccess(ProcessHarnessCliAccess access)
	{
		processHarnessCliAccess = access;
	}

	/**
	 * Returns the registered access, initializing its owner when needed.
	 *
	 * @return the controlled process start
	 */
	public static ProcessHarnessCliAccess getProcessHarnessCliAccess()
	{
		ProcessHarnessCliAccess access = processHarnessCliAccess;
		if (access == null)
		{
			try
			{
				MethodHandles.lookup().ensureInitialized(ProcessHarnessCli.class);
			}
			catch (IllegalAccessException failure)
			{
				throw new AssertionError("Cannot initialize " + ProcessHarnessCli.class.getName(), failure);
			}
			access = processHarnessCliAccess;
			if (access == null)
			{
				throw new AssertionError(ProcessHarnessCli.class.getName() +
					" did not register ProcessHarnessCliAccess");
			}
		}
		return access;
	}
}
