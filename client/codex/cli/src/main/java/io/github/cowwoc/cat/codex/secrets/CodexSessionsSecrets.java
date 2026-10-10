package io.github.cowwoc.cat.codex.secrets;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.codex.optimizer.CodexSessions;

/**
 * Exposes narrowly scoped {@link CodexSessions} access required by tests.
 */
public final class CodexSessionsSecrets
{
	private static CodexSessionsAccess access;

	/**
	 * Returns the CodexSessions access point.
	 *
	 * @return the access point
	 */
	public static CodexSessionsAccess access()
	{
		if (access == null)
			initialize(CodexSessions.class);
		if (access == null)
			throw new AssertionError("CodexSessions did not register its access point");
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
	 * Registers the CodexSessions access point.
	 *
	 * @param newAccess the access point
	 */
	public static void setAccess(CodexSessionsAccess newAccess)
	{
		requireThat(newAccess, "newAccess").isNotNull();
		if (access != null)
			throw new IllegalStateException("CodexSessions access point is already registered");
		access = newAccess;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private CodexSessionsSecrets()
	{
	}
}
