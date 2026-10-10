package io.github.cowwoc.cat.codex.secrets;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.codex.CodexGetHistory;

/**
 * Exposes narrowly scoped {@link CodexGetHistory} access required by tests.
 */
public final class CodexGetHistorySecrets
{
	private static CodexGetHistoryAccess access;

	/**
	 * Returns the CodexGetHistory access point.
	 *
	 * @return the access point
	 */
	public static CodexGetHistoryAccess access()
	{
		if (access == null)
			initialize(CodexGetHistory.class);
		if (access == null)
			throw new AssertionError("CodexGetHistory did not register its access point");
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
	 * Registers the CodexGetHistory access point.
	 *
	 * @param newAccess the access point
	 */
	public static void setAccess(CodexGetHistoryAccess newAccess)
	{
		requireThat(newAccess, "newAccess").isNotNull();
		if (access != null)
			throw new IllegalStateException("CodexGetHistory access point is already registered");
		access = newAccess;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private CodexGetHistorySecrets()
	{
	}
}
