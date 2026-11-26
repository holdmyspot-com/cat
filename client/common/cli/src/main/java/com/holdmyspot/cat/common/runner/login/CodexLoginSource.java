package com.holdmyspot.cat.common.runner.login;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * Reads the login that a source home's Codex keeps, read-only: its {@code auth.json}, or the item of the operating
 * system's keyring, as {@code cli_auth_credentials_store} chooses, with {@code auto} preferring the keyring's item and
 * falling back to {@code auth.json} when the keyring holds none.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class CodexLoginSource
{
	/**
	 * The name of Codex's login file in its folder.
	 */
	public static final String LOGIN_FILE = "auth.json";
	/**
	 * The most bytes of a login that the source reads.
	 */
	private static final int MAXIMUM_LOGIN_BYTES = 1_048_576;
	/**
	 * The number of hexadecimal digits of the hash that names a keyring item.
	 */
	private static final int HASH_DIGITS = 16;

	private final CodexKeyring keyring;

	/**
	 * Creates a source.
	 *
	 * @param keyring the operating system's keyring
	 * @throws NullPointerException if {@code keyring} is null
	 */
	public CodexLoginSource(CodexKeyring keyring)
	{
		requireThat(keyring, "keyring").isNotNull();
		this.keyring = keyring;
	}

	/**
	 * Returns the source that reads the current platform's keyring.
	 *
	 * @param platform    the platform
	 * @param environment the server's environment, whose session bus and {@code PATH} the keyring's client uses
	 * @return the source
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static CodexLoginSource system(Platform platform, Map<String, String> environment)
	{
		requireThat(platform, "platform").isNotNull();
		requireThat(environment, "environment").isNotNull();
		CodexKeyring keyring;
		if (platform.isLinux())
			keyring = new LinuxSecretService(environment, platform);
		else if (platform.isMacos())
			keyring = MacosKeychain.of(environment, platform);
		else
			keyring = new WindowsCredentials(platform);
		return new CodexLoginSource(keyring);
	}

	/**
	 * Returns the keyring.
	 *
	 * @return the keyring
	 */
	public CodexKeyring keyring()
	{
		return keyring;
	}

	/**
	 * Returns the account of the keyring item in which Codex keeps the login of a Codex folder: {@code cli|} and the
	 * first 16 hexadecimal digits of the SHA-256 of the folder's canonical path.
	 *
	 * @param canonicalHome the folder's canonical path, as Codex's platform spells it
	 * @return the account
	 * @throws NullPointerException if {@code canonicalHome} is null
	 */
	public static String account(String canonicalHome)
	{
		requireThat(canonicalHome, "canonicalHome").isNotNull();
		return "cli|" + hash(canonicalHome);
	}

	/**
	 * Returns the first 16 hexadecimal digits of the SHA-256 of a text.
	 *
	 * @param text the text
	 * @return the digits
	 */
	public static String hash(String text)
	{
		try
		{
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(UTF_8));
			return HexFormat.of().formatHex(digest).substring(0, HASH_DIGITS);
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new AssertionError("Every Java runtime has SHA-256", e);
		}
	}

	/**
	 * Returns the canonical path of a Codex folder as Codex spells it on a platform: its real path, or the path itself
	 * when it has none, with Windows's {@code \\?\} prefix that Codex's canonical paths carry there.
	 *
	 * @param codexHome the folder
	 * @param platform  the platform
	 * @return the canonical path
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static String canonical(Path codexHome, Platform platform)
	{
		requireThat(codexHome, "codexHome").isNotNull();
		requireThat(platform, "platform").isNotNull();
		Path path;
		try
		{
			path = codexHome.toRealPath();
		}
		catch (IOException _)
		{
			// Codex hashes the folder as it is named when it has no real path
			return codexHome.toString();
		}
		String text = path.toString();
		if (!platform.isWindows() || text.startsWith("\\\\?\\"))
			return text;
		if (text.startsWith("\\\\"))
			return "\\\\?\\UNC\\" + text.substring(2);
		return "\\\\?\\" + text;
	}

	/**
	 * Returns whether a stored login is in the keyring: always for the {@code keyring} store, never for {@code file},
	 * and for {@code auto} when the keyring holds an item for the source's Codex folder.
	 *
	 * @param plan the plan
	 * @return {@code true} if the login is in the keyring
	 * @throws NullPointerException if {@code plan} is null
	 * @throws LoginRefusal         if the keyring cannot be asked
	 */
	public boolean inKeyring(CodexLoginPlan.Stored plan) throws LoginRefusal
	{
		requireThat(plan, "plan").isNotNull();
		return switch (plan.store())
		{
			case FILE -> false;
			case KEYRING -> true;
			case AUTO -> keyring.holds(plan.codexHome());
		};
	}

	/**
	 * Reads a stored login now, read-only.
	 *
	 * @param plan      the plan
	 * @param inKeyring {@code true} to read the keyring's item, {@code false} to read {@code auth.json}
	 * @return the login
	 * @throws NullPointerException if {@code plan} is null
	 * @throws LoginRefusal         if the store holds no login, or it cannot be read or parsed; the reason names the
	 *                              store and holds no secret
	 */
	public CodexStoredLogin read(CodexLoginPlan.Stored plan, boolean inKeyring) throws LoginRefusal
	{
		requireThat(plan, "plan").isNotNull();
		if (inKeyring)
			return keyring.read(plan.codexHome());
		return readFile(plan.codexHome().resolve(LOGIN_FILE));
	}

	/**
	 * Reads Codex's {@code auth.json}, opening it for reading alone.
	 *
	 * @param file the file
	 * @return the login
	 * @throws NullPointerException if {@code file} is null
	 * @throws LoginRefusal         if the file does not exist, cannot be read or holds no login
	 */
	public static CodexStoredLogin readFile(Path file) throws LoginRefusal
	{
		requireThat(file, "file").isNotNull();
		String text;
		try
		{
			if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(file))
				throw new NoSuchFileException(file.toString());
			text = BoundedInput.readUtf8(file, MAXIMUM_LOGIN_BYTES, "Codex login");
		}
		catch (NoSuchFileException _)
		{
			throw new LoginRefusal("The Codex login file " + file + " does not exist. Sign in to Codex on that " +
				"source home again.");
		}
		catch (IOException e)
		{
			throw new LoginRefusal("The Codex login file " + file + " cannot be read: " + e.getClass().getSimpleName() +
				". Let its owner read it, or sign in to Codex again.");
		}
		return CodexStoredLogin.parse(text, file.toString());
	}
}
