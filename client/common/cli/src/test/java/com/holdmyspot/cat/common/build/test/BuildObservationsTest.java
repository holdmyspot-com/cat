package com.holdmyspot.cat.common.build.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.build.BuildObservations;
import com.holdmyspot.cat.common.build.BuildObservations.ProcessState;
import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests host and process resource observations using controlled proc records and identities.
 */
public final class BuildObservationsTest
{
	/**
	 * Creates the test.
	 */
	public BuildObservationsTest()
	{
	}

	/**
	 * Supplies unsigned kilobyte members and their exact byte values.
	 *
	 * @return valid proc member and expected bytes
	 */
	@DataProvider
	public Object[][] validMemoryMembers()
	{
		return new Object[][]
		{
			{"MemAvailable: 16777216 kB\n", 17_179_869_184L},
			{"MemAvailable: 0 kB\n", 0L},
			{"MemAvailable: 0001 kB\n", 1024L}
		};
	}

	/**
	 * Verifies the public MemAvailable operation converts only valid exact kilobyte members.
	 *
	 * @param contents complete proc member
	 * @param expected expected bytes
	 * @throws IOException if the fixture cannot be written or the member is rejected
	 */
	@Test(dataProvider = "validMemoryMembers")
	public void availableMemoryReturnsCheckedBytes(String contents, long expected) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-build-observations-"));
			Path meminfo = Files.writeString(directory.resolve("meminfo"), contents);
			requireThat(BuildObservations.availableMemory(meminfo), "availableBytes").isEqualTo(expected);
		}
	}

	/**
	 * Supplies malformed proc members that must never become resource estimates.
	 *
	 * @return invalid member text
	 */
	@DataProvider
	public Object[][] invalidMemoryMembers()
	{
		return new Object[][]
		{
			{"MemAvailable: broken kB\n"},
			{"MemAvailable: 9223372036854775808 kB\n"},
			{"MemAvailable: 9007199254740992 kB\n"},
			{"MemAvailable: 1 MB\n"},
			{"MemAvailable: 1 kB extra\n"},
			{"MemAvailable: -1 kB\n"},
			{"MemAvailable: -0 kB\n"},
			{"MemAvailable: +1 kB\n"},
			{"MemAvailable: 1\n"},
			{"MemAvailable: \n"}
		};
	}

	/**
	 * Verifies malformed or overflowing memory observations fail with the source path.
	 *
	 * @param contents invalid proc member text
	 * @throws IOException if the fixture cannot be written
	 */
	@Test(dataProvider = "invalidMemoryMembers", expectedExceptions = IOException.class)
	public void invalidMemoryMemberIsRejected(String contents) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-build-observations-"));
			Path meminfo = Files.writeString(directory.resolve("meminfo"), contents);
			try
			{
				BuildObservations.availableMemory(meminfo);
			}
			catch (IOException exception)
			{
				requireThat(exception.getMessage(), "message").contains(meminfo.toString());
				throw exception;
			}
		}
	}

	/**
	 * Verifies the public operation refuses an absent MemAvailable field instead of estimating it.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test(expectedExceptions = IOException.class)
	public void absentMemoryObservationFailsClosed() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-build-observations-"));
			Path meminfo = Files.writeString(directory.resolve("meminfo"), "MemFree: 12 kB\n");
			BuildObservations.availableMemory(meminfo);
		}
	}

	/**
	 * Verifies process RSS is published only when the stable identity survives the read.
	 *
	 * @throws IOException if proc fixtures cannot be written or a live observation is invalid
	 */
	@Test
	public void processIdentityReuseIsNotAdopted() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path proc = cleanup.add(Files.createTempDirectory("cat-build-proc-"));
			long pid = 101;
			Files.createDirectories(proc.resolve(Long.toString(pid)));
			Files.writeString(proc.resolve(Long.toString(pid)).resolve("status"), "VmRSS:\t7 kB\n");
			ProcessIdentity initial = new ProcessIdentity(pid, "boot1");
			ProcessIdentity replacement = new ProcessIdentity(pid, "boot2");
			BuildObservations observations = new BuildObservations(proc, new FixedMetrics(pid),
				new SequencedIdentities(initial, replacement));

			requireThat(observations.process(pid).isEmpty(), "processSample").isTrue();
		}
	}

	/**
	 * Verifies a candidate omitted from successful process metrics is discarded when its identity is absent.
	 *
	 * @param operation observation operation that samples the candidate
	 * @throws IOException if the observation rejects a successfully absent candidate
	 */
	@Test(dataProvider = "vanishedCandidateOperations")
	public void vanishedCandidateIsOmitted(String operation) throws IOException
	{
		long pid = 105;
		ProcessIdentity candidate = new ProcessIdentity(pid, "boot105");
		Deque<Optional<ProcessIdentity>> identityResults = new ArrayDeque<>();
		if (operation.equals("process"))
		{
			identityResults.add(Optional.empty());
			identityResults.add(Optional.empty());
		}
		else
		{
			identityResults.add(Optional.of(candidate));
			identityResults.add(Optional.empty());
			identityResults.add(Optional.empty());
		}
		BuildObservations.IdentityLookup identities = requestedPid -> identityResults.removeFirst();
		Map<Long, Deque<Optional<BuildObservations.RawProcess>>> records = Map.of(pid, new ArrayDeque<>());
		BuildObservations observations = new BuildObservations(Path.of("/unused"),
			new ScriptedMetrics(Map.of(pid, 0L), records), identities);

		switch (operation)
		{
			case "process" -> requireThat(observations.process(pid).isEmpty(), "processOmitted").isTrue();
			case "tree" ->
			{
				BuildObservations.TreeSample tree = observations.tree(candidate, Set.of());
				requireThat(tree.alive(), "treeAlive").isEmpty();
				requireThat(tree.aggregateRssBytes(), "treeRss").isEqualTo(0L);
				requireThat(tree.observedProcesses(), "treeProcessCount").isEqualTo(0);
			}
			case "alive" -> requireThat(observations.alive(candidate, Set.of()), "aliveOmitted").isEmpty();
			default -> throw new AssertionError("Unexpected observation operation: " + operation);
		}
	}

	/**
	 * Supplies operations that sample a candidate's lifecycle or resource state.
	 *
	 * @return supported observation operations
	 */
	@DataProvider
	public Object[][] vanishedCandidateOperations()
	{
		return new Object[][]
		{
			{"process"},
			{"tree"},
			{"alive"}
		};
	}

	/**
	 * Verifies stable identity and unknown process metrics do not turn missing metrics into absence.
	 *
	 * @throws IOException when either incomplete observation is rejected
	 */
	@Test
	public void missingMetricsStayRequired() throws IOException
	{
		ProcessIdentity identity = new ProcessIdentity(106, "boot106");
		try
		{
			sample(106, Arrays.asList(null, null), List.of(Optional.of(identity)), null);
			throw new AssertionError("Missing metrics for a stable identity were accepted");
		}
		catch (IOException expected)
		{
			requireThat(expected.getMessage(), "stableIdentityMissingMetrics").contains("No OSHI");
		}
		try
		{
			sample(107, List.of(raw(107, ProcessState.UNKNOWN), raw(107, ProcessState.UNKNOWN)),
				List.of(Optional.empty()), null);
			throw new AssertionError("Unknown metrics for an absent identity were accepted");
		}
		catch (IOException expected)
		{
			requireThat(expected.getMessage(), "absentIdentityUnknownMetrics").contains("unknown");
		}
	}

	/**
	 * Verifies a failed identity lookup cannot be treated as a successful absent identity.
	 *
	 * @throws IOException when the final identity lookup fails
	 */
	@Test
	public void failedIdentityLookupDoesNotOmit() throws IOException
	{
		long pid = 108;
		BuildObservations.IdentityLookup identities = new BuildObservations.IdentityLookup()
		{
			private int reads;

			@Override
			public Optional<ProcessIdentity> find(long requestedPid) throws IOException
			{
				reads += 1;
				if (reads == 1)
					return Optional.empty();
				throw new IOException("Identity lookup failed");
			}
		};
		Map<Long, Deque<Optional<BuildObservations.RawProcess>>> records = Map.of(pid, new ArrayDeque<>());
		BuildObservations observations = new BuildObservations(Path.of("/unused"),
			new ScriptedMetrics(Map.of(pid, 0L), records), identities);

		try
		{
			observations.process(pid);
			throw new AssertionError("Failed identity lookup was treated as absence");
		}
		catch (IOException expected)
		{
			requireThat(expected.getMessage(), "missingMetricsFailure").contains("No OSHI");
			requireThat(expected.getSuppressed().length, "identityLookupFailureCount").isEqualTo(1);
		}
	}

	/**
	 * Verifies a fallback value substituted after a metric exception cannot establish process absence.
	 *
	 * @throws IOException when the initially observed live process lacks its identity
	 */
	@Test
	public void metricFailureFallbackIsNotAbsence() throws IOException
	{
		long pid = 109;
		BuildObservations.Metrics metrics = new BuildObservations.Metrics()
		{
			private int reads;

			@Override
			public double cpuLoad()
			{
				return 0.1;
			}

			@Override
			public int logicalProcessors()
			{
				return 1;
			}

			@Override
			public int availableProcessors()
			{
				return 1;
			}

			@Override
			public Optional<BuildObservations.RawProcess> process(long requestedPid)
			{
				reads += 1;
				if (reads == 1)
					return Optional.of(raw(requestedPid, ProcessState.LIVE));
				throw new IllegalStateException("Metric observation failed");
			}

			@Override
			public Set<Long> descendants(long requestedPid)
			{
				return Set.of();
			}
		};
		BuildObservations observations = new BuildObservations(Path.of("/unused"), metrics, _ -> Optional.empty());

		try
		{
			observations.process(pid);
			throw new AssertionError("A failed metric fallback was treated as process absence");
		}
		catch (IOException expected)
		{
			requireThat(expected.getMessage(), "missingIdentityFailure").contains("no stable identity");
			requireThat(expected.getSuppressed().length, "metricFailureCount").isEqualTo(1);
		}
	}

	/**
	 * Verifies an exited process is omitted even while its identity remains visible and RSS is absent.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void exitedProcessSkipsRss() throws IOException
	{
		ProcessIdentity identity = new ProcessIdentity(201, "boot201");
		Optional<BuildObservations.ProcessSample> sample = sample(201, List.of(raw(201, ProcessState.EXITED)),
			List.of(Optional.of(identity)), null);
		requireThat(sample.isEmpty(), "processSample").isTrue();
	}

	/**
	 * Verifies a confirmed exit is sufficient when no stable identity was captured.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void exitedProcessWithoutIdentityIsOmitted() throws IOException
	{
		requireThat(sample(211, List.of(raw(211, ProcessState.EXITED)), List.of(Optional.empty()), null).isEmpty(),
			"processSample").isTrue();
	}

	/**
	 * Verifies a fresh confirmed exit after a failed live RSS read discards the row.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void liveExitAfterRssFailure() throws IOException
	{
		ProcessIdentity identity = new ProcessIdentity(202, "boot202");
		Optional<BuildObservations.ProcessSample> sample = sample(202,
			List.of(raw(202, ProcessState.LIVE), raw(202, ProcessState.EXITED)),
			List.of(Optional.of(identity)), null);
		requireThat(sample.isEmpty(), "processSample").isTrue();
	}

	/**
	 * Verifies strict RSS parsing remains mandatory for live processes after a failed observation.
	 *
	 * @param rss malformed or absent RSS record
	 * @throws IOException if the fixture cannot be written
	 */
	@Test(dataProvider = "invalidRssMembers", expectedExceptions = IOException.class)
	public void liveProcessStillRequiresStrictRss(String rss) throws IOException
	{
		ProcessIdentity identity = new ProcessIdentity(203, "boot203");
		sample(203, List.of(raw(203, ProcessState.LIVE)), List.of(Optional.of(identity)), rss);
	}

	/**
	 * Verifies lifecycle tracking accepts absent or malformed RSS without inventing a memory value.
	 *
	 * @param rss absent or malformed RSS fixture
	 * @throws IOException if the fixture cannot be written or the live identity is rejected
	 */
	@Test(dataProvider = "invalidRssMembers")
	public void aliveIgnoresAbsentOrMalformedRss(String rss) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path proc = cleanup.add(Files.createTempDirectory("cat-build-proc-"));
			long pid = 212;
			if (rss != null)
			{
				Files.createDirectories(proc.resolve(Long.toString(pid)));
				Files.writeString(proc.resolve(Long.toString(pid)).resolve("status"), rss);
			}
			ProcessIdentity identity = new ProcessIdentity(pid, "boot212");
			BuildObservations observations = new BuildObservations(proc, new FixedMetrics(pid),
				_ -> Optional.of(identity));

			requireThat(observations.alive(identity, Set.of()), "aliveWithoutRss").containsExactly(Set.of(identity));
		}
	}

	/**
	 * Verifies lifecycle results cannot be changed by callers.
	 *
	 * @throws IOException if the live process is rejected
	 */
	@Test
	public void aliveResultIsImmutable() throws IOException
	{
		ProcessIdentity identity = new ProcessIdentity(221, "boot221");
		BuildObservations observations = new BuildObservations(Path.of("/unused"), new FixedMetrics(identity.pid()),
			_ -> Optional.of(identity));
		Set<ProcessIdentity> alive = observations.alive(identity, Set.of());
		boolean immutable;
		try
		{
			alive.add(new ProcessIdentity(222, "boot222"));
			throw new AssertionError("Lifecycle result was mutable");
		}
		catch (UnsupportedOperationException _)
		{
			immutable = true;
		}
		requireThat(immutable, "aliveSetImmutable").isTrue();
	}

	/**
	 * Supplies malformed or absent VmRSS records for live processes.
	 *
	 * @return invalid VmRSS records
	 */
	@DataProvider
	public Object[][] invalidRssMembers()
	{
		return new Object[][]
		{
			{null},
			{"VmRSS:\tbroken kB\n"},
			{"VmRSS:\t-1 kB\n"},
			{"VmRSS:\t1 MB\n"},
			{"VmRSS:\t1 kB extra\n"},
			{"VmRSS:\t9223372036854775808 kB\n"}
		};
	}

	/**
	 * Verifies unknown and missing fresh process records preserve the original live RSS failure.
	 *
	 * @param fresh second process record, or null for a missing record
	 * @throws IOException if the fixture cannot be written
	 */
	@Test(dataProvider = "unconfirmedExitStates", expectedExceptions = IOException.class)
	public void failedRssWithUnconfirmedExitStillFails(BuildObservations.RawProcess fresh) throws IOException
	{
		ProcessIdentity identity = new ProcessIdentity(204, "boot204");
		try
		{
			sample(204, Arrays.asList(raw(204, ProcessState.LIVE), fresh), List.of(Optional.of(identity)), null);
		}
		catch (IOException exception)
		{
			requireThat(exception.getMessage(), "message").contains("VmRSS");
			throw exception;
		}
	}

	/**
	 * Supplies fresh states that do not confirm process exit.
	 *
	 * @return unknown and missing process records
	 */
	@DataProvider
	public Object[][] unconfirmedExitStates()
	{
		return new Object[][]
		{
			{raw(204, ProcessState.UNKNOWN)},
			{null}
		};
	}

	/**
	 * Verifies stale identity replacement still discards the row and a live zero RSS stays valid.
	 *
	 * @throws IOException if the fixture cannot be written or a required observation is invalid
	 */
	@Test
	public void identityReuseAndZeroRss() throws IOException
	{
		ProcessIdentity original = new ProcessIdentity(205, "boot205");
		ProcessIdentity replacement = new ProcessIdentity(205, "boot205reused");
		requireThat(sample(205, List.of(raw(205, ProcessState.LIVE)),
			List.of(Optional.of(original), Optional.of(replacement)), null).isEmpty(), "reusedProcess").isTrue();
		Optional<BuildObservations.ProcessSample> zero = sample(206, List.of(raw(206, ProcessState.LIVE)),
			List.of(Optional.of(new ProcessIdentity(206, "boot206"))), "VmRSS:\t0 kB\n");
		requireThat(zero.orElseThrow().rssBytes(), "zeroRssBytes").isEqualTo(0L);
	}

	/**
	 * Verifies an exited root is omitted while its retained live child remains in the tree.
	 *
	 * @throws IOException if fixtures cannot be written or a required observation is invalid
	 */
	@Test
	public void exitedRootKeepsRetainedLiveChild() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path proc = cleanup.add(Files.createTempDirectory("cat-build-proc-"));
			long rootPid = 207;
			long childPid = 208;
			writeProcess(proc, childPid, 9);
			ProcessIdentity root = new ProcessIdentity(rootPid, "boot207");
			ProcessIdentity child = new ProcessIdentity(childPid, "boot208");
			Map<Long, Deque<Optional<BuildObservations.RawProcess>>> records = new java.util.HashMap<>();
			records.put(rootPid, new ArrayDeque<>(List.of(Optional.of(raw(rootPid, ProcessState.EXITED)))));
			records.put(childPid, new ArrayDeque<>(List.of(Optional.of(raw(childPid, ProcessState.LIVE)))));
			BuildObservations observations = new BuildObservations(proc, new ScriptedMetrics(Map.of(rootPid, 0L,
				childPid, rootPid), records), pid ->
				{
					if (pid == rootPid)
						return Optional.of(root);
					return Optional.of(child);
				});
			BuildObservations.TreeSample tree = observations.tree(root, Set.of(child));
			requireThat(tree.alive(), "alive").containsExactly(Set.of(child));
			requireThat(tree.aggregateRssBytes(), "aggregateRssBytes").isEqualTo(9_216L);
		}
	}

	/**
	 * Verifies lifecycle tracking keeps a retained child after its root exits without reading child RSS.
	 *
	 * @throws IOException if the fixture cannot be written or the live child is rejected
	 */
	@Test
	public void aliveKeepsRetainedChildAfterRootExit() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path proc = cleanup.add(Files.createTempDirectory("cat-build-proc-"));
			long rootPid = 213;
			long childPid = 214;
			Files.createDirectories(proc.resolve(Long.toString(childPid)));
			Files.writeString(proc.resolve(Long.toString(childPid)).resolve("status"), "VmRSS:\tbroken kB\n");
			ProcessIdentity root = new ProcessIdentity(rootPid, "boot213");
			ProcessIdentity child = new ProcessIdentity(childPid, "boot214");
			Map<Long, Deque<Optional<BuildObservations.RawProcess>>> records = new java.util.HashMap<>();
			records.put(rootPid, new ArrayDeque<>(List.of(Optional.of(raw(rootPid, ProcessState.EXITED)))));
			records.put(childPid, new ArrayDeque<>(List.of(Optional.of(raw(childPid, ProcessState.LIVE)))));
			BuildObservations.IdentityLookup identities = pid ->
			{
				if (pid == rootPid)
					return Optional.of(root);
				return Optional.of(child);
			};
			BuildObservations observations = new BuildObservations(proc, new ScriptedMetrics(Map.of(rootPid, 0L,
				childPid, rootPid), records), identities);

			requireThat(observations.alive(root, Set.of(child)), "retainedChildAfterRootExit").
				containsExactly(Set.of(child));
		}
	}

	/**
	 * Verifies lifecycle tracking does not adopt a replacement identity for a reused PID.
	 *
	 * @throws IOException if the live process observation is invalid
	 */
	@Test
	public void aliveDoesNotAdoptReusedIdentity() throws IOException
	{
		ProcessIdentity original = new ProcessIdentity(215, "boot215");
		ProcessIdentity replacement = new ProcessIdentity(215, "boot215reused");
		BuildObservations observations = new BuildObservations(Path.of("/unused"), new FixedMetrics(215),
			new SequencedIdentities(original, replacement));

		requireThat(observations.alive(original, Set.of()), "reusedIdentityOmitted").isEmpty();
	}

	/**
	 * Verifies lifecycle tracking omits positively exited actors and rejects unknown states.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void aliveOmitsExitAndRejectsUnknownState() throws IOException
	{
		ProcessIdentity identity = new ProcessIdentity(216, "boot216");
		requireThat(alive(216, raw(216, ProcessState.EXITED), Optional.of(identity)), "exitedActorOmitted").isEmpty();
		try
		{
			alive(216, raw(216, ProcessState.UNKNOWN), Optional.of(identity));
			throw new AssertionError("Unknown process state was accepted");
		}
		catch (IOException expected)
		{
			requireThat(expected.getMessage(), "unknownStateMessage").contains("unknown");
		}
	}

	/**
	 * Verifies lifecycle tracking rejects a process record whose PID differs from the requested PID.
	 *
	 * @throws IOException when OSHI reports the wrong PID
	 */
	@Test(expectedExceptions = IOException.class)
	public void aliveRejectsWrongPid() throws IOException
	{
		alive(217, raw(218, ProcessState.EXITED), Optional.of(new ProcessIdentity(217, "boot217")));
	}

	/**
	 * Verifies lifecycle tracking fails closed when a live descendant has no stable identity.
	 *
	 * @throws IOException when the live descendant lacks an identity
	 */
	@Test(expectedExceptions = IOException.class)
	public void aliveRejectsLiveProcessWithoutIdentity() throws IOException
	{
		long rootPid = 219;
		long childPid = 220;
		ProcessIdentity root = new ProcessIdentity(rootPid, "boot219");
		BuildObservations.IdentityLookup identities = pid ->
		{
			if (pid == rootPid)
				return Optional.of(root);
			return Optional.empty();
		};
		BuildObservations observations = new BuildObservations(Path.of("/unused"),
			new FixedMetrics(rootPid, childPid), identities);

		observations.alive(root, Set.of());
	}

	/**
	 * Observes one lifecycle record with the requested stable identity.
	 *
	 * @param pid process identifier
	 * @param process process record
	 * @param identity stable identity result
	 * @return live identities
	 * @throws IOException if the process is not safely observable
	 */
	private static Set<ProcessIdentity> alive(long pid, BuildObservations.RawProcess process,
		Optional<ProcessIdentity> identity) throws IOException
	{
		Map<Long, Deque<Optional<BuildObservations.RawProcess>>> records = Map.of(pid,
			new ArrayDeque<>(List.of(Optional.of(process))));
		BuildObservations observations = new BuildObservations(Path.of("/unused"),
			new ScriptedMetrics(Map.of(pid, 0L), records), _ -> identity);
		return observations.alive(identity.orElseThrow(), Set.of());
	}

	/**
	 * Verifies a wrong-PID exited record cannot prove the requested process has exited.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test(expectedExceptions = IOException.class)
	public void wrongPidExitedRecordFails() throws IOException
	{
		ProcessIdentity identity = new ProcessIdentity(209, "boot209");
		sample(209, List.of(raw(210, ProcessState.EXITED)), List.of(Optional.of(identity)), null);
	}

	/**
	 * Runs a process observation with scripted OSHI records and identity results.
	 *
	 * @param pid process identifier
	 * @param rawProcesses successive process records; a null item means missing record
	 * @param identityResults successive identity lookup results
	 * @param rss resident-memory fixture text, or null when absent
	 * @return observed process sample, if live and stable
	 * @throws IOException if the process is live but a required observation is invalid
	 */
	private static Optional<BuildObservations.ProcessSample> sample(long pid,
		List<BuildObservations.RawProcess> rawProcesses, List<Optional<ProcessIdentity>> identityResults,
		String rss) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path proc = cleanup.add(Files.createTempDirectory("cat-build-proc-"));
			if (rss != null)
			{
				Files.createDirectories(proc.resolve(Long.toString(pid)));
				Files.writeString(proc.resolve(Long.toString(pid)).resolve("status"), rss);
			}
			Map<Long, Deque<Optional<BuildObservations.RawProcess>>> records = Map.of(pid,
				new ArrayDeque<>(rawProcesses.stream().map(Optional::ofNullable).toList()));
			Deque<Optional<ProcessIdentity>> identities = new ArrayDeque<>(identityResults);
			BuildObservations observations = new BuildObservations(proc,
				new ScriptedMetrics(Map.of(pid, 0L), records), requestedPid ->
				{
					if (identities.size() > 1)
						return identities.removeFirst();
					return identities.getFirst();
				});
			return observations.process(pid);
		}
	}

	/**
	 * Creates a test process record.
	 *
	 * @param pid process identifier
	 * @param state execution state
	 * @return process record
	 */
	private static BuildObservations.RawProcess raw(long pid, ProcessState state)
	{
		return new BuildObservations.RawProcess(pid, 0L, state);
	}

	private static final class ScriptedMetrics implements BuildObservations.Metrics
	{
		private final Map<Long, Long> parentByPid;
		private final Map<Long, Deque<Optional<BuildObservations.RawProcess>>> records;

		/**
		 * Creates scripted process rows and a parent table.
		 *
		 * @param parentByPid process parent identifiers
		 * @param records queued process records by PID
		 */
		private ScriptedMetrics(Map<Long, Long> parentByPid,
			Map<Long, Deque<Optional<BuildObservations.RawProcess>>> records)
		{
			this.parentByPid = parentByPid;
			this.records = records;
		}

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
		public Optional<BuildObservations.RawProcess> process(long pid)
		{
			Deque<Optional<BuildObservations.RawProcess>> processRecords = records.get(pid);
			if (processRecords == null || processRecords.isEmpty())
				return Optional.empty();
			if (processRecords.size() == 1)
				return processRecords.getFirst();
			return processRecords.removeFirst();
		}

		@Override
		public Set<Long> descendants(long pid)
		{
			return parentByPid.entrySet().stream().filter(entry -> entry.getValue() == pid).map(Map.Entry::getKey).
				collect(java.util.stream.Collectors.toUnmodifiableSet());
		}
	}

	/**
	 * Verifies an inaccessible identity for a process still reported alive fails closed.
	 *
	 * @throws IOException if the process observation is incomplete
	 */
	@Test(expectedExceptions = IOException.class)
	public void presentProcessWithoutStableIdentityFails() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path proc = cleanup.add(Files.createTempDirectory("cat-build-proc-"));
			long pid = 102;
			Files.createDirectories(proc.resolve(Long.toString(pid)));
			Files.writeString(proc.resolve(Long.toString(pid)).resolve("status"), "VmRSS:\t7 kB\n");
			BuildObservations observations = new BuildObservations(proc, new FixedMetrics(pid), _ -> Optional.empty());
			observations.process(pid);
		}
	}

	/**
	 * Verifies process-tree RSS counts each current stable identity once.
	 *
	 * @throws IOException if proc fixtures cannot be written or a required observation is invalid
	 */
	@Test
	public void treeSumsEachCurrentIdentityOnce() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path proc = cleanup.add(Files.createTempDirectory("cat-build-proc-"));
			long rootPid = 103;
			long childPid = 104;
			writeProcess(proc, rootPid, 5);
			writeProcess(proc, childPid, 9);
			ProcessIdentity root = new ProcessIdentity(rootPid, "boot3");
			BuildObservations observations = new BuildObservations(proc, new FixedMetrics(rootPid, childPid),
				pid -> Optional.of(new ProcessIdentity(pid, "boot" + (pid - 100))));
			BuildObservations.TreeSample sample = observations.tree(root, Set.of());

			requireThat(sample.aggregateRssBytes(), "aggregateRssBytes").isEqualTo(14_336L);
			requireThat(sample.observedProcesses(), "observedProcesses").isEqualTo(2);
		}
	}

	/**
	 * Writes one minimal proc status record for a process.
	 *
	 * @param proc proc fixture root
	 * @param pid process identifier
	 * @param kilobytes resident kilobytes
	 * @throws IOException if the fixture cannot be written
	 */
	private static void writeProcess(Path proc, long pid, long kilobytes) throws IOException
	{
		Files.createDirectories(proc.resolve(Long.toString(pid)));
		Files.writeString(proc.resolve(Long.toString(pid)).resolve("status"), "VmRSS:\t" + kilobytes + " kB\n");
	}

	private static final class FixedMetrics implements BuildObservations.Metrics
	{
		private final Map<Long, Long> parentByPid;

		/**
		 * Creates a process table with the first process as root and remaining processes as its children.
		 *
		 * @param pids process identifiers
		 */
		private FixedMetrics(long... pids)
		{
			Map<Long, Long> parents = new java.util.HashMap<>();
			long rootPid = pids[0];
			for (long pid : pids)
			{
				long parentPid = 0;
				if (pid != rootPid)
					parentPid = rootPid;
				parents.put(pid, parentPid);
			}
			parentByPid = Map.copyOf(parents);
		}

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
		public Optional<BuildObservations.RawProcess> process(long pid)
		{
			Long parentPid = parentByPid.get(pid);
			if (parentPid == null)
				return Optional.empty();
			return Optional.of(new BuildObservations.RawProcess(pid, parentPid, ProcessState.LIVE));
		}

		@Override
		public Set<Long> descendants(long pid)
		{
			return parentByPid.entrySet().stream().filter(entry -> entry.getValue() == pid).map(Map.Entry::getKey).
				collect(java.util.stream.Collectors.toUnmodifiableSet());
		}
	}

	private static final class SequencedIdentities implements BuildObservations.IdentityLookup
	{
		private final ProcessIdentity first;
		private final ProcessIdentity second;
		private int reads;

		/**
		 * Creates identities that change after the first lookup.
		 *
		 * @param first initial process identity
		 * @param second replacement process identity
		 */
		private SequencedIdentities(ProcessIdentity first, ProcessIdentity second)
		{
			this.first = first;
			this.second = second;
		}

		@Override
		public Optional<ProcessIdentity> find(long pid)
		{
			reads += 1;
			if (reads == 1)
				return Optional.of(first);
			return Optional.of(second);
		}
	}
}
