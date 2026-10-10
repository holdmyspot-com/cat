package io.github.cowwoc.cat.common.secrets;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Promotion;

/**
 * Exposes narrowly scoped {@link Promotion} access required by tests.
 */
public final class PromotionSecrets
{
	private static PromotionAccess access;

	/**
	 * Returns the Promotion access point.
	 *
	 * @return the access point
	 */
	public static PromotionAccess access()
	{
		if (access == null)
			initialize(Promotion.class);
		if (access == null)
			throw new AssertionError("Promotion did not register its access point");
		return access;
	}

	/**
	 * Initializes a class that registers an access point.
	 *
	 * @param owner the class to initialize
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
	 * Registers the Promotion access point.
	 *
	 * @param newAccess the access point
	 */
	public static void setAccess(PromotionAccess newAccess)
	{
		requireThat(newAccess, "newAccess").isNotNull();
		if (access != null)
			throw new IllegalStateException("Promotion access point is already registered");
		access = newAccess;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private PromotionSecrets()
	{
	}
}
