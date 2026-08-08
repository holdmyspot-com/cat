package io.github.cowwoc.cat.distribution.bundle.secrets;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.distribution.bundle.ModuleDescriptorPatcher;

/**
 * Exposes narrowly scoped {@link ModuleDescriptorPatcher} access required by tests.
 */
public final class ModuleDescriptorPatcherSecrets
{
	private static ModuleDescriptorPatcherAccess access;

	/**
	 * Returns the module-descriptor patching access point.
	 *
	 * @return the access point
	 * @throws AssertionError if its owner cannot be initialized
	 */
	public static ModuleDescriptorPatcherAccess access()
	{
		if (access == null)
			initialize(ModuleDescriptorPatcher.class);
		if (access == null)
			throw new AssertionError("ModuleDescriptorPatcher did not register its access point");
		return access;
	}

	/**
	 * Initializes a class that registers an access point.
	 *
	 * @param owner the class to initialize
	 * @throws AssertionError if {@code owner} cannot be initialized
	 */
	private static void initialize(Class<?> owner)
	{
		try
		{
			Class.forName(owner.getName(), true, owner.getClassLoader());
		}
		catch (ClassNotFoundException e)
		{
			throw new AssertionError("Unable to initialize " + owner.getSimpleName(), e);
		}
	}

	/**
	 * Registers the module-descriptor patching access point.
	 *
	 * @param newAccess the access point
	 */
	public static void setAccess(ModuleDescriptorPatcherAccess newAccess)
	{
		requireThat(newAccess, "newAccess").isNotNull();
		if (access != null)
			throw new IllegalStateException("ModuleDescriptorPatcher access point is already registered");
		access = newAccess;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private ModuleDescriptorPatcherSecrets()
	{
	}
}
