package com.holdmyspot.cat.common.runner.login.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.login.KeyringReach;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;

/**
 * Tests that {@link KeyringReach} decides from the top-level session's policy, the platform and the server's session
 * bus address whether that session could itself reach the operating system's keyring.
 */
public final class KeyringReachTest
{
	private static final Platform LINUX = Platform.detect("Linux", "amd64");
	private static final Platform MACOS = Platform.detect("Mac OS X", "aarch64");
	private static final Platform WINDOWS = Platform.detect("Windows 11", "amd64");

	/**
	 * Creates the test.
	 */
	public KeyringReachTest()
	{
	}

	/**
	 * Returns a sandboxed policy.
	 *
	 * @param localServices {@code true} if the policy lets its commands reach local services
	 * @param readDenials   the paths that the policy denies reading
	 * @return the policy
	 */
	private static ParentPolicy sandboxed(boolean localServices, List<Path> readDenials)
	{
		return new ParentPolicy(true, List.of(), List.of(), readDenials, List.of(), HostAccess.ALL, false,
			localServices, List.of());
	}

	/**
	 * Verifies that an unsandboxed top-level session reaches the keyring on every platform, whatever its bus.
	 *
	 * @throws IOException if a path cannot be resolved
	 */
	@Test
	public void unsandboxedReaches() throws IOException
	{
		// Requirement: R-I19
		for (Platform platform : List.of(LINUX, MACOS, WINDOWS))
		{
			requireThat(KeyringReach.unreachable(ParentPolicy.UNSANDBOXED, platform, Map.of()), platform.id()).
				isEmpty();
		}
	}

	/**
	 * Verifies that on Linux a sandboxed top-level session reaches the keyring when its policy lets its commands reach
	 * local services and does not deny reading the session bus socket, whether the bus is named by
	 * {@code DBUS_SESSION_BUS_ADDRESS} or found in {@code $XDG_RUNTIME_DIR}.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void linuxReachesUndeniedBus() throws IOException
	{
		// Requirement: R-I19
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path runtime = cleanup.add(Files.createTempDirectory("keyring-reach-")).toRealPath();
			Path bus = Files.createFile(runtime.resolve("bus"));
			Path elsewhere = Files.createDirectory(runtime.resolve("elsewhere"));
			ParentPolicy policy = sandboxed(true, List.of(elsewhere));

			requireThat(KeyringReach.unreachable(policy, LINUX, Map.of(KeyringReach.BUS_ADDRESS_VARIABLE,
				"unix:path=" + bus + ",guid=0123")), "address").isEmpty();
			requireThat(KeyringReach.unreachable(policy, LINUX, Map.of(KeyringReach.RUNTIME_FOLDER_VARIABLE,
				runtime.toString())), "runtimeFolder").isEmpty();
		}
	}

	/**
	 * Verifies that on Linux a sandboxed top-level session cannot reach the keyring when its policy keeps its commands
	 * from local services, and that the reason names the settings that give that access.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void linuxNeedsLocalServices() throws IOException
	{
		// Requirement: R-I19
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path runtime = cleanup.add(Files.createTempDirectory("keyring-reach-")).toRealPath();
			Path bus = Files.createFile(runtime.resolve("bus"));

			Optional<String> reason = KeyringReach.unreachable(sandboxed(false, List.of()), LINUX,
				Map.of(KeyringReach.BUS_ADDRESS_VARIABLE, "unix:path=" + bus));

			requireThat(reason, "reason").isPresent();
			requireThat(reason.get(), "reason").contains("full network access").contains("allowAllUnixSockets");
		}
	}

	/**
	 * Verifies that on Linux a policy that denies reading the bus socket's real path keeps the top-level session from
	 * the keyring, also when the bus address reaches the socket through a symbolic link, and that the reason names the
	 * denial.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void linuxDeniedBusIsUnreachable() throws IOException
	{
		// Requirement: R-I19
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("keyring-reach-")).toRealPath();
			Path runtime = Files.createDirectory(root.resolve("runtime"));
			Path bus = Files.createFile(runtime.resolve("bus"));
			Path link = Files.createSymbolicLink(root.resolve("link"), runtime);

			Optional<String> direct = KeyringReach.unreachable(sandboxed(true, List.of(runtime)), LINUX,
				Map.of(KeyringReach.BUS_ADDRESS_VARIABLE, "unix:path=" + bus));
			Optional<String> throughLink = KeyringReach.unreachable(sandboxed(true, List.of(runtime)), LINUX,
				Map.of(KeyringReach.BUS_ADDRESS_VARIABLE, "unix:path=" + link.resolve("bus")));
			Optional<String> deniedLink = KeyringReach.unreachable(sandboxed(true, List.of(link)), LINUX,
				Map.of(KeyringReach.BUS_ADDRESS_VARIABLE, "unix:path=" + bus));

			requireThat(direct, "direct").isPresent();
			requireThat(direct.get(), "direct").contains(runtime.toString());
			requireThat(throughLink, "throughLink").isPresent();
			requireThat(deniedLink, "deniedLink").isPresent();
		}
	}

	/**
	 * Verifies that on Linux a bus in the abstract namespace, or no bus at all, keeps a sandboxed top-level session from
	 * the keyring.
	 *
	 * @throws IOException if a path cannot be resolved
	 */
	@Test
	public void linuxAbstractOrMissingBusIsUnreachable() throws IOException
	{
		// Requirement: R-I19
		ParentPolicy policy = sandboxed(true, List.of());

		Optional<String> abstractBus = KeyringReach.unreachable(policy, LINUX,
			Map.of(KeyringReach.BUS_ADDRESS_VARIABLE, "unix:abstract=/tmp/dbus-ABCDEF,guid=0123"));
		Optional<String> missing = KeyringReach.unreachable(policy, LINUX, Map.of());

		requireThat(abstractBus, "abstractBus").isPresent();
		requireThat(abstractBus.get(), "abstractBus").contains("abstract");
		requireThat(missing, "missing").isPresent();
		requireThat(missing.get(), "missing").contains("no session bus");
	}

	/**
	 * Verifies that on macOS a sandboxed top-level session reaches the Keychain exactly when its policy lets its
	 * commands reach local services, and that on Windows a sandboxed one never reaches the Credential Manager.
	 *
	 * @throws IOException if a path cannot be resolved
	 */
	@Test
	public void macosAndWindowsVerdicts() throws IOException
	{
		// Requirement: R-I19
		requireThat(KeyringReach.unreachable(sandboxed(true, List.of()), MACOS, Map.of()), "macosReaches").isEmpty();
		requireThat(KeyringReach.unreachable(sandboxed(false, List.of()), MACOS, Map.of()), "macosClosed").
			isPresent();
		requireThat(KeyringReach.unreachable(sandboxed(true, List.of()), WINDOWS, Map.of()), "windows").isPresent();
	}
}
