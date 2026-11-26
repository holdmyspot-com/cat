package com.holdmyspot.cat.common.runner.login;

import java.io.IOException;
import java.io.Serial;

/**
 * Thrown when the login service refuses to open a session's login or to serve a token. The message says why and how to
 * fix it, and holds no token, key or other secret.
 */
public final class LoginRefusal extends IOException
{
	@Serial
	private static final long serialVersionUID = 0L;

	/**
	 * Creates a refusal.
	 *
	 * @param message the reason, which holds no secret
	 */
	public LoginRefusal(String message)
	{
		super(message);
	}

	/**
	 * Creates a refusal.
	 *
	 * @param message the reason, which holds no secret
	 * @param cause   the failure, whose message holds no secret
	 */
	public LoginRefusal(String message, Throwable cause)
	{
		super(message, cause);
	}
}
