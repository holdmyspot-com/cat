package com.holdmyspot.cat.common.runner.login;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Linux keyring that the Secret Service of the user's session bus serves, in which Codex keeps a login in the item
 * whose {@code service} attribute is {@code Codex Auth} and whose {@code username} attribute is the account that
 * {@link CodexLoginSource#account(String)} computes, with the whole {@code auth.json} content as its value.
 * <p>
 * It searches by attributes, opens a session with no encryption, reads the item's secret and closes the session. It
 * never unlocks a collection, so a locked keyring is a refusal rather than a prompt, and it changes no item.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class LinuxSecretService implements CodexKeyring
{
	/**
	 * How long one call may take.
	 */
	private static final Duration TIMEOUT = Duration.ofSeconds(10);
	private static final String SECRETS = "org.freedesktop.secrets";
	private static final String SERVICE_PATH = "/org/freedesktop/secrets";
	private static final String SERVICE_INTERFACE = "org.freedesktop.Secret.Service";

	/**
	 * The items that a Secret Service holds.
	 */
	public interface SecretItems
	{
		/**
		 * Searches the items whose attributes match.
		 *
		 * @param attributes the attributes
		 * @return the matching items
		 * @throws IOException if the service fails
		 */
		Found search(Map<String, String> attributes) throws IOException;

		/**
		 * Reads an unlocked item's value, without unlocking anything.
		 *
		 * @param item the item's object path
		 * @return the value
		 * @throws IOException if the service fails
		 */
		byte[] secret(String item) throws IOException;
	}

	/**
	 * The items that a search found.
	 *
	 * @param unlocked the object paths of the unlocked items
	 * @param locked   the object paths of the locked items
	 */
	public record Found(List<String> unlocked, List<String> locked)
	{
		/**
		 * Creates a search's result.
		 *
		 * @param unlocked the unlocked items
		 * @param locked   the locked items
		 * @throws NullPointerException if any of the arguments are null
		 */
		public Found
		{
			unlocked = List.copyOf(unlocked);
			locked = List.copyOf(locked);
		}
	}

	private final Platform platform;
	private final SecretItems items;

	/**
	 * Creates the keyring that the session bus serves.
	 *
	 * @param environment the server's environment, which names the session bus
	 * @param platform    the platform, which spells the Codex folder's canonical path
	 * @throws NullPointerException if any of the arguments are null
	 */
	LinuxSecretService(Map<String, String> environment, Platform platform)
	{
		this(platform, new BusItems(Map.copyOf(environment)));
	}

	/**
	 * Creates the keyring.
	 *
	 * @param platform the platform, which spells the Codex folder's canonical path
	 * @param items    the Secret Service's items
	 * @throws NullPointerException if any of the arguments are null
	 */
	public LinuxSecretService(Platform platform, SecretItems items)
	{
		requireThat(platform, "platform").isNotNull();
		requireThat(items, "items").isNotNull();
		this.platform = platform;
		this.items = items;
	}

	/**
	 * Returns the attributes of the item in which Codex keeps the login of a Codex folder.
	 *
	 * @param canonicalHome the folder's canonical path
	 * @return the attributes
	 * @throws NullPointerException if {@code canonicalHome} is null
	 */
	public static Map<String, String> attributes(String canonicalHome)
	{
		return Map.of("service", SERVICE, "username", CodexLoginSource.account(canonicalHome));
	}

	@Override
	public String name()
	{
		return "the Secret Service item \"" + SERVICE + "\"";
	}

	@Override
	public boolean holds(Path codexHome) throws LoginRefusal
	{
		requireThat(codexHome, "codexHome").isNotNull();
		Found found = search(codexHome);
		return !found.unlocked().isEmpty() || !found.locked().isEmpty();
	}

	@Override
	public CodexStoredLogin read(Path codexHome) throws LoginRefusal
	{
		requireThat(codexHome, "codexHome").isNotNull();
		Found found = search(codexHome);
		if (found.unlocked().isEmpty())
		{
			if (!found.locked().isEmpty())
			{
				throw new LoginRefusal(name() + " of the Codex folder " + codexHome + " is locked, and CAT does not " +
					"unlock it, which would prompt. Unlock the keyring, for example by signing in to the desktop, or " +
					"set cli_auth_credentials_store = \"file\" in that folder's config.toml and sign in to Codex again.");
			}
			throw new LoginRefusal("The keyring holds no " + name() + " for the Codex folder " + codexHome +
				". Sign in to Codex on that source home again.");
		}
		byte[] value;
		try
		{
			value = items.secret(found.unlocked().getFirst());
		}
		catch (LoginRefusal e)
		{
			throw e;
		}
		catch (IOException | RuntimeException e)
		{
			throw failure(codexHome, e);
		}
		try
		{
			return CodexStoredLogin.parse(new String(value, UTF_8), name() + " of the Codex folder " + codexHome);
		}
		finally
		{
			Arrays.fill(value, (byte) 0);
		}
	}

	/**
	 * Searches the items of a Codex folder.
	 *
	 * @param codexHome the Codex folder
	 * @return the items
	 * @throws LoginRefusal if the search fails
	 */
	private Found search(Path codexHome) throws LoginRefusal
	{
		try
		{
			return items.search(attributes(CodexLoginSource.canonical(codexHome, platform)));
		}
		catch (LoginRefusal e)
		{
			throw e;
		}
		catch (IOException | RuntimeException e)
		{
			throw failure(codexHome, e);
		}
	}

	/**
	 * Returns the refusal for a keyring that fails.
	 *
	 * @param codexHome the Codex folder
	 * @param failure   the failure, whose message holds no secret
	 * @return the refusal
	 */
	private LoginRefusal failure(Path codexHome, Exception failure)
	{
		return new LoginRefusal("The request server cannot read " + name() + " of the Codex folder " + codexHome +
			" from the Secret Service: " + failure.getMessage() + ". Make sure the keyring runs and is unlocked, or " +
			"set cli_auth_credentials_store = \"file\" in that folder's config.toml and sign in to Codex again.",
			failure);
	}

	/**
	 * The items of the Secret Service on the user's session bus.
	 *
	 * @param environment the server's environment, which names the session bus
	 */
	private record BusItems(Map<String, String> environment) implements SecretItems
	{
		@Override
		public Found search(Map<String, String> attributes) throws IOException
		{
			List<Object> pairs = new ArrayList<>();
			for (Map.Entry<String, String> attribute : attributes.entrySet())
				pairs.add(new Object[]{attribute.getKey(), attribute.getValue()});
			try (DBusConnection bus = connect())
			{
				List<Object> found = bus.call(SECRETS, SERVICE_PATH, SERVICE_INTERFACE, "SearchItems", "a{ss}",
					List.of(pairs));
				return new Found(paths(found.get(0)), paths(found.get(1)));
			}
		}

		/**
		 * Returns the object paths of a list.
		 *
		 * @param list the list
		 * @return the paths
		 */
		private static List<String> paths(Object list)
		{
			List<String> paths = new ArrayList<>();
			for (Object path : (List<?>) list)
				paths.add((String) path);
			return paths;
		}

		@Override
		public byte[] secret(String item) throws IOException
		{
			try (DBusConnection bus = connect())
			{
				List<Object> opened = bus.call(SECRETS, SERVICE_PATH, SERVICE_INTERFACE, "OpenSession", "sv",
					List.of("plain", new DBusConnection.Variant("s", "")));
				String session = (String) opened.get(1);
				try
				{
					List<Object> secret = bus.call(SECRETS, item, "org.freedesktop.Secret.Item", "GetSecret", "o",
						List.of(session));
					return (byte[]) ((Object[]) secret.getFirst())[2];
				}
				finally
				{
					bus.call(SECRETS, session, "org.freedesktop.Secret.Session", "Close", "", List.of());
				}
			}
		}

		/**
		 * Connects to the session bus.
		 *
		 * @return the connection
		 * @throws IOException if the bus cannot be reached
		 */
		private DBusConnection connect() throws IOException
		{
			Optional<Path> socket = KeyringReach.busSocket(environment);
			if (socket.isEmpty() || !Files.exists(socket.get()))
			{
				throw new IOException("the request server finds no session bus in a file-system socket; run the session " +
					"where the keyring's session bus is reachable");
			}
			long uid = ((Number) Files.getAttribute(Path.of("/proc/self"), "unix:uid")).longValue();
			return new DBusConnection(socket.get(), uid, TIMEOUT);
		}

		@Override
		public String toString()
		{
			return "BusItems";
		}
	}
}
