package com.holdmyspot.cat.common.build;

import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import oshi.software.os.OSProcess;
import oshi.spi.SystemInfoProvider;
import oshi.util.FileUtil;
import oshi.spi.SystemInfoFactory;
import oshi.hardware.CentralProcessor;
import oshi.software.os.OperatingSystem;
import oshi.hardware.HardwareAbstractionLayer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reads host and process resource observations used to decide whether a build worker can start.
 */
public final class BuildObservations
{
	private final Path procRoot;
	private final Metrics metrics;
	private final IdentityLookup identities;

	/**
	 * Creates observations from an explicit proc filesystem root and metric providers.
	 *
	 * @param procRoot   root of the proc filesystem used for host and process records
	 * @param metrics    provider of CPU, process and descendant observations
	 * @param identities lookup for stable process identities
	 * @throws NullPointerException if any argument is null
	 */
	public BuildObservations(Path procRoot, Metrics metrics, IdentityLookup identities)
	{
		this.procRoot = Objects.requireNonNull(procRoot, "procRoot").toAbsolutePath().normalize();
		this.metrics = Objects.requireNonNull(metrics, "metrics");
		this.identities = Objects.requireNonNull(identities, "identities");
	}

	/**
	 * Creates the Linux observation provider using OSHI's native-free provider and JDK process discovery.
	 *
	 * @param identities lookup for stable process identities
	 * @return the system observation provider
	 * @throws NullPointerException if {@code identities} is null
	 * @throws UnsupportedOperationException if the current operating system is not Linux
	 */
	public static BuildObservations system(IdentityLookup identities)
	{
		Objects.requireNonNull(identities, "identities");
		String operatingSystem = System.getProperty("os.name");
		if (!"Linux".equals(operatingSystem))
			throw new UnsupportedOperationException("Build resource observations require Linux");

		return new BuildObservations(Path.of("/proc"), systemMetrics(), identities);
	}

	/**
	 * Creates the system metric provider for composing resource and process observations.
	 *
	 * @return fresh OSHI metrics using the native-free system provider
	 */
	public static Metrics systemMetrics()
	{
		return new SystemMetrics(SystemInfoFactory.create());
	}

	/**
	 * Reads the MemAvailable field and converts its exact kilobyte member to bytes.
	 *
	 * @param meminfo proc meminfo file
	 * @return available memory in bytes
	 * @throws NullPointerException if {@code meminfo} is null
	 * @throws IOException if MemAvailable is absent, unreadable, malformed, signed, in another unit, or overflows
	 */
	public static long availableMemory(Path meminfo) throws IOException
	{
		Objects.requireNonNull(meminfo, "meminfo");
		return requiredKilobytes(meminfo, "MemAvailable");
	}

	/**
	 * Reads the current host boot identity, available memory, CPU load and CPU count.
	 *
	 * @return the current host sample
	 * @throws IOException if a required host value cannot be read or validated
	 */
	public HostSample host() throws IOException
	{
		Path bootIdPath = procRoot.resolve("sys/kernel/random/boot_id");
		UUID bootId;
		try
		{
			bootId = UUID.fromString(Files.readString(bootIdPath).strip());
		}
		catch (IllegalArgumentException exception)
		{
			throw new IOException("Invalid boot UUID in " + bootIdPath, exception);
		}

		double cpuLoad = metrics.cpuLoad();
		int logicalProcessors = metrics.logicalProcessors();
		int availableProcessors = metrics.availableProcessors();
		boolean validCpuLoad = Double.isFinite(cpuLoad) && cpuLoad >= 0.0 && cpuLoad <= 1.0;
		boolean validProcessorCounts = logicalProcessors > 0 && availableProcessors > 0;
		if (!validCpuLoad || !validProcessorCounts)
			throw new IOException("Invalid host CPU observation in " + procRoot);

		return new HostSample(bootId, availableMemory(procRoot.resolve("meminfo")), cpuLoad,
			Math.min(logicalProcessors, availableProcessors));
	}

	/**
	 * Reads a process only when its stable identity is unchanged across the resource observation.
	 *
	 * @param pid process identifier
	 * @return the process sample, or empty when the process disappeared or its identifier was reused
	 * @throws IOException if a still-live process lacks an identity or required metric observation
	 */
	public Optional<ProcessSample> process(long pid) throws IOException
	{
		Optional<ProcessIdentity> initialIdentity = identities.find(pid);
		try
		{
			RawProcess rawProcess = metrics.process(pid).orElseThrow(() ->
				new IOException("No OSHI process record for PID " + pid));
			if (rawProcess.pid() != pid)
				throw new IOException("OSHI returned PID " + rawProcess.pid() + " for requested PID " + pid);
			if (rawProcess.state() == ProcessState.EXITED)
			{
				identities.find(pid);
				return Optional.empty();
			}
			if (rawProcess.state() == ProcessState.UNKNOWN)
				throw new IOException("OSHI process state is unknown for PID " + pid);
			if (initialIdentity.isEmpty())
				throw new IOException("A live process has no stable identity: " + pid);
			long rssBytes = requiredKilobytes(procRoot.resolve(Long.toString(pid)).resolve("status"), "VmRSS");
			Optional<ProcessIdentity> finalIdentity = identities.find(pid);
			if (!initialIdentity.equals(finalIdentity))
				return Optional.empty();
			return Optional.of(new ProcessSample(initialIdentity.orElseThrow(), rawProcess.parentPid(), rssBytes));
		}
		catch (IOException observationFailure)
		{
			Optional<RawProcess> freshProcess;
			boolean freshMetricsObservedSuccessfully = false;
			try
			{
				freshProcess = metrics.process(pid);
				freshMetricsObservedSuccessfully = true;
			}
			catch (RuntimeException freshFailure)
			{
				observationFailure.addSuppressed(freshFailure);
				freshProcess = Optional.empty();
			}
			Optional<ProcessIdentity> finalIdentity;
			try
			{
				finalIdentity = identities.find(pid);
			}
			catch (IOException identityFailure)
			{
				observationFailure.addSuppressed(identityFailure);
				throw observationFailure;
			}
			if (initialIdentity.isPresent() && !initialIdentity.equals(finalIdentity))
				return Optional.empty();
			boolean identityWasAbsentBeforeAndAfter = initialIdentity.isEmpty() && finalIdentity.isEmpty();
			boolean freshMetricsConfirmedAbsence = freshMetricsObservedSuccessfully && freshProcess.isEmpty();
			if (identityWasAbsentBeforeAndAfter && freshMetricsConfirmedAbsence)
				return Optional.empty();
			if (isConfirmedExit(freshProcess, pid))
				return Optional.empty();
			throw observationFailure;
		}
	}

	/**
	 * Checks whether a fresh record positively identifies the requested PID as exited.
	 *
	 * @param process fresh process record
	 * @param requestedPid requested process identifier
	 * @return whether the matching record confirms exit
	 */
	private static boolean isConfirmedExit(Optional<RawProcess> process, long requestedPid)
	{
		return process.filter(rawProcess -> rawProcess.pid() == requestedPid).
			filter(rawProcess -> rawProcess.state() == ProcessState.EXITED).isPresent();
	}

	/**
	 * Samples the root, its descendants, and retained live identities without adopting a reused PID.
	 *
	 * @param root stable identity of the build process
	 * @param retained stable identities observed in earlier samples
	 * @return current live identities and their aggregate resident memory
	 * @throws NullPointerException if either argument or a member of {@code retained} is null
	 * @throws IOException if any still-live process lacks a required observation
	 * @throws ArithmeticException if the aggregate resident-memory sum overflows
	 */
	public TreeSample tree(ProcessIdentity root, Set<ProcessIdentity> retained) throws IOException
	{
		Objects.requireNonNull(root, "root");
		Set<ProcessIdentity> retainedIdentities = Set.copyOf(Objects.requireNonNull(retained, "retained"));
		Set<Long> candidatePids = new LinkedHashSet<>();
		Map<Long, ProcessIdentity> requiredIdentities = new LinkedHashMap<>();
		Optional<ProcessIdentity> currentRootIdentity = identities.find(root.pid());
		if (currentRootIdentity.isPresent() && currentRootIdentity.orElseThrow().equals(root))
		{
			candidatePids.add(root.pid());
			requiredIdentities.put(root.pid(), root);
			candidatePids.addAll(metrics.descendants(root.pid()));
		}

		for (ProcessIdentity retainedIdentity : retainedIdentities)
		{
			Optional<ProcessIdentity> currentIdentity = identities.find(retainedIdentity.pid());
			if (currentIdentity.isPresent() && currentIdentity.orElseThrow().equals(retainedIdentity))
			{
				candidatePids.add(retainedIdentity.pid());
				requiredIdentities.put(retainedIdentity.pid(), retainedIdentity);
				candidatePids.addAll(metrics.descendants(retainedIdentity.pid()));
			}
		}

		Set<ProcessIdentity> alive = new LinkedHashSet<>();
		Map<ProcessIdentity, Long> rssByIdentity = new LinkedHashMap<>();
		long aggregateRssBytes = 0;
		for (long pid : candidatePids)
		{
			Optional<ProcessSample> processSample = process(pid);
			if (processSample.isEmpty())
				continue;
			ProcessSample sample = processSample.orElseThrow();
			ProcessIdentity requiredIdentity = requiredIdentities.get(pid);
			if (requiredIdentity != null && !requiredIdentity.equals(sample.identity()))
				continue;
			alive.add(sample.identity());
			rssByIdentity.put(sample.identity(), sample.rssBytes());
			aggregateRssBytes = Math.addExact(aggregateRssBytes, sample.rssBytes());
		}
		return new TreeSample(Set.copyOf(alive), aggregateRssBytes, alive.size(), Map.copyOf(rssByIdentity));
	}

	/**
	 * Tracks the root, its descendants, and retained live identities without reading resident memory.
	 *
	 * @param root stable identity of the build process
	 * @param retained stable identities observed in earlier samples
	 * @return current live identities
	 * @throws NullPointerException if either argument or a member of {@code retained} is null
	 * @throws IOException if a still-live process lacks a valid identity or state observation
	 */
	public Set<ProcessIdentity> alive(ProcessIdentity root, Set<ProcessIdentity> retained) throws IOException
	{
		Objects.requireNonNull(root, "root");
		Set<ProcessIdentity> retainedIdentities = Set.copyOf(Objects.requireNonNull(retained, "retained"));
		Set<Long> candidatePids = new LinkedHashSet<>();
		Map<Long, ProcessIdentity> requiredIdentities = new LinkedHashMap<>();
		Optional<ProcessIdentity> currentRootIdentity = identities.find(root.pid());
		if (currentRootIdentity.isPresent() && currentRootIdentity.orElseThrow().equals(root))
		{
			candidatePids.add(root.pid());
			requiredIdentities.put(root.pid(), root);
			candidatePids.addAll(metrics.descendants(root.pid()));
		}

		for (ProcessIdentity retainedIdentity : retainedIdentities)
		{
			Optional<ProcessIdentity> currentIdentity = identities.find(retainedIdentity.pid());
			if (currentIdentity.isPresent() && currentIdentity.orElseThrow().equals(retainedIdentity))
			{
				candidatePids.add(retainedIdentity.pid());
				requiredIdentities.put(retainedIdentity.pid(), retainedIdentity);
				candidatePids.addAll(metrics.descendants(retainedIdentity.pid()));
			}
		}

		Set<ProcessIdentity> alive = new LinkedHashSet<>();
		for (long pid : candidatePids)
		{
			Optional<ProcessIdentity> identity = liveIdentity(pid);
			if (identity.isEmpty())
				continue;
			ProcessIdentity requiredIdentity = requiredIdentities.get(pid);
			if (requiredIdentity == null || requiredIdentity.equals(identity.orElseThrow()))
				alive.add(identity.orElseThrow());
		}
		return Set.copyOf(alive);
	}

	/**
	 * Reads a process state only when its stable identity is unchanged across the observation.
	 *
	 * @param pid process identifier
	 * @return the stable identity, or empty when the process exited or its identifier was reused
	 * @throws IOException if a still-live process lacks a valid identity or state observation
	 */
	private Optional<ProcessIdentity> liveIdentity(long pid) throws IOException
	{
		Optional<ProcessIdentity> initialIdentity = identities.find(pid);
		try
		{
			RawProcess rawProcess = metrics.process(pid).orElseThrow(() ->
				new IOException("No OSHI process record for PID " + pid));
			if (rawProcess.pid() != pid)
				throw new IOException("OSHI returned PID " + rawProcess.pid() + " for requested PID " + pid);
			if (rawProcess.state() == ProcessState.EXITED)
			{
				identities.find(pid);
				return Optional.empty();
			}
			if (rawProcess.state() == ProcessState.UNKNOWN)
				throw new IOException("OSHI process state is unknown for PID " + pid);
			if (initialIdentity.isEmpty())
				throw new IOException("A live process has no stable identity: " + pid);
			Optional<ProcessIdentity> finalIdentity = identities.find(pid);
			if (!initialIdentity.equals(finalIdentity))
				return Optional.empty();
			return initialIdentity;
		}
		catch (IOException observationFailure)
		{
			Optional<RawProcess> freshProcess;
			boolean freshMetricsObservedSuccessfully = false;
			try
			{
				freshProcess = metrics.process(pid);
				freshMetricsObservedSuccessfully = true;
			}
			catch (RuntimeException freshFailure)
			{
				observationFailure.addSuppressed(freshFailure);
				freshProcess = Optional.empty();
			}
			Optional<ProcessIdentity> finalIdentity;
			try
			{
				finalIdentity = identities.find(pid);
			}
			catch (IOException identityFailure)
			{
				observationFailure.addSuppressed(identityFailure);
				throw observationFailure;
			}
			if (initialIdentity.isPresent() && !initialIdentity.equals(finalIdentity))
				return Optional.empty();
			boolean identityWasAbsentBeforeAndAfter = initialIdentity.isEmpty() && finalIdentity.isEmpty();
			boolean freshMetricsConfirmedAbsence = freshMetricsObservedSuccessfully && freshProcess.isEmpty();
			if (identityWasAbsentBeforeAndAfter && freshMetricsConfirmedAbsence)
				return Optional.empty();
			if (isConfirmedExit(freshProcess, pid))
				return Optional.empty();
			throw observationFailure;
		}
	}

	/**
	 * Reads a stable process identity from the configured authority.
	 *
	 * @param pid process identifier
	 * @return the current stable identity, if present
	 * @throws IOException if identity lookup fails
	 */
	Optional<ProcessIdentity> identity(long pid) throws IOException
	{
		return identities.find(pid);
	}

	/**
	 * Reads one required proc member as unsigned kilobytes and returns its byte value.
	 *
	 * @param source proc file containing the member
	 * @param memberName exact proc member name
	 * @return the member value in bytes
	 * @throws IOException if the member is absent, malformed, or overflows
	 */
	private static long requiredKilobytes(Path source, String memberName) throws IOException
	{
		Map<String, String> members = FileUtil.getKeyValueMapFromFile(source.toString(), ":");
		String rawMember = members.get(memberName);
		if (!members.containsKey(memberName) || rawMember == null || rawMember.isBlank())
			throw new IOException("No usable " + memberName + " observation in " + source);

		try (Scanner input = new Scanner(rawMember))
		{
			if (!input.hasNext())
				throw new NoSuchElementException("No value token");
			String rawValue = input.next();
			if (rawValue.startsWith("+") || rawValue.startsWith("-"))
				throw invalidMember(source, memberName, rawMember, "must be unsigned", null);
			long value = Long.parseLong(rawValue);
			if (value < 0)
				throw invalidMember(source, memberName, rawMember, "must be nonnegative", null);
			if (!input.hasNext())
				throw new NoSuchElementException("No unit token");
			String unit = input.next();
			if (!"kB".equals(unit))
				throw invalidMember(source, memberName, rawMember, "must use kB", null);
			if (input.hasNext())
				throw invalidMember(source, memberName, rawMember, "contains trailing data", null);
			return Math.multiplyExact(value, 1024L);
		}
		catch (NoSuchElementException | NumberFormatException | ArithmeticException exception)
		{
			throw invalidMember(source, memberName, rawMember, "contains an invalid or overflowing byte count", exception);
		}
	}

	/**
	 * Creates a contextual failure for a member that cannot represent an unsigned kilobyte value.
	 *
	 * @param source proc file containing the member
	 * @param memberName exact proc member name
	 * @param rawMember unmodified member text
	 * @param reason failed member constraint
	 * @param cause parsing or arithmetic failure, or null for a semantic constraint failure
	 * @return contextual I/O failure
	 */
	private static IOException invalidMember(Path source, String memberName, String rawMember, String reason,
		Throwable cause)
	{
		String message = "Invalid " + memberName + " member in " + source + ": " + reason + " (" + rawMember + ")";
		if (cause == null)
			return new IOException(message);
		return new IOException(message, cause);
	}

	/**
	 * Looks up one stable process identity.
	 */
	@FunctionalInterface
	public interface IdentityLookup
	{
		/**
		 * Finds the stable identity of a process.
		 *
		 * @param pid process identifier
		 * @return the stable process identity, or empty when the process is absent
		 * @throws IOException if a present process has no stable start identity
		 */
		Optional<ProcessIdentity> find(long pid) throws IOException;
	}

	/**
	 * Supplies resource metrics and descendant process identifiers.
	 */
	public interface Metrics
	{
		/**
		 * Reads one fresh system CPU-load interval.
		 *
		 * @return CPU load in the inclusive range from zero to one
		 */
		double cpuLoad();

		/**
		 * Returns the logical processor count.
		 *
		 * @return positive logical processor count when available
		 */
		int logicalProcessors();

		/**
		 * Returns the processor count available to the current process.
		 *
		 * @return positive available processor count when available
		 */
		int availableProcessors();

		/**
		 * Looks up the process record required for PID and parent PID.
		 *
		 * @param pid process identifier
		 * @return the process record, or empty when OSHI has no record
		 */
		Optional<RawProcess> process(long pid);

		/**
		 * Returns the current descendants of a process.
		 *
		 * @param pid process identifier
		 * @return descendant process identifiers
		 */
		Set<Long> descendants(long pid);
	}

	/**
	 * OSHI process identity and execution state needed to validate resident-memory records.
	 *
	 * @param pid process identifier
	 * @param parentPid parent process identifier
	 * @param state observed process execution state
	 */
	public record RawProcess(long pid, long parentPid, ProcessState state)
	{
		/**
		 * Creates a process record with a known non-null execution state.
		 *
		 * @param pid process identifier
		 * @param parentPid parent process identifier
		 * @param state observed process execution state
		 * @throws NullPointerException if {@code state} is null
		 */
		public RawProcess
		{
			Objects.requireNonNull(state, "state");
		}
	}

	/**
	 * Distinguishes confirmed liveness from confirmed exit and unavailable state.
	 */
	public enum ProcessState
	{
		LIVE,
		EXITED,
		UNKNOWN
	}

	/**
	 * Host resource values used by one admission decision.
	 *
	 * @param bootId Linux boot UUID
	 * @param availableBytes available memory in bytes
	 * @param cpuLoad system CPU load from zero to one
	 * @param cpuCount the smaller of logical and process-available processor counts
	 */
	public record HostSample(UUID bootId, long availableBytes, double cpuLoad, int cpuCount)
	{
	}

	/**
	 * A stable process identity and its resident memory observation.
	 *
	 * @param identity process identity
	 * @param parentPid parent process identifier
	 * @param rssBytes resident set size in bytes
	 */
	public record ProcessSample(ProcessIdentity identity, long parentPid, long rssBytes)
	{
	}

	/**
	 * Resident memory for all identities observed in one process-tree sample.
	 *
	 * @param alive stable identities still present
	 * @param aggregateRssBytes resident memory summed over those identities
	 * @param observedProcesses number of identities included in the sum
	 * @param rssByIdentity current RSS rows included in the sum
	 */
	public record TreeSample(Set<ProcessIdentity> alive, long aggregateRssBytes, int observedProcesses,
		Map<ProcessIdentity, Long> rssByIdentity)
	{
		/**
		 * Creates a tree sample with an immutable set of live identities.
		 *
		 * @param alive stable identities still present
		 * @param aggregateRssBytes resident memory summed over those identities
		 * @param observedProcesses number of identities included in the sum
		 * @param rssByIdentity current RSS rows included in the sum
		 * @throws NullPointerException if {@code alive} is null or contains null
		 */
		public TreeSample
		{
			alive = Set.copyOf(Objects.requireNonNull(alive, "alive"));
			rssByIdentity = Map.copyOf(Objects.requireNonNull(rssByIdentity, "rssByIdentity"));
		}
	}

	/**
	 * Supplies Linux system measurements from OSHI and the JDK.
	 */
	private static final class SystemMetrics implements Metrics
	{
		private final CentralProcessor processor;
		private final OperatingSystem operatingSystem;

		/**
		 * Creates a system metric adapter for one OSHI provider.
		 *
		 * @param system OSHI system information provider
		 */
		private SystemMetrics(SystemInfoProvider system)
		{
			HardwareAbstractionLayer hardware = system.getHardware();
			processor = hardware.getProcessor();
			operatingSystem = system.getOperatingSystem();
		}

		@Override
		public double cpuLoad()
		{
			return processor.getSystemCpuLoad(1000L);
		}

		@Override
		public int logicalProcessors()
		{
			return processor.getLogicalProcessorCount();
		}

		@Override
		public int availableProcessors()
		{
			return Runtime.getRuntime().availableProcessors();
		}

		@Override
		public Optional<RawProcess> process(long pid)
		{
			int processId;
			try
			{
				processId = Math.toIntExact(pid);
			}
			catch (ArithmeticException _)
			{
				return Optional.empty();
			}
			OSProcess process = operatingSystem.getProcess(processId);
			if (process == null)
				return Optional.empty();
			OSProcess.State osState = process.getState();
			ProcessState state;
			if (osState == null)
				state = ProcessState.UNKNOWN;
			else
				state = switch (osState)
				{
					case ZOMBIE -> ProcessState.EXITED;
					case NEW, RUNNING, SLEEPING, WAITING, STOPPED, SUSPENDED -> ProcessState.LIVE;
					case OTHER, INVALID -> ProcessState.UNKNOWN;
				};
			return Optional.of(new RawProcess(process.getProcessID(), process.getParentProcessID(), state));
		}

		@Override
		public Set<Long> descendants(long pid)
		{
			return ProcessHandle.of(pid).map(process ->
				{
					try (Stream<ProcessHandle> descendants = process.descendants())
					{
						return descendants.map(ProcessHandle::pid).collect(Collectors.toUnmodifiableSet());
					}
				}).orElseGet(Set::of);
		}
	}
}
