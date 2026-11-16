package com.holdmyspot.cat.common.build;

import com.holdmyspot.cat.common.ExclusiveFileLock;
import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.core.StreamReadFeature;

/**
 * Serializes reservations in the common host ledger.
 */
public final class BuildReservations
{
	private final FileLocks locks;
	private final BuildAdmission.HostContext context;
	private final JsonMapper mapper = JsonMapper.builder().build();

	/**
	 * Creates the reservation store for a host-wide ledger.
	  *
	 * @param fileLocks process lock coordinator
	  *
	 * @param context shared host context
	  *
	 * @param clock clock for reservation time decisions
	 */
	public BuildReservations(FileLocks fileLocks, BuildAdmission.HostContext context, Clock clock)
	{
		locks = Objects.requireNonNull(fileLocks, "fileLocks");
		this.context = Objects.requireNonNull(context, "context");
		Objects.requireNonNull(clock, "clock");
	}

	/**
	 * Returns the configured shared ledger directory without creating it.
	 *
	 * @return shared ledger root
	 */
	public Path ledgerPath()
	{
		return context.ledgerPath();
	}

	/**
	 * Returns the context identity used by retained receipts.
	 *
	 * @return shared context UUID
	 */
	public UUID contextId()
	{
		return context.contextId();
	}

	/**
	 * Returns the expected host boot identity used by retained receipts.
	 *
	 * @return host boot UUID
	 */
	public UUID hostBootId()
	{
		return context.hostBootId();
	}

	/**
	 * Records the latest observed reason that a job cannot yet be admitted.
	 *
	 * @param job requested job
	 * @param evidence owned evidence directory
	 * @param host latest host sample
	 * @param heldBytes bytes held by active reservations
	 * @param candidateBytes estimated bytes needed by the job
	 * @param activeWorkers active scheduler worker count
	 * @param reason reason admission was deferred
	 * @return deferred claim carrying its receipt identity
	 * @throws IOException if the ledger or receipt cannot be read or written
	 */
	private Claim deferred(BuildAdmission.Job job, Path evidence, BuildObservations.HostSample host, long heldBytes,
		long candidateBytes, int activeWorkers, String reason) throws IOException
	{
		Claim claim = new Claim(Claim.Kind.DEFERRED, null, job, candidateBytes, -1, null);
		long generation = currentGeneration();
		return writeAdmissionReceipt(claim, evidence, host, generation, generation, heldBytes,
			candidateBytes, activeWorkers, "deferred", reason);
	}

	/**
	 * Reads the latest serialized ledger generation.
	 *
	 * @return current ledger generation
	 * @throws IOException if the ledger is invalid or unavailable
	 */
	private long currentGeneration() throws IOException
	{
		return ((Number) read(context.ledgerPath().resolve("ledger.json")).get("generation")).longValue();
	}

	/**
	 * Persists the resource decision's observation and generation metadata.
	 *
	 * @param claim resource decision to record
	 * @param evidence owned evidence directory
	 * @param host host resource sample
	 * @param generationBefore ledger generation before the decision
	 * @param generationAfter ledger generation after the decision
	 * @param heldBytes existing reserved bytes
	 * @param candidateBytes requested reserved bytes
	 * @param activeWorkers active worker count
	 * @param decision decision category
	 * @param reason decision explanation
	 * @return claim carrying the persisted receipt identity
	 * @throws IOException if the receipt cannot be persisted
	 */
	private Claim writeAdmissionReceipt(Claim claim, Path evidence, BuildObservations.HostSample host,
		long generationBefore, long generationAfter, long heldBytes, long candidateBytes, int activeWorkers,
		String decision, String reason) throws IOException
	{
		Files.createDirectories(evidence);
		String receiptId = UUID.randomUUID().toString();
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("format_version", 1);
		receipt.put("context_id", context.contextId().toString());
		receipt.put("host_boot_id", context.hostBootId().toString());
		receipt.put("reservation_id", claim.reservationId());
		receipt.put("job_id", claim.job().jobId());
		receipt.put("assignment_id", claim.job().assignmentId());
		receipt.put("family_id", claim.job().gate().familyId());
		receipt.put("generation_before", generationBefore);
		receipt.put("generation_after", generationAfter);
		receipt.put("decision", decision);
		receipt.put("available_bytes", host.availableBytes());
		receipt.put("held_bytes", heldBytes);
		if (decision.equals("calibration"))
			receipt.put("candidate_bytes", null);
		else
			receipt.put("candidate_bytes", candidateBytes);
		receipt.put("cpu_load", host.cpuLoad());
		receipt.put("cpu_count", host.cpuCount());
		receipt.put("active_workers", activeWorkers);
		receipt.put("reason", reason);
		Path diagnostics = claim.job().invocation().resolve("diagnostics/wrapper.log").toAbsolutePath().normalize();
		receipt.put("diagnostics_path", evidence.toAbsolutePath().normalize().relativize(diagnostics).toString());
		Path receiptFile = evidence.resolve("admission-" + receiptId + ".json");
		Files.writeString(receiptFile, mapper.writeValueAsString(receipt));
		return new Claim(claim.kind(), claim.reservationId(), claim.job(), claim.bytes(), claim.generation(), receiptId);
	}

	/**
	 * Records or defers one atomic resource claim.
	  *
	 * @param job requested job
	  *
	 * @param observations fresh resource provider
	  *
	 * @param evidence owned evidence directory
	  *
	 * @return typed claim decision
	  *
	 * @throws IOException if the ledger or observations are invalid
	 */
	public Claim tryClaim(BuildAdmission.Job job, BuildObservations observations, Path evidence) throws IOException
	{
		Objects.requireNonNull(job, "job");
		Objects.requireNonNull(observations, "observations");
		Files.createDirectories(context.ledgerPath());
		Path lockFile = context.ledgerPath().resolve("ledger.lock");
		if (!Files.exists(lockFile)) Files.createFile(lockFile);
		synchronized (locks.monitor(lockFile))
		{
			Optional<ExclusiveFileLock> acquired = locks.tryLock(lockFile,
				path -> java.nio.channels.FileChannel.open(path, StandardOpenOption.WRITE));
			if (acquired.isEmpty()) throw new IOException("Build ledger lock is already held: " + lockFile);
			try (ExclusiveFileLock held = acquired.orElseThrow())
			{
				if (!held.isValid()) throw new IOException("Build ledger lock became invalid: " + lockFile);
				BuildObservations.HostSample host = observations.host();
				if (!host.bootId().equals(context.hostBootId()))
					throw new BuildAdmission.Failure(BuildAdmission.Failure.Kind.HOST_MISMATCH, lockFile, job.jobId(), null,
						"Observed host boot identity does not match build context");
				ProcessIdentity owner = observations.identity(ProcessHandle.current().pid()).orElseThrow(() ->
					new IOException("Build controller process has no stable host identity"));
				Path ledger = context.ledgerPath().resolve("ledger.json");
				Map<String, Object> state = read(ledger);
				List<Map<String, Object>> reservations = records(state.get("reservations"));
				List<Map<String, Object>> calibrations = records(state.get("calibrations"));
				boolean calibrationExists = calibrations.stream().anyMatch(item ->
					job.gate().familyId().equals(item.get("family_id")) &&
					job.sourceManifestId().equals(item.get("source_manifest_id")) &&
					"complete".equals(item.get("coverage_result")));
				boolean calibrationActive = reservations.stream().
					anyMatch(item -> Boolean.TRUE.equals(item.get("calibration")));
				if (calibrationActive || !calibrationExists && !reservations.isEmpty())
					return deferred(job, evidence, host, 0, 0, reservations.size(),
						"another calibration or uncalibrated build owns the host");
				long heldBytes = 0;
				for (Map<String, Object> reservation : reservations)
				{
					Object reserved = reservation.get("reserved_bytes");
					if (Boolean.TRUE.equals(reservation.get("calibration")))
						continue;
					if (!(reserved instanceof Number number) || number.longValue() <= 0)
						throw new IOException("Invalid build reservation bytes: " + reservation.get("reservation_id"));
					heldBytes = Math.addExact(heldBytes, number.longValue());
				}
				long candidate = 0;
				if (calibrationExists)
				{
					Map<String, Object> calibration = calibrations.stream().filter(item ->
						job.gate().familyId().equals(item.get("family_id")) &&
						job.sourceManifestId().equals(item.get("source_manifest_id"))).findFirst().orElseThrow();
					candidate = ((Number) calibration.get("reservation_bytes")).longValue();
				}
				if (!BuildAdmission.resourcesPermit(host.availableBytes(), heldBytes, candidate, host.cpuLoad(),
					reservations.size(), host.cpuCount()))
					return deferred(job, evidence, host, heldBytes, candidate, reservations.size(),
						"strict host resource threshold not met");
				String reservationId = UUID.randomUUID().toString();
				Claim.Kind kind = Claim.Kind.CALIBRATION;
				if (calibrationExists)
					kind = Claim.Kind.ADMITTED;
				long generation;
				try
				{
					generation = Math.addExact(((Number) state.get("generation")).longValue(), 1L);
				}
				catch (ArithmeticException exception)
				{
					throw new IOException("Build ledger generation overflow: " + ledger, exception);
				}
				Claim claim = new Claim(kind, reservationId, job, candidate, generation, null);
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("reservation_id", reservationId); row.put("job_id", job.jobId());
				row.put("assignment_id", job.assignmentId()); row.put("family_id", job.gate().familyId());
				row.put("phase", "starting"); row.put("calibration", !calibrationExists);
				Object reservedBytes = null;
				if (calibrationExists)
					reservedBytes = candidate;
				row.put("reserved_bytes", reservedBytes);
				row.put("owner", Map.of("pid", owner.pid(), "start", owner.start()));
				row.put("process", null);
				row.put("descendants", List.of());
				Path evidencePath = evidence.toAbsolutePath().normalize();
				row.put("evidence_path", context.contextDirectory().relativize(evidencePath).toString());
				row.put("terminal_receipt_path", null); reservations.add(row);
				state.put("reservations", reservations); state.put("generation", claim.generation());
				state.put("context_id", context.contextId().toString());
				state.put("host_boot_id", host.bootId().toString()); state.put("format_version", 1);
				write(ledger, state);
				String decision;
				if (kind == Claim.Kind.CALIBRATION)
					decision = "calibration";
				else
					decision = "admitted";
				return writeAdmissionReceipt(claim, evidence, host, ((Number) state.get("generation")).longValue() - 1,
					generation, heldBytes, candidate, reservations.size(), decision, null);
			}
		}
	}

	/**
	 * Records the launched process identity before observations begin.
	  *
	 * @param claim owned reservation claim
	  *
	 * @param process verified process identity
	  *
	 * @throws IOException if the reservation cannot be updated
	 */
	public void started(Claim claim, ProcessIdentity process) throws IOException
	{
		mutate(claim, row ->
		{
		row.put("phase", "running");
			row.put("process", Map.of("pid", process.pid(), "start", process.start()));
		});
	}

	/**
	 * Retains each newly observed owned process identity for restart recovery.
	 *
	 * @param claim active reservation
	 * @param root Maven root identity
	 * @param observed all identities retained for the process tree
	 * @throws IOException if identities cannot be persisted
	 */
	public void observed(Claim claim, ProcessIdentity root, java.util.Set<ProcessIdentity> observed) throws IOException
	{
		locked(() ->
		{
			Path ledger = context.ledgerPath().resolve("ledger.json");
			Map<String, Object> state = read(ledger);
			List<Map<String, Object>> rows = records(state.get("reservations"));
			Map<String, Object> row = find(rows, claim);
			List<Map<String, Object>> descendants = observed.stream().filter(identity -> !identity.equals(root)).
				sorted(java.util.Comparator.comparingLong(ProcessIdentity::pid)).map(identity -> Map.<String, Object>of(
					"pid", identity.pid(), "start", identity.start())).toList();
			if (descendants.equals(row.get("descendants")))
				return;
			row.put("descendants", descendants);
			state.put("generation", Math.addExact(((Number) state.get("generation")).longValue(), 1L));
			write(ledger, state);
		});
	}

	/**
	 * Persists terminal evidence before releasing this reservation.
	  *
	 * @param claim owned reservation claim
	  *
	 * @param result terminal build result
	  *
	 * @throws IOException if terminal state cannot be durably recorded
	 */
	public void terminal(Claim claim, BuildAdmission.JobResult result) throws IOException
	{
		locked(() ->
		{
			Path ledger = context.ledgerPath().resolve("ledger.json"); Map<String, Object> state = read(ledger);
			List<Map<String, Object>> rows = records(state.get("reservations"));
			Map<String, Object> row = find(rows, claim);
			Map<String, Object> pending = new LinkedHashMap<>(row);
			pending.put("phase", "terminal_pending_release");
			pending.put("terminal_receipt_path", result.terminalReceiptPath().toString());
			if (!result.jobId().equals(claim.job().jobId()) || !terminalReceiptMatches(pending))
				throw new IOException("Terminal receipt is missing, malformed, or mismatched: " +
					result.terminalReceiptPath() + " for job " + claim.job().jobId() + " reservation " +
					claim.reservationId());
			row.putAll(pending);
			state.put("generation", Math.addExact(((Number) state.get("generation")).longValue(), 1L));
			write(ledger, state);
			rows.remove(row);
			state.put("reservations", rows);
			state.put("generation", Math.addExact(((Number) state.get("generation")).longValue(), 1L));
			write(ledger, state);
		});
	}

	/**
	 * Retains a reservation when process termination cannot be proven.
	 *
	 * @param claim owned reservation
	 * @throws IOException if the recovery-required phase cannot be persisted
	 */
	public void recoveryRequired(Claim claim) throws IOException
	{
		mutate(claim, row ->
		{
			row.put("phase", "recovery_required");
			row.put("terminal_receipt_path", null);
		});
	}

	/**
	 * Persists a complete solitary calibration receipt before the worker becomes eligible for release.
	  *
	 * @param claim calibration reservation
	  *
	 * @param peakBytes maximum measured tree RSS
	  *
	 * @param samples sample count
	  *
	 * @param maximumGapMillis maximum observed interval between process-tree samples
	 * @param observedProcesses number of process identities observed during calibration
	  *
	 * @param job calibrated job
	 * @param terminalReceiptPath retained terminal receipt path
	  *
	 * @throws IOException if the calibration is incomplete or cannot be persisted
	 */
	public void calibrated(Claim claim, long peakBytes, int samples, long maximumGapMillis, int observedProcesses,
		BuildAdmission.Job job, Path terminalReceiptPath) throws IOException
	{
		if (peakBytes <= 0 || samples <= 0)
			throw new IOException("Calibration did not observe a positive process-tree resident set");
		long reservedBytes;
		try
		{
			reservedBytes = Math.addExact(Math.multiplyExact(peakBytes, 5L), 3L) / 4L;
		}
		catch (ArithmeticException exception)
		{
			throw new IOException("Calibration reservation overflows for " + peakBytes + " bytes", exception);
		}
		locked(() ->
		{
			Path ledger = context.ledgerPath().resolve("ledger.json"); Map<String, Object> state = read(ledger);
			List<Map<String, Object>> rows = records(state.get("reservations"));
			find(rows, claim);
			List<Map<String, Object>> calibrations = records(state.get("calibrations"));
			Map<String, Object> calibration = new LinkedHashMap<>();
			calibration.put("family_id", job.gate().familyId());
			calibration.put("definition_manifest_id", job.gate().definitionManifestId());
			calibration.put("command_profile_id", job.gate().commandProfileId());
			calibration.put("source_manifest_id", job.sourceManifestId());
			calibration.put("calibration_job_id", job.jobId());
			calibration.put("terminal_receipt_path", terminalReceiptPath.toString());
			calibration.put("peak_rss_bytes", peakBytes); calibration.put("reservation_bytes", reservedBytes);
			calibration.put("sample_count", samples); calibration.put("interval_millis", 100);
			calibration.put("maximum_gap_millis", maximumGapMillis);
			calibration.put("observed_process_count", observedProcesses);
			calibration.put("coverage_result", "complete");
			calibrations.removeIf(item -> job.gate().familyId().equals(item.get("family_id")));
			calibrations.add(calibration); state.put("calibrations", calibrations);
			state.put("generation", ((Number) state.get("generation")).longValue() + 1); write(ledger, state);
		});
	}

	/**
	 * Conservatively reports outstanding ledger entries; it does not guess at liveness.
	  *
	 * @param observations resource provider
	  *
	 * @param evidence owned evidence root
	  *
	 * @return recovery result
	  *
	 * @throws IOException if the ledger is corrupt
	 */
	public BuildAdmission.RecoveryResult reconcile(BuildObservations observations, Path evidence) throws IOException
	{
		Objects.requireNonNull(observations, "observations");
		Objects.requireNonNull(evidence, "evidence");
		BuildObservations.HostSample host = observations.host();
		if (!host.bootId().equals(context.hostBootId()))
			throw new BuildAdmission.Failure(BuildAdmission.Failure.Kind.HOST_MISMATCH, context.ledgerPath(), null, null,
				"Observed host boot identity does not match build context");
		List<String> unresolved = new ArrayList<>();
		locked(() ->
		{
			Path ledger = context.ledgerPath().resolve("ledger.json");
			Map<String, Object> state = read(ledger);
			List<Map<String, Object>> rows = records(state.get("reservations"));
			boolean changed = false;
			for (Map<String, Object> row : new ArrayList<>(rows))
			{
				String reservationId = Objects.toString(row.get("reservation_id"));
				String phase = Objects.toString(row.get("phase"));
				if ("terminal_pending_release".equals(phase) && !hasLiveOwnedProcess(row, observations) &&
					terminalReceiptMatches(row))
				{
					rows.remove(row);
					changed = true;
					continue;
				}
				if (!"starting".equals(phase) && !"terminal_pending_release".equals(phase))
					row.put("phase", "recovery_required");
				unresolved.add(reservationId);
			}
			if (changed || !unresolved.isEmpty())
			{
				state.put("reservations", rows);
				state.put("generation", Math.addExact(((Number) state.get("generation")).longValue(), 1L));
				write(ledger, state);
			}
		});
		Files.createDirectories(evidence);
		Map<String, Object> receipt = Map.of("format_version", 1, "context_id", context.contextId().toString(),
			"host_boot_id", context.hostBootId().toString(), "reconciled", unresolved.isEmpty(),
			"unresolved_reservations", List.copyOf(unresolved));
		Files.writeString(evidence.resolve("recovery.json"), mapper.writeValueAsString(receipt));
		return new BuildAdmission.RecoveryResult(unresolved.isEmpty(), unresolved);
	}

	/**
	 * Checks every recorded build process against the current stable-identity authority.
	 *
	 * @param row reservation data
	 * @param observations process identity provider
	 * @return whether any recorded process remains alive with the same identity
	 * @throws IOException if liveness cannot be established
	 */
	private boolean hasLiveOwnedProcess(Map<String, Object> row, BuildObservations observations) throws IOException
	{
		Object process = row.get("process");
		if (process instanceof Map<?, ?> processMap && processMap.get("pid") instanceof Number pid &&
			processMap.get("start") instanceof String start &&
			isLive(new ProcessIdentity(pid.longValue(), start), observations))
			return true;
		for (Map<String, Object> descendant : records(row.get("descendants")))
		{
			Object pid = descendant.get("pid");
			Object start = descendant.get("start");
			if (pid instanceof Number number && start instanceof String value &&
				isLive(new ProcessIdentity(number.longValue(), value), observations))
				return true;
		}
		return false;
	}

	/**
	 * Tests one identity without adopting a reused PID.
	 *
	 * @param expected previously recorded identity
	 * @param observations current identity provider
	 * @return true if the exact identity remains present
	 * @throws IOException if the provider cannot establish identity
	 */
	private static boolean isLive(ProcessIdentity expected, BuildObservations observations) throws IOException
	{
		return observations.identity(expected.pid()).filter(expected::equals).isPresent();
	}

	/**
	 * Verifies the terminal receipt before recovery releases its reservation.
	 *
	 * @param row terminal reservation
	 * @return true if the bounded receipt is valid and bound to this reservation
	 * @throws IOException if the file cannot be safely inspected
	 */
	private boolean terminalReceiptMatches(Map<String, Object> row) throws IOException
	{
		Path evidenceRoot = context.contextDirectory().resolve((String) row.get("evidence_path")).normalize();
		Path receiptPath = Path.of((String) row.get("terminal_receipt_path"));
		if (receiptPath.isAbsolute() || receiptPath.toString().contains(".."))
			return false;
		Path receiptFile = evidenceRoot.resolve(receiptPath).normalize();
		if (!receiptFile.startsWith(evidenceRoot) ||
			!Files.isRegularFile(receiptFile, java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
			Files.isSymbolicLink(receiptFile) || Files.size(receiptFile) > 16L * 1024 * 1024)
			return false;
		JsonNode receipt;
		try
		{
			receipt = mapper.readerFor(JsonNode.class).with(StreamReadFeature.STRICT_DUPLICATE_DETECTION).
				with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(Files.readAllBytes(receiptFile));
		}
		catch (tools.jackson.core.JacksonException _)
		{
			return false;
		}
		List<String> receiptFields = List.of("format_version", "context_id", "host_boot_id", "reservation_id", "job_id",
			"assignment_id", "family_id", "status", "exit_code", "owned_processes", "termination_result",
			"calibration_result", "diagnostics_path", "receipt_id");
		if (!hasExactFields(receipt, receiptFields) || !receipt.path("format_version").canConvertToInt() ||
			receipt.path("format_version").intValue() != 1 ||
			!context.contextId().toString().equals(receipt.path("context_id").stringValue()) ||
			!context.hostBootId().toString().equals(receipt.path("host_boot_id").stringValue()) ||
			!Objects.equals(row.get("reservation_id"), receipt.path("reservation_id").stringValue()) ||
			!Objects.equals(row.get("job_id"), receipt.path("job_id").stringValue()) ||
			!Objects.equals(row.get("assignment_id"), receipt.path("assignment_id").stringValue()) ||
			!Objects.equals(row.get("family_id"), receipt.path("family_id").stringValue()) ||
			!receipt.path("status").isString() ||
			!List.of("passed", "failed", "infrastructure_failed", "interrupted").contains(
				receipt.path("status").stringValue()) ||
			!(receipt.path("exit_code").isNull() || receipt.path("exit_code").canConvertToInt()) ||
			!receipt.path("owned_processes").isArray() ||
			!receipt.path("termination_result").isString() || !receipt.path("diagnostics_path").isString() ||
			!receipt.path("receipt_id").isString())
			return false;
		String status = receipt.path("status").stringValue();
		JsonNode exit = receipt.path("exit_code");
		String termination = receipt.path("termination_result").stringValue();
		if (!List.of("reaped", "not_started").contains(termination) ||
			("passed".equals(status) && (!exit.canConvertToInt() || exit.intValue() != 0 ||
				!"reaped".equals(termination))) ||
			("failed".equals(status) && (!exit.canConvertToInt() || exit.intValue() == 0 ||
				!"reaped".equals(termination))) ||
			(exit.isNull() && !List.of("infrastructure_failed", "interrupted").contains(status)) ||
			("not_started".equals(termination) && (!exit.isNull() || !receipt.path("owned_processes").isEmpty() ||
				row.get("process") != null || !records(row.get("descendants")).isEmpty())) ||
			("reaped".equals(termination) && row.get("process") == null))
			return false;
		if ("reaped".equals(termination))
		{
			java.util.Set<String> expected = new java.util.HashSet<>();
			Map<?, ?> root = (Map<?, ?>) row.get("process");
			expected.add(root.get("pid") + ":" + root.get("start"));
			for (Map<String, Object> descendant : records(row.get("descendants")))
				expected.add(descendant.get("pid") + ":" + descendant.get("start"));
			java.util.Set<String> actual = new java.util.HashSet<>();
			for (JsonNode owned : receipt.path("owned_processes"))
			{
				if (!hasExactFields(owned, List.of("pid", "start")) || !owned.path("pid").canConvertToLong() ||
					!owned.path("start").isString() || !actual.add(owned.path("pid").longValue() + ":" +
						owned.path("start").stringValue()))
					return false;
			}
			if (!expected.equals(actual))
				return false;
		}
		Map<String, Object> body = new LinkedHashMap<>();
		@SuppressWarnings("unchecked")
		Map<String, Object> complete = (Map<String, Object>) mapper.convertValue(receipt, Map.class);
		body.putAll(complete);
		Object claimedId = body.remove("receipt_id");
		String actualId;
		try
		{
			actualId = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(
				mapper.writeValueAsBytes(body)));
		}
		catch (java.security.NoSuchAlgorithmException impossible)
		{
			throw new IllegalStateException(impossible);
		}
		return actualId.equals(claimedId);
	}

	/**
	 * Applies one mutation while holding the shared ledger lock.
	  *
	 * @param claim owned reservation claim
	  *
	 * @param change row mutation
	  *
	 * @throws IOException if the ledger is missing or invalid
	 */
	private void mutate(Claim claim, java.util.function.Consumer<Map<String, Object>> change) throws IOException
	{
		locked(() ->
		{
			Path ledger = context.ledgerPath().resolve("ledger.json");
			Map<String, Object> state = read(ledger);
			List<Map<String, Object>> rows = records(state.get("reservations"));
			Map<String, Object> row = find(rows, claim);
			change.accept(row);
			state.put("generation", ((Number) state.get("generation")).longValue() + 1);
			write(ledger, state);
		});
	}

	/**
	 * Runs one ledger action under the cross-process lock.
	  *
	 * @param action serialized state transition
	  *
	 * @throws IOException if lock acquisition or the action fails
	 */
	private void locked(IoAction action) throws IOException
	{
		Path lock = context.ledgerPath().resolve("ledger.lock");
		synchronized (locks.monitor(lock))
		{
			Optional<ExclusiveFileLock> acquired = locks.tryLock(lock,
				path -> java.nio.channels.FileChannel.open(path, StandardOpenOption.WRITE));
			if (acquired.isEmpty()) throw new IOException("Build ledger lock is already held: " + lock);
			try (ExclusiveFileLock held = acquired.orElseThrow())
			{
				if (!held.isValid())
					throw new IOException("Invalid build ledger lock");
				action.run();
			}
		}
	}

	/**
	 * Reads a schema-checked ledger or returns a new empty one.
	  *
	 * @param ledger shared ledger file
	  *
	 * @return mutable parsed ledger state
	  *
	 * @throws IOException if persisted state is invalid
	 */
	private Map<String, Object> read(Path ledger) throws IOException
	{
		if (!Files.exists(ledger))
		{
			Map<String, Object> empty = new LinkedHashMap<>();
			empty.put("format_version", 1);
			empty.put("context_id", context.contextId().toString());
			empty.put("host_boot_id", context.hostBootId().toString());
			empty.put("generation", 0L);
			empty.put("calibrations", List.of());
			empty.put("reservations", List.of());
			return empty;
		}
		if (Files.size(ledger) > 16L * 1024 * 1024)
			throw new IOException("Build ledger exceeds 16 MiB: " + ledger);
		JsonNode node;
		try
		{
			node = mapper.readerFor(JsonNode.class).with(StreamReadFeature.STRICT_DUPLICATE_DETECTION).
				with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(Files.readAllBytes(ledger));
		}
		catch (tools.jackson.core.JacksonException exception)
		{
			throw new IOException("Malformed build ledger: " + ledger + ": " + exception.getOriginalMessage(), exception);
		}
		if (!hasExactFields(node, List.of("format_version", "context_id", "host_boot_id", "generation",
			"calibrations", "reservations")) || !node.path("format_version").canConvertToInt() ||
			node.path("format_version").intValue() != 1 ||
			!context.contextId().toString().equals(node.path("context_id").stringValue()) ||
			!context.hostBootId().toString().equals(node.path("host_boot_id").stringValue()) ||
			!node.path("generation").canConvertToLong() || node.path("generation").longValue() < 0 ||
			!node.path("calibrations").isArray() || !node.path("reservations").isArray())
			throw new IOException("Corrupt or mismatched build ledger: " + ledger);
		for (JsonNode calibration : node.path("calibrations"))
			validateCalibration(calibration, ledger);
		for (JsonNode reservation : node.path("reservations"))
			validateReservation(reservation, ledger);
		@SuppressWarnings("unchecked")
		Map<String, Object> result = (Map<String, Object>) mapper.convertValue(node, Map.class);
		return new LinkedHashMap<>(result);
	}

	/**
	 * Verifies exact calibration fields before capacity accounting.
	 *
	 * @param record serialized calibration
	 * @param ledger containing file
	 * @throws IOException if the record is incomplete or malformed
	 */
	private static void validateCalibration(JsonNode record, Path ledger) throws IOException
	{
		List<String> fields = List.of("family_id", "definition_manifest_id", "command_profile_id", "source_manifest_id",
			"calibration_job_id", "terminal_receipt_path", "peak_rss_bytes", "reservation_bytes", "sample_count",
			"interval_millis", "maximum_gap_millis", "observed_process_count", "coverage_result");
		if (!hasExactFields(record, fields) || !positiveLong(record.path("peak_rss_bytes")) ||
			!positiveLong(record.path("reservation_bytes")) || !positiveInt(record.path("sample_count")) ||
			!positiveInt(record.path("interval_millis")) || !nonnegativeInt(record.path("maximum_gap_millis")) ||
			!positiveInt(record.path("observed_process_count")) ||
			!"complete".equals(record.path("coverage_result").stringValue()))
			throw new IOException("Malformed calibration record in build ledger: " + ledger);
		for (String field : List.of("family_id", "definition_manifest_id", "command_profile_id", "source_manifest_id",
			"calibration_job_id", "terminal_receipt_path"))
			if (!record.path(field).isString() || record.path(field).stringValue().isBlank())
				throw new IOException("Malformed calibration field " + field + " in build ledger: " + ledger);
	}

	/**
	 * Verifies exact reservation fields before capacity accounting or recovery.
	 *
	 * @param record serialized reservation
	 * @param ledger containing file
	 * @throws IOException if the record is incomplete or malformed
	 */
	private static void validateReservation(JsonNode record, Path ledger) throws IOException
	{
		List<String> fields = List.of("reservation_id", "job_id", "assignment_id", "family_id", "phase",
			"calibration", "reserved_bytes", "owner", "process", "descendants", "evidence_path",
			"terminal_receipt_path");
		if (!hasExactFields(record, fields) || !record.path("reservation_id").isString() ||
			!record.path("job_id").isString() || !record.path("assignment_id").isString() ||
			!record.path("family_id").isString() || !record.path("phase").isString() ||
			!record.path("calibration").isBoolean() || !record.path("descendants").isArray() ||
			!record.path("evidence_path").isString())
			throw new IOException("Malformed reservation record in build ledger: " + ledger);
		String phase = record.path("phase").stringValue();
		if (!List.of("starting", "running", "terminal_pending_release", "recovery_required").contains(phase))
			throw new IOException("Invalid reservation phase in build ledger: " + ledger);
		boolean beforeSpawn = record.path("process").isNull();
		if (!identity(record.path("owner")) ||
			beforeSpawn && !List.of("starting", "terminal_pending_release").contains(phase) ||
			!beforeSpawn && "starting".equals(phase))
			throw new IOException("Invalid reservation process identity in build ledger: " + ledger);
		if (!beforeSpawn && !identity(record.path("process")))
			throw new IOException("Invalid reservation process identity in build ledger: " + ledger);
		if (record.path("terminal_receipt_path").isNull())
		{
			if ("terminal_pending_release".equals(record.path("phase").stringValue()))
				throw new IOException("Terminal reservation has no receipt in build ledger: " + ledger);
		}
		else if (!record.path("terminal_receipt_path").isString() ||
			!"terminal_pending_release".equals(record.path("phase").stringValue()))
			throw new IOException("Invalid terminal reservation in build ledger: " + ledger);
		for (JsonNode descendant : record.path("descendants"))
			if (!identity(descendant))
				throw new IOException("Invalid descendant identity in build ledger: " + ledger);
		if (record.path("calibration").booleanValue())
		{
			if (!record.path("reserved_bytes").isNull())
				throw new IOException("Calibration reservation must exclude workers in build ledger: " + ledger);
		}
		else if (!positiveLong(record.path("reserved_bytes")))
			throw new IOException("Invalid reservation bytes in build ledger: " + ledger);
	}

	/**
	 * Checks the strict serialized process-identity shape.
	 *
	 * @param identity identity node
	 * @return whether it contains one positive PID and nonblank start token
	 */
	private static boolean identity(JsonNode identity)
	{
		return hasExactFields(identity, List.of("pid", "start")) && positiveLong(identity.path("pid")) &&
			identity.path("start").isString() && !identity.path("start").stringValue().isBlank();
	}

	/**
	 * Checks a JSON positive long.
	 *
	 * @param value value node
	 * @return whether it is a positive long
	 */
	private static boolean positiveLong(JsonNode value)
	{
		return value.canConvertToLong() && value.longValue() > 0;
	}

	/**
	 * Checks a JSON positive int.
	 *
	 * @param value value node
	 * @return whether it is a positive int
	 */
	private static boolean positiveInt(JsonNode value)
	{
		return value.canConvertToInt() && value.intValue() > 0;
	}

	/**
	 * Checks a JSON nonnegative int.
	 *
	 * @param value value node
	 * @return whether it is a nonnegative int
	 */
	private static boolean nonnegativeInt(JsonNode value)
	{
		return value.canConvertToInt() && value.intValue() >= 0;
	}

	/**
	 * Checks whether an object has exactly the named fields.
	 *
	 * @param node object node
	 * @param expected expected field names
	 * @return whether the object shape is exact
	 */
	private static boolean hasExactFields(JsonNode node, List<String> expected)
	{
		if (!node.isObject() || node.size() != expected.size())
			return false;
		for (String field : expected)
			if (!node.has(field))
				return false;
		return true;
	}

	/**
	 * Atomically replaces the ledger with a complete generation.
	  *
	 * @param ledger destination ledger path
	  *
	 * @param state replacement state
	  *
	 * @throws IOException if serialization or replacement fails
	 */
	private void write(Path ledger, Map<String, Object> state) throws IOException
	{
		Path temporary = Files.createTempFile(ledger.getParent(), ".ledger-", ".tmp");
		try
		{
			Files.writeString(temporary, mapper.writeValueAsString(state));
			Files.move(temporary, ledger, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}
	/**
	 * Copies parsed ledger rows to a mutable list.
	  *
	 * @param value parsed row list
	  *
	 * @return mutable row list
	 */
	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> records(Object value)
	{
		return new ArrayList<>((List<Map<String, Object>>) value);
	}

	/**
	 * Finds one reservation row by its unforgeable claim ID.
	  *
	 * @param rows ledger rows
	  *
	 * @param claim owned claim
	  *
	 * @return matching reservation row
	  *
	 * @throws IOException if the reservation no longer exists
	 */
	private static Map<String, Object> find(List<Map<String, Object>> rows, Claim claim) throws IOException
	{
		return rows.stream().filter(row -> claim.reservationId().equals(row.get("reservation_id"))).findFirst().
			orElseThrow(() -> new IOException("Reservation no longer exists: " + claim.reservationId()));
	}
	/**
	 * One ledger operation that may fail during persistence.
	 */
	@FunctionalInterface
	private interface IoAction
	{
		/**
		 * Runs the serialized state mutation.
		  *
		 * @throws IOException if the mutation fails
		 */
		void run() throws IOException;
	}

	/**
	 * Opaque reservation generation owned by this store.
	 */
	public static final class Claim
	{
		private final Kind kind;
		private final String reservationId;
		private final BuildAdmission.Job job;
		private final long bytes;
		private final long generation;
		private final String admissionReceiptId;

		/**
		 * Creates a claim from a persisted ledger decision.
		  *
		 * @param kind claim kind
		  *
		 * @param reservationId reservation identity
		  *
		 * @param job requested job
		  *
		 * @param bytes reserved byte count
		  *
		 * @param generation current ledger generation
		 * @param admissionReceiptId persisted decision receipt identity
		 */
		private Claim(Kind kind, String reservationId, BuildAdmission.Job job, long bytes, long generation,
			String admissionReceiptId)
		{
			this.kind = kind;
			this.reservationId = reservationId;
			this.job = job;
			this.bytes = bytes;
			this.generation = generation;
			this.admissionReceiptId = admissionReceiptId;
		}

		/**
		 * Returns decision kind.
		  *
		 * @return claim kind
		 */
		public Kind kind()
		{
			return kind;
		}
		/**
		 * Returns reservation identifier when claimed.
		  *
		 * @return reservation ID, or null if deferred
		 */
		public String reservationId()
		{
			return reservationId;
		}
		/**
		 * Returns the request associated with this claim.
		  *
		 * @return job request
		 */
		public BuildAdmission.Job job()
		{
			return job;
		}
		/**
		 * Returns reserved bytes.
		  *
		 * @return byte reservation
		 */
		public long bytes()
		{
			return bytes;
		}
		/**
		 * Returns claim ledger generation.
		  *
		 * @return generation
		 */
		public long generation()
		{
			return generation;
		}
		/**
		 * Returns the retained allocation receipt identifier.
		 *
		 * @return admission receipt UUID
		 */
		public String admissionReceiptId()
		{
			return admissionReceiptId;
		}
		/**
		 * Reservation outcome.
		 */
		public enum Kind
		{
			CALIBRATION,
			ADMITTED,
			DEFERRED
		}
	}
}
