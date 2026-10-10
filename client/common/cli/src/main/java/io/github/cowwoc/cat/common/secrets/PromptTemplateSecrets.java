package io.github.cowwoc.cat.common.secrets;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.proposal.PromptTemplate;

/**
 * Exposes narrowly scoped {@link PromptTemplate} access required by tests.
 */
public final class PromptTemplateSecrets
{
	private static PromptTemplateAccess access;

	/**
	 * Returns the PromptTemplate access point.
	 *
	 * @return the access point
	 */
	public static PromptTemplateAccess access()
	{
		if (access == null)
			initialize(PromptTemplate.class);
		if (access == null)
			throw new AssertionError("PromptTemplate did not register its access point");
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
	 * Registers the PromptTemplate access point.
	 *
	 * @param newAccess the access point
	 */
	public static void setAccess(PromptTemplateAccess newAccess)
	{
		requireThat(newAccess, "newAccess").isNotNull();
		if (access != null)
			throw new IllegalStateException("PromptTemplate access point is already registered");
		access = newAccess;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private PromptTemplateSecrets()
	{
	}
}
