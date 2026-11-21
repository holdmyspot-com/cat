package com.holdmyspot.cat.codex.runner.test;

import com.holdmyspot.cat.codex.runner.CodexStartupHookComparison;
import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.runner.AbstractHarnessRunnerScope;
import com.holdmyspot.cat.common.runner.HeartbeatTicker;
import com.holdmyspot.cat.common.runner.TrialPluginInput;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.secrets.ExecutableFilesSecrets;
import com.holdmyspot.cat.common.test.ExitedProcess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.SkipException;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

/**
 * Verifies the filesystem view used by native startup-hook comparison processes.
 */
public final class CodexStartupHookComparisonTest
{
	/**
	 * Requires the owner-denied directory and file masks after the trial-home mount in both plugin setup and
	 * native hook inspection.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void masksOwnerDenialsInNativeProcesses() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path deniedDirectory = Files.createDirectory(fixture.home.resolve("private"));
			Path deniedFile = Files.writeString(fixture.root.resolve("denied.txt"), "dummy\n");
			Path alias = Files.createSymbolicLink(fixture.root.resolve("alias"), deniedFile);
			Path allowed = Files.writeString(fixture.root.resolve("allowed.txt"), "dummy\n");
			IOException stopped = expectThrows(IOException.class, () -> fixture.resolve(
				List.of(deniedDirectory, alias, allowed), List.of(allowed)));

			requireThat(stopped.getMessage(), "message").isEqualTo("Reached the app-server launch");
			requireThat(fixture.commands.size(), "launches").isEqualTo(3);
			for (List<String> command : fixture.commands)
			{
				int writable = Collections.indexOfSubList(command,
					List.of("--bind", fixture.home.toString(), fixture.home.toString()));
				int denied = Collections.indexOfSubList(command,
					List.of("--tmpfs", deniedDirectory.toString(), "--remount-ro", deniedDirectory.toString()));
				int fileDestination = command.indexOf(deniedFile.toString());
				requireThat(writable, "writableHomeMount").isGreaterThanOrEqualTo(0);
				requireThat(denied, "directoryMask").isGreaterThan(writable);
				requireThat(fileDestination, "fileMask").isGreaterThan(writable + 2);
				requireThat(command.get(fileDestination - 2), "fileMaskMode").isEqualTo("--ro-bind");
				requireThat(command.contains(allowed.toString()), "allowedPathRemounted").isFalse();
				requireThat(command.contains("--unshare-pid"), "privateProcessView").isTrue();
			}
		}
	}

	/**
	 * Refuses a native comparison before launch if a denied subtree has a partial read exception.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesUnrepresentableReadPolicy() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path deniedDirectory = Files.createDirectory(fixture.root.resolve("private"));
			Path allowed = Files.writeString(deniedDirectory.resolve("allowed.txt"), "dummy\n");

			IOException refused = expectThrows(IOException.class, () -> fixture.resolve(List.of(deniedDirectory),
				List.of(allowed)));

			requireThat(refused.getMessage(), "message").contains("cannot represent").contains(deniedDirectory.toString());
			requireThat(fixture.commands.isEmpty(), "launchedProcess").isTrue();
		}
	}

	/**
	 * Refuses a comparison whose owner cannot read the sandbox launcher itself.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesUnreadableSandboxLauncher() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path denied = fixture.bin.resolve("bwrap");
			IOException refused = expectThrows(IOException.class, () -> fixture.resolve(List.of(denied), List.of()));

			requireThat(refused.getMessage(), "message").contains("cannot read").contains(denied.toString());
			requireThat(fixture.commands.isEmpty(), "launchedProcess").isTrue();
		}
	}

	/**
	 * A frozen plugin and a fresh comparison home, with native process launches recorded instead of executed.
	 */
	private static final class Fixture implements AutoCloseable
	{
		private final TemporaryDirectories cleanup = new TemporaryDirectories();
		private final List<List<String>> commands = new ArrayList<>();
		private final Path root;
		private final Path home;
		private final Path trial;
		private final Path source;
		private final Path bin;
		private final AbstractHarnessRunnerScope scope;

		/**
		 * Prepares the fresh workspaces and inert executable paths.
		 *
		 * @throws IOException if the fixture cannot be created
		 */
		private Fixture() throws IOException
		{
			if (!Platform.current().isLinux())
				throw new SkipException("Native startup-hook inspection uses the Linux filesystem view");
			try (TestJvmScope build = new TestJvmScope())
			{
				root = cleanup.add(Files.createTempDirectory(build.temporaryDirectory(), "hook-read-policy-")).toRealPath();
			}
			home = Files.createDirectories(root.resolve("comparison/home"));
			trial = Files.createDirectory(root.resolve("comparison/project"));
			source = Files.createDirectories(root.resolve("plugin/.codex-plugin")).getParent();
			Files.writeString(source.resolve(".codex-plugin/plugin.json"), "{\"name\":\"example\"}");
			bin = Files.createDirectory(root.resolve("bin"));
			for (String program : List.of("bwrap", "codex"))
			{
				Path file = Files.createFile(bin.resolve(program));
				requireThat(file.toFile().setExecutable(true), "setExecutable").isTrue();
			}
			Clock clock = new ManualClock(Instant.EPOCH);
			ExecutableFiles executables = ExecutableFilesSecrets.getExecutableFilesAccess().create(() -> {}, clock,
				builder ->
				{
					commands.add(List.copyOf(builder.command()));
					if (builder.command().contains("app-server"))
						throw new IOException("Reached the app-server launch");
					return new ExitedProcess(builder, 0, "", "");
				});
			scope = new AbstractHarnessRunnerScope(new TestJvmScope(root, executables))
			{
				@Override
				public Clock clock()
				{
					return clock;
				}

				@Override
				protected HeartbeatTicker heartbeatTicker()
				{
					throw new AssertionError("Startup-hook inspection never starts a heartbeat");
				}
			};
		}

		/**
		 * Runs the comparison until it tries to inspect native hook state.
		 *
		 * @param denied directories or files the owner cannot read
		 * @param allowed explicit owner read exceptions
		 * @throws IOException if the policy cannot be enforced or inspection reaches the recorded launch
		 * @throws InterruptedException if setup is interrupted
		 */
		private void resolve(List<Path> denied, List<Path> allowed) throws IOException, InterruptedException
		{
			ParentPolicy policy = new ParentPolicy(true, List.of(home), List.of(), denied, allowed, HostAccess.NONE,
				false, List.of());
			CodexStartupHookComparison.Context context = new CodexStartupHookComparison.Context(trial,
				root.resolve("host"), "codex", Map.of("PATH", bin.toString()), policy, scope);
			CodexStartupHookComparison.resolve(context, List.of(new TrialPluginInput(source, Optional.empty())), trial,
				trial, home, "compare");
		}

		@Override
		public void close() throws IOException
		{
			try (TemporaryDirectories _ = cleanup)
			{
				scope.close();
			}
		}
	}
}
