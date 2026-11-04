package com.holdmyspot.cat.common.installer.secrets;

import com.holdmyspot.cat.common.installer.engine.HttpsReleaseSource;
import java.lang.invoke.MethodHandles;

/**
 * Supplies typed access to {@link HttpsReleaseSource}'s transport boundary for its test module.
 */
public final class HttpsSourceSecrets
{
	private static HttpsSourceAccess httpsSourceAccess;

	/**
	 * Prevents construction of this static facade.
	 */
	private HttpsSourceSecrets()
	{
	}

	/**
	 * Registers access from the owning source's initializer.
	 *
	 * @param access transport construction access
	 */
	public static void setHttpsSourceAccess(HttpsSourceAccess access)
	{
		httpsSourceAccess = access;
	}

	/**
	 * Returns the source's registered transport access, initializing its owner when needed.
	 *
	 * @return transport construction access
	 */
	public static HttpsSourceAccess getHttpsSourceAccess()
	{
		HttpsSourceAccess access = httpsSourceAccess;
		if (access == null)
		{
			ensureClassInitialized(HttpsReleaseSource.class);
			access = httpsSourceAccess;
			if (access == null)
				throw new AssertionError(HttpsReleaseSource.class.getName() + " did not register HttpsSourceAccess");
		}
		return access;
	}

	/**
	 * Runs an access owner's static initializer.
	 *
	 * @param owner class that registers an accessor
	 */
	private static void ensureClassInitialized(Class<?> owner)
	{
		try
		{
			MethodHandles.lookup().ensureInitialized(owner);
		}
		catch (IllegalAccessException failure)
		{
			throw new AssertionError("Cannot initialize " + owner.getName(), failure);
		}
	}
}
