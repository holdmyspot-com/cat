package com.holdmyspot.cat.claude.credential;

/**
 * Thrown when {@link ClaudeCredentialFile} refuses CAT's Claude credential file or a token for it: a file that a launch
 * cannot use, a token of neither accepted form, or a path that is a symbolic link or an entry of the wrong kind. Its
 * message names the path or the fault and how to fix it, and never holds the token.
 */
public final class CredentialRefusedException extends Exception
{
	private static final long serialVersionUID = 1L;

	/**
	 * Creates a refusal.
	 *
	 * @param message the fault and how to fix it, which never holds the token
	 */
	public CredentialRefusedException(String message)
	{
		super(message);
	}
}
