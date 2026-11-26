package com.holdmyspot.cat.common.runner.login;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Decides whether the top-level session of a runner chain can itself reach the operating system's keyring from its own
 * sandbox, so that the login service serves a login that the keyring holds only to the sessions of a chain whose
 * top-level session could read it too.
 * <p>
 * A session that is not sandboxed reaches the keyring on every platform. A sandboxed one reaches it:
 * <ul>
 *   <li>on Linux, when its policy lets its commands reach the operating system's local services and the session bus
 *   is a socket in the file system whose real path its policy does not deny reading. The bus is the one that
 *   {@code DBUS_SESSION_BUS_ADDRESS} names with {@code unix:path=}, otherwise {@code $XDG_RUNTIME_DIR/bus}; a bus in
 *   the abstract namespace is unreachable from a sandbox, whose network namespace is its own or whose sockets are
 *   filtered;</li>
 *   <li>on macOS, when its policy lets its commands reach the operating system's local services;</li>
 *   <li>on Windows, never.</li>
 * </ul>
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class KeyringReach
{
	/**
	 * The variable that names the session bus's addresses.
	 */
	public static final String BUS_ADDRESS_VARIABLE = "DBUS_SESSION_BUS_ADDRESS";
	/**
	 * The variable that names the user's runtime folder, which holds the session bus's default socket.
	 */
	public static final String RUNTIME_FOLDER_VARIABLE = "XDG_RUNTIME_DIR";
	/**
	 * What a refusal says the top-level session lacks when its policy keeps its commands from local services.
	 */
	private static final String NO_LOCAL_SERVICES = "its sandbox does not let its commands reach the operating " +
		"system's local services (a Codex session reaches them only with full network access, and a Claude Code " +
		"session on Linux only with sandbox.network.allowAllUnixSockets set to true)";

	/**
	 * Prevents construction.
	 */
	private KeyringReach()
	{
	}

	/**
	 * Returns the session bus socket that a Secret Service client on Linux connects to.
	 *
	 * @param environment the environment of the process that connects
	 * @return the socket, or empty if no bus address names one
	 * @throws NullPointerException if {@code environment} is null
	 */
	public static Optional<Path> busSocket(Map<String, String> environment)
	{
		requireThat(environment, "environment").isNotNull();
		return switch (busAddress(environment))
		{
			case BusAddress.FileSocket(Path socket) -> Optional.of(socket);
			case BusAddress.Abstract _, BusAddress.Missing _ -> Optional.empty();
		};
	}

	/**
	 * Returns why the top-level session of a chain cannot reach the keyring from its own sandbox.
	 *
	 * @param topLevel    the top-level session's policy for its main thread, as the request server reads it for a
	 *                    launch, with its earlier readings intersected
	 * @param platform    the platform that the session and the server run on
	 * @param environment the server's environment, whose bus address the server's own Secret Service client uses
	 * @return the reason, which names the setting or the denied path that keeps the session out, or empty if the
	 *   session reaches the keyring
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if a symbolic link in the bus socket's path cannot be read
	 */
	public static Optional<String> unreachable(ParentPolicy topLevel, Platform platform,
		Map<String, String> environment) throws IOException
	{
		requireThat(topLevel, "topLevel").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(environment, "environment").isNotNull();
		if (!topLevel.sandboxed())
			return Optional.empty();
		if (platform.isWindows())
		{
			return Optional.of("its commands run in a sandbox, and no sandbox on Windows lets them reach the Credential " +
				"Manager");
		}
		if (!topLevel.allowLocalServices())
			return Optional.of(NO_LOCAL_SERVICES);
		if (platform.isMacos())
			return Optional.empty();
		return switch (busAddress(environment))
		{
			case BusAddress.Missing _ -> Optional.of("the request server finds no session bus: neither " +
				BUS_ADDRESS_VARIABLE + " nor " + RUNTIME_FOLDER_VARIABLE + " names one");
			case BusAddress.Abstract(String address) -> Optional.of("the session bus " + address + " is in the " +
				"abstract socket namespace, which no sandboxed command reaches");
			case BusAddress.FileSocket(Path socket) ->
			{
				Optional<Path> denial = topLevel.readDenial(socket);
				if (denial.isEmpty())
					yield Optional.empty();
				yield Optional.of("its sandbox denies reading " + denial.get() + ", which holds the session bus " +
					"socket " + socket);
			}
		};
	}

	/**
	 * Returns the session bus's address that a client on Linux uses.
	 *
	 * @param environment the client's environment
	 * @return the address
	 */
	private static BusAddress busAddress(Map<String, String> environment)
	{
		String addresses = environment.getOrDefault(BUS_ADDRESS_VARIABLE, "");
		String abstractAddress = null;
		for (String address : addresses.split(";"))
		{
			String trimmed = address.strip();
			if (!trimmed.startsWith("unix:"))
				continue;
			for (String pair : trimmed.substring("unix:".length()).split(","))
			{
				int equals = pair.indexOf('=');
				if (equals < 0)
					continue;
				String key = pair.substring(0, equals);
				String value = unescape(pair.substring(equals + 1));
				if (key.equals("path"))
				{
					Optional<Path> socket = absolutePath(value);
					if (socket.isPresent())
						return new BusAddress.FileSocket(socket.get());
				}
				if ((key.equals("abstract") || key.equals("tmpdir") || key.equals("dir")) && abstractAddress == null)
					abstractAddress = trimmed;
			}
		}
		if (abstractAddress != null)
			return new BusAddress.Abstract(abstractAddress);
		String runtime = environment.getOrDefault(RUNTIME_FOLDER_VARIABLE, "");
		if (addresses.isBlank())
		{
			Optional<Path> folder = absolutePath(runtime);
			if (folder.isPresent())
				return new BusAddress.FileSocket(folder.get().resolve("bus"));
		}
		return new BusAddress.Missing();
	}

	/**
	 * Returns a path that a value names, if the value names an absolute one.
	 *
	 * @param value the value
	 * @return the path, or empty if {@code value} is not an absolute path
	 */
	private static Optional<Path> absolutePath(String value)
	{
		if (value.isBlank())
			return Optional.empty();
		try
		{
			Path path = Path.of(value);
			if (path.isAbsolute())
				return Optional.of(path);
		}
		catch (InvalidPathException _)
		{
			// A value that is no path names no socket
		}
		return Optional.empty();
	}

	/**
	 * Decodes the escapes of a D-Bus address value, in which {@code %} and two hexadecimal digits stand for a byte.
	 *
	 * @param value the value
	 * @return the decoded value, or {@code value} if an escape is malformed
	 */
	private static String unescape(String value)
	{
		if (value.indexOf('%') < 0)
			return value;
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
		int i = 0;
		while (i < value.length())
		{
			char c = value.charAt(i);
			if (c != '%')
			{
				bytes.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
				i += 1;
				continue;
			}
			if (i + 2 >= value.length())
				return value;
			int high = Character.digit(value.charAt(i + 1), 16);
			int low = Character.digit(value.charAt(i + 2), 16);
			if (high < 0 || low < 0)
				return value;
			bytes.write((high << 4) | low);
			i += 3;
		}
		return bytes.toString(StandardCharsets.UTF_8);
	}

	/**
	 * The address of the session bus.
	 */
	private sealed interface BusAddress
	{
		/**
		 * A socket in the file system.
		 *
		 * @param socket the socket's path
		 */
		record FileSocket(Path socket) implements BusAddress
		{
		}

		/**
		 * A socket in the abstract namespace.
		 *
		 * @param address the address
		 */
		record Abstract(String address) implements BusAddress
		{
		}

		/**
		 * No address.
		 */
		record Missing() implements BusAddress
		{
		}
	}
}
