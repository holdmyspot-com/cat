package com.holdmyspot.cat.common.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.common.optimizer.proposal.PromptTemplate;

/**
 * Exposes narrowly scoped {@link PromptTemplate} access required by tests.
 */
public final class PromptTemplateSecrets
{
	private static PromptTemplateAccess promptTemplateAccess;

	/**
	 * Returns the {@link PromptTemplate} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code PromptTemplate} cannot initialize or fails to register access
	 */
	public static PromptTemplateAccess getPromptTemplateAccess()
	{
		PromptTemplateAccess access = promptTemplateAccess;
		if (access == null)
		{
			ensureClassInitialized(PromptTemplate.class);
			access = promptTemplateAccess;
			if (access == null)
				throw new AssertionError(PromptTemplate.class.getName() + " did not register PromptTemplateAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link PromptTemplate}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setPromptTemplateAccess(PromptTemplateAccess access)
	{
		promptTemplateAccess = access;
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
	private PromptTemplateSecrets()
	{
	}
}
