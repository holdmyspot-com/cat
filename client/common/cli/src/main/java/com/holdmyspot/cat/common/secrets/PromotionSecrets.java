package com.holdmyspot.cat.common.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.common.optimizer.campaign.Promotion;

/**
 * Exposes narrowly scoped {@link Promotion} access required by tests.
 */
public final class PromotionSecrets
{
	private static PromotionAccess promotionAccess;

	/**
	 * Returns the {@link Promotion} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code Promotion} cannot initialize or fails to register access
	 */
	public static PromotionAccess getPromotionAccess()
	{
		PromotionAccess access = promotionAccess;
		if (access == null)
		{
			ensureClassInitialized(Promotion.class);
			access = promotionAccess;
			if (access == null)
				throw new AssertionError(Promotion.class.getName() + " did not register PromotionAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link Promotion}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setPromotionAccess(PromotionAccess access)
	{
		promotionAccess = access;
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
	private PromotionSecrets()
	{
	}
}
