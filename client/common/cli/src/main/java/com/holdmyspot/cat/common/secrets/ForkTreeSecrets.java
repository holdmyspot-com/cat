package com.holdmyspot.cat.common.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.common.optimizer.workspace.ForkTree;

/**
 * Exposes narrowly scoped {@link ForkTree} access required by tests.
 */
public final class ForkTreeSecrets
{
	private static ForkTreeAccess forkTreeAccess;

	/**
	 * Returns the {@link ForkTree} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code ForkTree} cannot initialize or fails to register access
	 */
	public static ForkTreeAccess getForkTreeAccess()
	{
		ForkTreeAccess access = forkTreeAccess;
		if (access == null)
		{
			ensureClassInitialized(ForkTree.class);
			access = forkTreeAccess;
			if (access == null)
				throw new AssertionError(ForkTree.class.getName() + " did not register ForkTreeAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link ForkTree}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setForkTreeAccess(ForkTreeAccess access)
	{
		forkTreeAccess = access;
	}

	/**
	 * Initializes a class that registers an access point.
	 *
	 * @param owner the class to initialize
	 * @throws AssertionError if the class cannot be initialized
	 */
	private static void ensureClassInitialized(Class<?> owner)
	{
		try
		{
			MethodHandles.lookup().ensureInitialized(owner);
		}
		catch (IllegalAccessException e)
		{
			throw new AssertionError("Cannot initialize " + owner.getName(), e);
		}
	}

	/**
	 * Prevents utility-class construction.
	 */
	private ForkTreeSecrets()
	{
	}
}
