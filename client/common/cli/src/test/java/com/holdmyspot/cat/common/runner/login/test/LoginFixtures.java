package com.holdmyspot.cat.common.runner.login.test;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.runner.login.CodexKeyring;
import com.holdmyspot.cat.common.runner.login.CodexStoredLogin;
import com.holdmyspot.cat.common.runner.login.LoginRefusal;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the source homes, logins and keyring doubles of the login tests.
 */
final class LoginFixtures
{
	/**
	 * The refresh token of every sign-in fixture, which no answer may hold.
	 */
	static final String REFRESH_TOKEN = "fixture-refresh-token";
	/**
	 * The account ID of every sign-in fixture.
	 */
	static final String ACCOUNT_ID = "fixture-account";
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Prevents construction.
	 */
	private LoginFixtures()
	{
	}

	/**
	 * Returns an access token in the form of a JSON web token, whose claims name the fixture's account.
	 *
	 * @param name   a word that makes the token unique
	 * @param expiry when the token expires
	 * @return the token
	 */
	static String accessToken(String name, Instant expiry)
	{
		Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
		ObjectNode claims = JSON.createObjectNode();
		claims.put("exp", expiry.getEpochSecond());
		claims.put("sub", name);
		claims.putObject("https://api.openai.com/auth").put("chatgpt_account_id", ACCOUNT_ID);
		return encoder.encodeToString("{\"alg\":\"none\"}".getBytes(UTF_8)) + "." +
			encoder.encodeToString(JSON.writeValueAsString(claims).getBytes(UTF_8)) + ".signature-" + name;
	}

	/**
	 * Returns the text of a ChatGPT sign-in as Codex stores it.
	 *
	 * @param accessToken the access token
	 * @return the text
	 */
	static String signIn(String accessToken)
	{
		ObjectNode login = JSON.createObjectNode();
		login.putNull("OPENAI_API_KEY");
		ObjectNode tokens = login.putObject("tokens");
		tokens.put("id_token", "fixture-id-token");
		tokens.put("access_token", accessToken);
		tokens.put("refresh_token", REFRESH_TOKEN);
		tokens.put("account_id", ACCOUNT_ID);
		login.put("last_refresh", "2026-11-26T00:00:00Z");
		return JSON.writeValueAsString(login);
	}

	/**
	 * Returns the text of an API-key login as Codex stores it.
	 *
	 * @param key the key
	 * @return the text
	 */
	static String apiKey(String key)
	{
		ObjectNode login = JSON.createObjectNode();
		login.put("OPENAI_API_KEY", key);
		return JSON.writeValueAsString(login);
	}

	/**
	 * Writes a login file that its owner alone may read and no one may write.
	 *
	 * @param file the file
	 * @param text the login's text
	 * @throws IOException if the file cannot be written
	 */
	static void writeReadOnly(Path file, String text) throws IOException
	{
		if (Files.exists(file))
			PortableFiles.clearReadOnly(file);
		Files.writeString(file, text);
		if (PortableFiles.hasModes(file))
			PortableFiles.setMode(file, EnumSet.of(PosixFilePermission.OWNER_READ));
		else
			PortableFiles.setReadOnly(file);
	}

	/**
	 * Returns whether a folder is its owner's alone.
	 *
	 * @param folder the folder
	 * @return {@code true} if no one but its owner may use it
	 * @throws IOException if its access cannot be read
	 */
	static boolean ownerOnly(Path folder) throws IOException
	{
		if (PortableFiles.hasModes(folder))
		{
			return PortableFiles.mode(folder).equals(EnumSet.of(PosixFilePermission.OWNER_READ,
				PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
		}
		AclFileAttributeView view = Files.getFileAttributeView(folder, AclFileAttributeView.class);
		String owner = view.getOwner().getName();
		for (AclEntry entry : view.getAcl())
		{
			if (entry.type() == AclEntryType.ALLOW && !entry.principal().getName().equals(owner))
				return false;
		}
		return true;
	}

	/**
	 * Reads a test resource of this package.
	 *
	 * @param name the resource's name
	 * @return its bytes
	 * @throws IOException if it cannot be read
	 */
	static byte[] resource(String name) throws IOException
	{
		try (InputStream input = LoginFixtures.class.getResourceAsStream(name))
		{
			if (input == null)
				throw new IOException("No test resource " + name);
			return input.readAllBytes();
		}
	}

	/**
	 * Returns a path as the shell that runs a fixture command prints it. On Windows that is the shell of Git for
	 * Windows, which names {@code C:\Users\me} as {@code /c/Users/me}.
	 *
	 * @param path an absolute path
	 * @return the path as the shell prints it
	 */
	static String shellPath(Path path)
	{
		String text = path.toString();
		if (!Platform.current().isWindows())
			return text;
		return "/" + Character.toLowerCase(text.charAt(0)) + text.substring(2).replace('\\', '/');
	}

	/**
	 * A keyring double that holds one login, or none, and counts its reads.
	 */
	static final class Keyring implements CodexKeyring
	{
		private volatile String login;
		private final AtomicInteger reads = new AtomicInteger();

		/**
		 * Creates a keyring.
		 *
		 * @param login the text of the login that it holds, or null if it holds none
		 */
		Keyring(String login)
		{
			this.login = login;
		}

		/**
		 * Replaces the login.
		 *
		 * @param login the login's text
		 */
		void set(String login)
		{
			this.login = login;
		}

		/**
		 * Returns how many times the login was read.
		 *
		 * @return the count
		 */
		int reads()
		{
			return reads.get();
		}

		@Override
		public String name()
		{
			return "the fixture keyring item \"" + SERVICE + "\"";
		}

		@Override
		public boolean holds(Path codexHome)
		{
			return login != null;
		}

		@Override
		public CodexStoredLogin read(Path codexHome) throws LoginRefusal
		{
			reads.incrementAndGet();
			String text = login;
			if (text == null)
				throw new LoginRefusal("The fixture keyring holds no login for " + codexHome);
			return CodexStoredLogin.parse(text, name());
		}

		@Override
		public String toString()
		{
			return "Keyring{reads=" + reads + "}";
		}
	}
}
