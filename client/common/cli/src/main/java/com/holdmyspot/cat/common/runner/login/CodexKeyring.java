package com.holdmyspot.cat.common.runner.login;

import java.nio.file.Path;

/**
 * The operating system's keyring, in which Codex keeps a login when its {@code cli_auth_credentials_store} is
 * {@code keyring}, or {@code auto} and the keyring takes it. The login service reads it read-only and changes neither
 * the item nor its access list.
 * <p>
 * <b>Thread Safety:</b> Implementations must be thread-safe.
 */
public interface CodexKeyring
{
	/**
	 * The service of the keyring item in which Codex keeps a login.
	 */
	String SERVICE = "Codex Auth";

	/**
	 * Returns the keyring's item as a refusal names it, such as {@code the Secret Service item "Codex Auth"}.
	 *
	 * @return the name
	 */
	String name();

	/**
	 * Returns whether the keyring holds a login for a Codex folder, without reading its value.
	 *
	 * @param codexHome the Codex folder, whose canonical path names the item
	 * @return {@code true} if an item exists
	 * @throws NullPointerException if {@code codexHome} is null
	 * @throws LoginRefusal         if the keyring cannot be asked; the reason names the keyring
	 */
	boolean holds(Path codexHome) throws LoginRefusal;

	/**
	 * Reads the login that the keyring holds for a Codex folder.
	 *
	 * @param codexHome the Codex folder, whose canonical path names the item
	 * @return the login, of which only the access token or the key is kept
	 * @throws NullPointerException if {@code codexHome} is null
	 * @throws LoginRefusal         if the keyring holds no login for the folder, is locked, prompts or fails; the reason
	 *                              names the keyring and the folder and holds no secret
	 */
	CodexStoredLogin read(Path codexHome) throws LoginRefusal;
}
