package com.holdmyspot.cat.common.runner.launcher.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.ExclusiveFileLock;
import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.PluginInfo;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel;
import com.holdmyspot.cat.common.runner.launcher.LauncherRecord;
import com.holdmyspot.cat.common.runner.launcher.ParentPolicySource;
import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import com.holdmyspot.cat.common.runner.launcher.SessionLauncherService;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests the work of a harness's {@code session-launcher} program: it serves the harness process that its folder's
 * record names until that process ends or the launcher is terminated, and then removes its folder.
 */
public final class SessionLauncherServiceTest
{
	private static final String HARNESS = "harness-a";
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public SessionLauncherServiceTest()
	{
	}

	/**
	 * Verifies that the launcher serves the harness process that its record names, with the policy of that record's
	 * session, until the process ends, and then deletes its folder.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void servesUntilHarnessEnds() throws IOException
	{
		// Requirement: R-I11
		try (Fixture fixture = new Fixture())
		{
			List<LauncherRecord> policyRecords = new ArrayList<>();
			List<Boolean> liveFolder = new ArrayList<>();
			int status = fixture.serve(record ->
			{
				policyRecords.add(record);
				return fixture.policy();
			}, () ->
			{
				liveFolder.add(Files.isRegularFile(fixture.folder.resolve(SessionLauncherChannel.HEARTBEAT_FILE)));
				fixture.processes.end(fixture.harness);
			}, _ ->
			{
			});

			requireThat(status, "status").isEqualTo(0);
			requireThat(policyRecords, "policyRecords").isEqualTo(List.of(fixture.record));
			requireThat(liveFolder, "liveFolder").isEqualTo(List.of(true));
			requireThat(Files.exists(fixture.folder, LinkOption.NOFOLLOW_LINKS), "folderExists").isFalse();
			requireThat(fixture.error(), "error").isEmpty();
		}
	}

	/**
	 * Verifies that terminating the launcher ends it as its harness's end would, deleting its folder, while the
	 * harness process still runs.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void terminationEndsLauncher() throws IOException
	{
		// Requirement: R-I11
		try (Fixture fixture = new Fixture())
		{
			List<Runnable> onTermination = new ArrayList<>();
			int status = fixture.serve(_ -> fixture.policy(), () -> onTermination.getFirst().run(),
				onTermination::add);

			requireThat(status, "status").isEqualTo(0);
			requireThat(onTermination.size(), "onTermination").isEqualTo(1);
			requireThat(fixture.processes.isAlive(fixture.harness), "harnessAlive").isTrue();
			requireThat(Files.exists(fixture.folder, LinkOption.NOFOLLOW_LINKS), "folderExists").isFalse();
		}
	}

	/**
	 * Verifies that a launcher started while another launcher serves the same harness process leaves that launcher's
	 * folder alone and exits.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void leavesHarnessThatAnotherLauncherServes() throws IOException
	{
		// Requirement: R-I11
		try (Fixture fixture = new Fixture();
			ExclusiveFileLock _ = fixture.fileLocks.tryLock(fixture.folder.resolve(SessionLauncherChannel.LOCK_FILE),
				file -> FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)).orElseThrow())
		{
			List<Runnable> onTermination = new ArrayList<>();
			int status = fixture.serve(_ -> fixture.policy(), () ->
			{
				throw new AssertionError("The launcher served");
			}, onTermination::add);

			requireThat(status, "status").isEqualTo(0);
			requireThat(onTermination, "onTermination").isEmpty();
			requireThat(fixture.folder.resolve(SessionLauncherChannel.LAUNCHER_FILE), "record").isRegularFile();
		}
	}

	/**
	 * Verifies that a launcher whose folder holds the record of a harness process that the folder does not name serves
	 * nothing and reports why.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void refusesRecordOfAnotherHarnessProcess() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			ProcessIdentity other = fixture.processes.start(200, Optional.empty(), List.of("harness-a"));
			new LauncherRecord(HARNESS, other, "session-1", fixture.home, Optional.empty(), "startup", fixture.home,
				PluginInfo.CAT_VERSION).write(json, fixture.folder.resolve(SessionLauncherChannel.LAUNCHER_FILE));

			int status = fixture.serve(_ -> fixture.policy(), () ->
			{
				throw new AssertionError("The launcher served");
			}, _ ->
			{
			});

			requireThat(status, "status").isNotEqualTo(0);
			requireThat(fixture.error(), "error").contains(fixture.folder.toString());
			requireThat(Files.exists(fixture.folder.resolve(SessionLauncherChannel.HEARTBEAT_FILE),
				LinkOption.NOFOLLOW_LINKS), "heartbeat").isFalse();
		}
	}

	/**
	 * A user's home with the launcher folder of a running harness process, whose record the hook wrote.
	 */
	private final class Fixture implements AutoCloseable
	{
		private final TemporaryDirectories cleanup = new TemporaryDirectories();
		private final Path home;
		private final SessionLauncherChannel channel;
		private final FakeProcessTable processes = new FakeProcessTable();
		private final ProcessIdentity harness = processes.start(100, Optional.empty(), List.of(HARNESS));
		private final FileLocks fileLocks = new FileLocks();
		private final ByteArrayOutputStream error = new ByteArrayOutputStream();
		private final Path folder;
		private final LauncherRecord record;

		/**
		 * Creates the fixture.
		 *
		 * @throws IOException if setup fails
		 */
		private Fixture() throws IOException
		{
			try (TestJvmScope jvm = new TestJvmScope())
			{
				home = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "launcher-")).toRealPath();
			}
			channel = new SessionLauncherChannel(home);
			folder = Files.createDirectories(channel.launcherDirectory(HARNESS, harness.pid(), harness.start()));
			record = new LauncherRecord(HARNESS, harness, "session-1", home, Optional.empty(), "startup", home,
				PluginInfo.CAT_VERSION);
			record.write(json, folder.resolve(SessionLauncherChannel.LAUNCHER_FILE));
		}

		/**
		 * Returns the served session's policy.
		 *
		 * @return a source of a sandboxed policy that writes the home folder
		 */
		private ParentPolicySource policy()
		{
			ParentPolicy policy = new ParentPolicy(true, List.of(home), List.of(), List.of(), List.of(),
				HostAccess.listed(Set.of("model.example")), false, List.of());
			return new ParentPolicySource()
			{
				@Override
				public String harnessName()
				{
					return "Harness A";
				}

				@Override
				public String permissionEntries(List<Path> writes, Set<String> hosts)
				{
					return "add the entries to the settings of Harness A";
				}

				@Override
				public ParentPolicy read(Optional<String> askingThread)
				{
					return policy;
				}
			};
		}

		/**
		 * Runs the launcher on its folder.
		 *
		 * @param policies      returns the policy source of a record's session
		 * @param pause         runs between polls
		 * @param onTermination receives the work that runs when the launcher is terminated
		 * @return the launcher's exit status
		 * @throws IOException if the launcher fails
		 */
		private int serve(Function<LauncherRecord, ParentPolicySource> policies, Runnable pause,
			Consumer<Runnable> onTermination) throws IOException
		{
			return SessionLauncherService.serve(channel, folder, HARNESS, policies, (_, _, _) ->
			{
				throw new IOException("No child is requested");
			}, processes, fileLocks, Clock.systemUTC(), new TrackedExecutor(), pause::run, onTermination,
				new PrintStream(error, true, UTF_8));
		}

		/**
		 * Returns the launcher's error output.
		 *
		 * @return the output
		 */
		private String error()
		{
			return error.toString(UTF_8);
		}

		@Override
		public void close() throws IOException
		{
			cleanup.close();
		}
	}
}
