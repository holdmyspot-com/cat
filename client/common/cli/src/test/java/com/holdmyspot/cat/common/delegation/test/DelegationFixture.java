package com.holdmyspot.cat.common.delegation.test;

import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.test.ProcessCompletionContract;
import com.holdmyspot.cat.common.build.BuildAdmission;
import com.holdmyspot.cat.common.build.BuildObservations;
import com.holdmyspot.cat.common.build.BuildObservations.ProcessState;
import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.json.JsonMapper;

/**
 * Owns one delegation test's synthetic host observations, process state, and admission files.
 */
final class DelegationFixture
{
	private final AtomicLong nextPid = new AtomicLong(20_000);
	private final Set<Long> livePids = ConcurrentHashMap.newKeySet();
	private final Map<Long, FixtureProcess> liveProcesses = new ConcurrentHashMap<>();
	private final long controllerPid;
	private final Path root;
	private final Path contextPath;
	private final BuildAdmission.HostContext context;
	private final BuildObservations observations;

	/**
	 * Creates isolated admission inputs beneath an existing test-owned directory.
	 *
	 * @param root existing test-owned directory for the fixture
	 * @throws IOException if the synthetic admission inputs cannot be written or read
	 */
	DelegationFixture(Path root) throws IOException
	{
		this.root = root.toAbsolutePath().normalize();
		this.controllerPid = ProcessHandle.current().pid();
		Path admissionRoot = this.root.resolve("admission");
		Path procRoot = admissionRoot.resolve("proc");
		Files.createDirectories(procRoot.resolve("sys/kernel/random"));
		UUID bootId = UUID.nameUUIDFromBytes(admissionRoot.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
		Files.writeString(procRoot.resolve("sys/kernel/random/boot_id"), bootId.toString());
		Files.writeString(procRoot.resolve("meminfo"), "MemAvailable: 16777216 kB\n");
		Path controllerStatus = Files.createDirectories(procRoot.resolve(Long.toString(this.controllerPid))).
			resolve("status");
		Files.writeString(controllerStatus, "VmRSS:\t16 kB\n");
		this.contextPath = admissionRoot.resolve("context.json");
		String contextId = UUID.nameUUIDFromBytes((admissionRoot + "context").
			getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
		Files.writeString(this.contextPath, "{\"format_version\":1,\"context_id\":\"" + contextId +
			"\",\"host_boot_id\":\"" + bootId + "\",\"ledger_path\":\"ledger\",\"authority_id\":\"test\"," +
			"\"authority_actor\":\"test\",\"authority_evidence_paths\":[\"authority.txt\"]," +
			"\"cleanup_owner\":\"test\"}");
		Files.writeString(admissionRoot.resolve("authority.txt"), "synthetic test authority");
		this.context = BuildAdmission.HostContext.read(JsonMapper.builder().build(), this.contextPath);
		BuildObservations.Metrics metrics = new BuildObservations.Metrics()
		{
			@Override
			public double cpuLoad()
			{
				return 0.1;
			}

			@Override
			public int logicalProcessors()
			{
				return 4;
			}

			@Override
			public int availableProcessors()
			{
				return 4;
			}

			@Override
			public java.util.Optional<BuildObservations.RawProcess> process(long pid)
			{
				if (pid != controllerPid && !liveProcesses.containsKey(pid))
					return java.util.Optional.empty();
				ProcessState state = ProcessState.EXITED;
				if (pid == controllerPid || livePids.contains(pid))
					state = ProcessState.LIVE;
				if (state == ProcessState.EXITED)
					return java.util.Optional.of(new BuildObservations.RawProcess(pid, 1, state));
				try
				{
					Path status = Files.createDirectories(procRoot.resolve(Long.toString(pid))).resolve("status");
					Files.writeString(status, "VmRSS:\t16 kB\n");
					return java.util.Optional.of(new BuildObservations.RawProcess(pid, 1, state));
				}
				catch (IOException exception)
				{
					throw new IllegalStateException(exception);
				}
			}

			@Override
			public Set<Long> descendants(long pid)
			{
				return Set.of();
			}
		};
		this.observations = new BuildObservations(procRoot, metrics, pid ->
		{
			if (pid != controllerPid && !liveProcesses.containsKey(pid))
				return java.util.Optional.empty();
			ProcessIdentity identity = new ProcessIdentity(pid, "fixture" + pid);
			return java.util.Optional.of(identity);
		});
	}

	/**
	 * Returns the admission controller backed by this fixture's context and process observations.
	 *
	 * @param scope process services used by admission
	 * @return admission controller sharing this fixture's ledger
	 * @throws IOException if admission inputs cannot be read
	 */
	BuildAdmission admission(JvmScope scope) throws IOException
	{
		return new BuildAdmission(scope, context, observations, scope.clock(), Map.of("PATH", "/controlled/path"));
	}

	/**
	 * Returns the exact request context path owned by this fixture.
	 *
	 * @return this fixture's admission context path
	 */
	Path contextPath()
	{
		return contextPath;
	}

	/**
	 * Simulates the minimal Git and Maven requests used by delegation preparation.
	 *
	 * @param builder configured child process
	 * @return completed simulated process
	 * @throws IOException if simulated filesystem writes fail
	 */
	Process start(ProcessBuilder builder) throws IOException
	{
		return start(builder, false, false);
	}

	/**
	 * Simulates Git and controlled scope or exit behavior for a Maven probe.
	 *
	 * @param builder configured child process
	 * @param undeclaredOutput whether Maven writes outside reviewed output roots
	 * @param nonzeroMaven whether Maven exits unsuccessfully
	 * @return completed simulated process
	 * @throws IOException if simulated filesystem writes fail
	 */
	Process start(ProcessBuilder builder, boolean undeclaredOutput, boolean nonzeroMaven) throws IOException
	{
		java.util.List<String> command = builder.command();
		Path directory = builder.directory().toPath();
		String output = "";
		if (command.getFirst().equals("git"))
		{
			if (command.get(1).equals("rev-parse"))
				output = directory + "\n";
			else if (command.get(1).equals("hash-object"))
				output = "fake-blob\n";
			else if (command.get(1).equals("write-tree"))
				output = "fake-tree\n";
			else if (command.get(1).equals("commit-tree"))
				output = "fake-commit\n";
		}
		else
		{
			createMavenLog(builder);
			Path outputRoot = directory.resolve("client/common/cli/target");
			Files.createDirectories(outputRoot);
			Files.writeString(outputRoot.resolve("probe.txt"), "gate output");
			if (undeclaredOutput)
				Files.writeString(directory.resolve("outside-output.txt"), "undeclared output");
		}
		if (command.getFirst().equals("git"))
			return completed(0, output);
		int mavenExitCode = 0;
		if (nonzeroMaven)
			mavenExitCode = 1;
		return running(mavenExitCode, output);
	}

	/**
	 * Returns a controlled Maven start failure after allowing the Git setup requests.
	 *
	 * @param builder configured child process
	 * @return simulated Git process
	 * @throws IOException if Maven is started or simulated Git setup fails
	 */
	Process failMaven(ProcessBuilder builder) throws IOException
	{
		if (!builder.command().getFirst().equals("git"))
			throw new IOException("controlled Maven start failure");
		return start(builder);
	}

	/**
	 * Creates the Maven-owned diagnostic file specified in the controlled command arguments.
	 *
	 * @param builder simulated Maven process
	 * @throws IOException if the log cannot be created
	 */
	static void createMavenLog(ProcessBuilder builder) throws IOException
	{
		java.util.List<String> command = builder.command();
		int index = command.indexOf("--log-file");
		if (index == -1 || index + 1 >= command.size())
			throw new IOException("Controlled Maven command omitted --log-file");
		Path path = Path.of(command.get(index + 1));
		Files.createDirectories(path.getParent());
		Files.createFile(path);
	}

	/**
	 * Opens a running synthetic process for shared completion checks.
	 *
	 * @return an owned process fixture
	 */
	ProcessCompletionContract.Fixture processFixture()
	{
		FixtureProcess process = runningProcess(0, "");
		return new ProcessCompletionContract.Fixture()
		{
			@Override
			public Process process()
			{
				return process;
			}

			@Override
			public void complete(int status)
			{
				process.complete(status);
			}

			@Override
			public boolean observerAlive()
			{
				return livePids.contains(process.pid());
			}

			@Override
			public boolean otherActorAlive()
			{
				return false;
			}

			@Override
			public void close() throws IOException
			{
				process.destroyForcibly();
				try
				{
					process.close();
				}
				finally
				{
					liveProcesses.remove(process.pid());
				}
			}
		};
	}

	/**
	 * Indicates whether the external fixture observer considers a process live.
	 *
	 * @param process process under observation
	 * @return whether the process identity is live
	 */
	boolean observesLive(Process process)
	{
		return livePids.contains(process.pid());
	}

	/**
	 * Creates a completed synthetic child without registering a live process identity.
	 *
	 * @param exitCode terminal exit code
	 * @param stdout process output
	 * @return completed synthetic process
	 */
	Process completed(int exitCode, String stdout)
	{
		long pid = nextPid.incrementAndGet();
		if (pid == controllerPid)
			pid = nextPid.incrementAndGet();
		return new FixtureProcess(pid, exitCode, stdout, livePids, liveProcesses, exitCode);
	}

	/**
	 * Creates a process that remains live until an observation or control releases it.
	 *
	 * @param exitCode selected terminal status
	 * @param stdout captured child output
	 * @return running process
	 */
	Process running(int exitCode, String stdout)
	{
		FixtureProcess process = runningProcess(exitCode, stdout);
		process.completeAfterWaitStarts();
		return process;
	}

	/**
	 * Registers a live process in the fixture observer.
	 *
	 * @param exitCode selected terminal status
	 * @param stdout captured child output
	 * @return running process control
	 */
	private FixtureProcess runningProcess(int exitCode, String stdout)
	{
		long pid = nextPid.incrementAndGet();
		if (pid == controllerPid)
			pid = nextPid.incrementAndGet();
		return new FixtureProcess(pid, null, stdout, livePids, liveProcesses, exitCode);
	}

	private static final class FixtureProcess extends Process
	{
		private final long pid;
		private final AtomicReference<Integer> exitCode;
		private final java.io.InputStream output;
		private final Set<Long> livePids;
		private final int plannedExitCode;
		private final CountDownLatch completion = new CountDownLatch(1);
		private final CountDownLatch waitEntered = new CountDownLatch(1);

		/**
		 * Creates a completed process or a process released by fixture control.
		 *
		 * @param pid process identity used by the synthetic observer
		 * @param exitCode terminal status, or null while running
		 * @param stdout process output
		 * @param livePids process identities owned by this fixture
		 * @param liveProcesses process controls owned by this fixture
		 * @param plannedExitCode status supplied by the process owner after observation
		 */
		FixtureProcess(long pid, Integer exitCode, String stdout, Set<Long> livePids,
			Map<Long, FixtureProcess> liveProcesses, int plannedExitCode)
		{
			this.pid = pid;
			this.exitCode = new AtomicReference<>(exitCode);
			this.output = new java.io.ByteArrayInputStream(stdout.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			this.livePids = livePids;
			this.plannedExitCode = plannedExitCode;
			if (exitCode == null)
			{
				livePids.add(pid);
				liveProcesses.put(pid, this);
			}
			else
				completion.countDown();
		}

		/**
		 * Releases the selected Maven result from fixture control after a wait begins.
		 */
		private void completeAfterWaitStarts()
		{
			Thread.ofPlatform().start(() ->
			{
				try
				{
					waitEntered.await();
					complete(plannedExitCode);
				}
				catch (InterruptedException _)
				{
					Thread.currentThread().interrupt();
				}
			});
		}

		/**
		 * Returns the status selected by fixture control.
		 *
		 * @return selected terminal status
		 */
		int plannedExitCode()
		{
			return plannedExitCode;
		}

		/**
		 * Publishes terminal state to the process and external observer.
		 *
		 * @param status terminal status
		 */
		private void complete(int status)
		{
			if (exitCode.compareAndSet(null, status))
			{
				livePids.remove(pid);
				completion.countDown();
				waitEntered.countDown();
			}
		}

		@Override
		public long pid()
		{
			return pid;
		}

		@Override
		public java.io.OutputStream getOutputStream()
		{
			return java.io.OutputStream.nullOutputStream();
		}

		@Override
		public java.io.InputStream getInputStream()
		{
			return output;
		}

		@Override
		public java.io.InputStream getErrorStream()
		{
			return java.io.InputStream.nullInputStream();
		}

		@Override
		public int waitFor() throws InterruptedException
		{
			waitEntered.countDown();
			completion.await();
			return exitValue();
		}

		@Override
		public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException
		{
			waitEntered.countDown();
			return completion.await(timeout, unit);
		}

		@Override
		public int exitValue()
		{
			Integer status = exitCode.get();
			if (status == null)
				throw new IllegalThreadStateException("Fixture process is still running");
			return status;
		}

		@Override
		public boolean isAlive()
		{
			return exitCode.get() == null;
		}

		@Override
		public void destroy()
		{
			complete(143);
		}

		@Override
		public Process destroyForcibly()
		{
			destroy();
			return this;
		}
	}
}
