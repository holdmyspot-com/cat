package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.Platform;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Builds the minimal environment inherited by isolated harness processes.
 */
public final class HarnessEnvironment
{
	/**
	 * The variable that marks a harness process as a session of a CAT run. A runner sets it to {@value #RUN_VALUE} in
	 * the environment of every harness process that it starts, and the session's commands inherit it. CAT's hooks in
	 * the session then start no session launcher, and a runner launcher that one of the commands runs starts no
	 * session of its own: inside a run, a session starts only as a request to the run's own runner, which counts and
	 * limits the sessions that the run requests.
	 */
	public static final String RUN_VARIABLE = "CAT_RUN";
	/**
	 * The value of {@link #RUN_VARIABLE} in a session of a CAT run.
	 */
	public static final String RUN_VALUE = "1";
	/**
	 * The names of the variables that name the proxy for a child's network connections, in upper and lower case.
	 */
	private static final List<String> PROXY_VARIABLES = List.of("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY",
		"http_proxy", "https_proxy", "all_proxy");
	/**
	 * The names of the variables that list the hosts that a child connects to without a proxy.
	 */
	private static final List<String> EXCLUSION_VARIABLES = List.of("NO_PROXY", "no_proxy");

	/**
	 * Returns why a runner launcher refuses to start a session from the command that runs it. A command of a CAT run's
	 * session may start sessions only as requests to the run's own runner, which it reaches through
	 * {@link SessionRequestAccess#REQUESTS_VARIABLE} when the run lets its session request sessions; without that
	 * variable, the run lets the session start none.
	 *
	 * @param harness     the harness whose runner the launcher stands for
	 * @param environment the launcher's environment
	 * @return the reason, or empty if the command is not part of a CAT run or the run lets its session request sessions
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code harness} is blank
	 */
	public static Optional<String> inRunRefusal(String harness, Map<String, String> environment)
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(environment, "environment").isNotNull();
		if (!RUN_VALUE.equals(environment.get(RUN_VARIABLE)))
			return Optional.empty();
		String requests = environment.get(SessionRequestAccess.REQUESTS_VARIABLE);
		if (requests != null && !requests.isBlank())
			return Optional.empty();
		return Optional.of(harness + "-runner refuses to start a session: this command runs in a session of a CAT " +
			"run whose runner lets it request no sessions. Start the run with --session-requests to let its session " +
			"request sessions.");
	}

	/**
	 * Copies only generic operating-system variables and removes CAT/harness home state.
	 * <p>
	 * The copy also holds {@code NVM_DIR}, the Node version manager's directory. A {@code node} launcher that loads the
	 * manager finds it through {@code NVM_DIR}, which defaults to {@code .nvm} in the home directory, and a child's
	 * home is the run's own, so without the variable the Node programs that the harness runs would not start.
	 * <p>
	 * The copy also holds the proxy variables, in upper and lower case: {@code HTTP_PROXY}, {@code HTTPS_PROXY},
	 * {@code ALL_PROXY} and {@code NO_PROXY}. A host that reaches the network only through a proxy names it there,
	 * and a harness that lacks them cannot reach its model service.
	 * <p>
	 * When every proxy variable that names a proxy names one on a loopback address ({@code localhost},
	 * {@code 127.0.0.0/8} or {@code ::1}), the copies of {@code NO_PROXY} and {@code no_proxy} lose their loopback
	 * entries ({@code localhost}, an address or range in {@code 127.0.0.0/8}, and {@code ::1} with or without
	 * brackets) and keep every other entry in its order, so that the child reaches this machine's loopback services,
	 * such as a model gateway, through the proxy. A loopback proxy runs on this machine and can reach its loopback
	 * services. A command sandbox that denies a command direct connections names such a proxy and lists the loopback
	 * addresses in {@code NO_PROXY}, so a child started from that command could not otherwise reach a loopback
	 * service at all; the proxy's address, not a variable that only one harness's sandbox sets, is how this method
	 * recognizes it. A remote proxy cannot reach this machine's loopback services, so with a remote proxy, with a
	 * proxy variable whose value cannot be parsed, or with no proxy, the copies stay unchanged. The proxy variables
	 * are always copied unchanged, and a client connects to its proxy directly rather than through a proxy, so the
	 * proxy stays reachable.
	 * <p>
	 * On Windows, the copy also holds {@code USERPROFILE}, {@code LOCALAPPDATA}, {@code TEMP}, {@code TMP},
	 * {@code PATHEXT} and {@code SystemDrive}. Windows programs read the user's home directory from
	 * {@code USERPROFILE} and its local data folder from {@code LOCALAPPDATA}; a program without {@code TEMP} and
	 * {@code TMP} puts its temporary files in the Windows directory, which a user cannot write; PowerShell finds a
	 * program by its name without an extension only with an extension that {@code PATHEXT} lists; and Codex's MXC
	 * sandbox, the only Windows sandbox that denies reads without an administrator's setup, does not start without
	 * {@code SystemDrive} and {@code LOCALAPPDATA}.
	 *
	 * @param source the host environment
	 *
	 * @return the scrubbed environment
	 */
	public static Map<String, String> scrub(Map<String, String> source)
	{
		requireThat(source, "source").isNotNull();
		Map<String, String> result = new HashMap<>();
		copy(source, result, "PATH");
		copy(source, result, "HOME");
		copy(source, result, "LANG");
		copy(source, result, "LC_ALL");
		copy(source, result, "TERM");
		copy(source, result, "NVM_DIR");
		for (String name : PROXY_VARIABLES)
			copy(source, result, name);
		for (String name : EXCLUSION_VARIABLES)
			copy(source, result, name);
		if (isEveryProxyLoopback(result))
		{
			for (String name : EXCLUSION_VARIABLES)
				removeLoopbackExclusions(result, name);
		}
		if (Platform.current().isWindows())
		{
			copy(source, result, "USERPROFILE");
			copy(source, result, "LOCALAPPDATA");
			copy(source, result, "TEMP");
			copy(source, result, "TMP");
			copy(source, result, "PATHEXT");
			copy(source, result, "SystemDrive");
		}
		return Map.copyOf(result);
	}

	/**
	 * Copies explicitly approved authentication variables into an isolated environment.
	 *
	 * @param source the host environment
	 *
	 * @param destination the mutable isolated environment
	 *
	 * @param names the approved authentication variable names
	 */
	public static void copyAllowed(Map<String, String> source, Map<String, String> destination, Set<String> names)
	{
		requireThat(source, "source").isNotNull();
		requireThat(destination, "destination").isNotNull();
		requireThat(names, "names").isNotNull();
		for (String name : names)
		{
			requireThat(name, "name").isNotBlank();
			copy(source, destination, name);
		}
	}

	/**
	 * Gives a child process a home directory. The environment names it in {@code HOME} and, on Windows, also in
	 * {@code USERPROFILE}, which Windows programs read instead of {@code HOME}. On Windows, the environment also names
	 * the home's {@code AppData\Local} in {@code LOCALAPPDATA}, where Windows programs keep a user's local data and
	 * without which Codex's MXC sandbox does not start; the folder need not exist.
	 *
	 * @param environment the mutable isolated environment
	 *
	 * @param home the child's home directory
	 */
	public static void setHomeDirectory(Map<String, String> environment, Path home)
	{
		requireThat(environment, "environment").isNotNull();
		requireThat(home, "home").isNotNull();
		environment.putAll(homeVariables(home, Platform.current()));
	}

	/**
	 * Returns the variables that give a child process a home directory on a platform, as
	 * {@link #setHomeDirectory(Map, Path)} sets them: {@code HOME} and, on Windows, also {@code USERPROFILE} and
	 * {@code LOCALAPPDATA}.
	 *
	 * @param home     the child's home directory
	 * @param platform the platform that the child runs on
	 * @return each variable's name mapped to its value
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static Map<String, String> homeVariables(Path home, Platform platform)
	{
		requireThat(home, "home").isNotNull();
		requireThat(platform, "platform").isNotNull();
		Map<String, String> variables = new HashMap<>();
		variables.put("HOME", home.toString());
		variables.put(platform.homeVariable(), home.toString());
		if (platform.isWindows())
			variables.put("LOCALAPPDATA", home.resolve("AppData").resolve("Local").toString());
		return Map.copyOf(variables);
	}

	/**
	 * Stops Git commands in an owned project copy from discovering a repository in the host's parent directories.
	 *
	 * @param environment the mutable isolated environment
	 *
	 * @param runDirectory the owned directory immediately above the copied project
	 */
	public static void preventGitDiscoveryAbove(Map<String, String> environment, Path runDirectory)
	{
		requireThat(environment, "environment").isNotNull();
		requireThat(runDirectory, "runDirectory").isNotNull();
		if (!runDirectory.isAbsolute())
			throw new IllegalArgumentException("runDirectory must be absolute");
		environment.put("GIT_CEILING_DIRECTORIES", runDirectory.toString());
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private HarnessEnvironment()
	{
	}

	/**
	 * Returns whether an environment names a proxy and every proxy that it names listens on a loopback address.
	 *
	 * @param environment the child's environment, which holds only non-blank values
	 * @return {@code true} if at least one proxy variable is set and each one names a host on a loopback address
	 */
	private static boolean isEveryProxyLoopback(Map<String, String> environment)
	{
		boolean namesProxy = false;
		for (String name : PROXY_VARIABLES)
		{
			String value = environment.get(name);
			if (value == null)
				continue;
			if (!isLoopbackProxy(value.strip()))
				return false;
			namesProxy = true;
		}
		return namesProxy;
	}

	/**
	 * Returns whether a proxy variable's value names a proxy on a loopback address. A value without a scheme is a host
	 * and port, as command-line tools such as curl read it.
	 *
	 * @param value the variable's value, without surrounding whitespace
	 * @return {@code true} if the value is a URL whose host is a loopback name or address; {@code false} if it names
	 *   another host or cannot be parsed
	 */
	private static boolean isLoopbackProxy(String value)
	{
		String url = value;
		if (!url.contains("://"))
			url = "http://" + url;
		String host;
		try
		{
			host = new URI(url).getHost();
		}
		catch (URISyntaxException _)
		{
			return false;
		}
		return host != null && isLoopbackHost(host);
	}

	/**
	 * Returns whether a host name or address literal denotes this machine's loopback interface, without a name
	 * lookup.
	 *
	 * @param host a host name, an IPv4 address, or an IPv6 address with or without brackets
	 * @return {@code true} if the host is {@code localhost}, an IPv4 address in {@code 127.0.0.0/8}, or the IPv6
	 *   loopback address
	 */
	private static boolean isLoopbackHost(String host)
	{
		if (host.equalsIgnoreCase("localhost"))
			return true;
		String literal = host;
		if (literal.startsWith("[") && literal.endsWith("]"))
			literal = literal.substring(1, literal.length() - 1);
		try
		{
			return InetAddress.ofLiteral(literal).isLoopbackAddress();
		}
		catch (IllegalArgumentException _)
		{
			// A host name other than localhost
			return false;
		}
	}

	/**
	 * Removes the loopback entries from a comma-separated list of hosts that connect without a proxy. An entry is a
	 * loopback entry if it is a loopback host or an address range, such as {@code 127.0.0.0/8}, whose address is one.
	 * The other entries keep their order; the variable is removed if no entry remains.
	 *
	 * @param environment the mutable child environment
	 * @param name        the name of the variable that holds the list
	 */
	private static void removeLoopbackExclusions(Map<String, String> environment, String name)
	{
		String value = environment.get(name);
		if (value == null)
			return;
		StringJoiner kept = new StringJoiner(",");
		for (String entry : value.split(","))
		{
			String host = entry.strip();
			if (host.isEmpty())
				continue;
			int prefix = host.indexOf('/');
			String address = host;
			if (prefix != -1)
				address = host.substring(0, prefix);
			if (!isLoopbackHost(address))
				kept.add(host);
		}
		if (kept.length() == 0)
			environment.remove(name);
		else
			environment.put(name, kept.toString());
	}

	/**
	 * Copies a non-blank environment value.
	 *
	 * @param source the source environment
	 *
	 * @param destination the destination environment
	 *
	 * @param name the variable name
	 */
	private static void copy(Map<String, String> source, Map<String, String> destination, String name)
	{
		assert source != null;
		assert destination != null;
		assert name != null;
		String value = source.get(name);
		if (value != null && !value.isBlank())
			destination.put(name, value);
	}
}
