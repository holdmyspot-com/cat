package com.holdmyspot.cat.distribution.bundle.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.distribution.bundle.ModuleDescriptorPatcher;

/**
 * Exposes narrowly scoped {@link ModuleDescriptorPatcher} access required by tests.
 */
public final class ModuleDescriptorPatcherSecrets
{
	private static ModuleDescriptorPatcherAccess moduleDescriptorPatcherAccess;

	/**
	 * Returns the {@link ModuleDescriptorPatcher} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code ModuleDescriptorPatcher} cannot initialize or fails to register access
	 */
	public static ModuleDescriptorPatcherAccess getModuleDescriptorPatcherAccess()
	{
		ModuleDescriptorPatcherAccess access = moduleDescriptorPatcherAccess;
		if (access == null)
		{
			ensureClassInitialized(ModuleDescriptorPatcher.class);
			access = moduleDescriptorPatcherAccess;
			if (access == null)
				throw new AssertionError(ModuleDescriptorPatcher.class.getName() + " did not register " +
					"ModuleDescriptorPatcherAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link ModuleDescriptorPatcher}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setModuleDescriptorPatcherAccess(ModuleDescriptorPatcherAccess access)
	{
		moduleDescriptorPatcherAccess = access;
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
	private ModuleDescriptorPatcherSecrets()
	{
	}
}
