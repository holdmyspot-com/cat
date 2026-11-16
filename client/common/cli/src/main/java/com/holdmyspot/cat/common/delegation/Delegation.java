package com.holdmyspot.cat.common.delegation;

import com.holdmyspot.cat.common.ExclusiveFileLock;
import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.SessionDirectory;
import com.holdmyspot.cat.common.build.BuildAdmission;
import com.holdmyspot.cat.common.git.Git;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;

/**
 * Coordinates preparation, audit, and replay of independently implemented work.
 */
public final class Delegation
{
	private final JvmScope scope;
	private final Path project;
	private final Map<String, String> environment;
	private final BuildAdmission buildAdmission;

	/**
	 * Creates a coordinator for one project and child process environment.
	 *
	 * @param scope process services
	 * @param project project root
	 * @param environment complete environment for child processes
	 * @param buildAdmission required admission controller for every Maven gate
	 * @throws IOException if the project root cannot be resolved
	 * @throws NullPointerException if an argument is null
	 */
	public Delegation(JvmScope scope, Path project, Map<String, String> environment, BuildAdmission buildAdmission)
		throws IOException
	{
		this.scope = Objects.requireNonNull(scope);
		this.project = Objects.requireNonNull(project).toRealPath();
		this.environment = Map.copyOf(environment);
		this.buildAdmission = Objects.requireNonNull(buildAdmission);
	}

	/**
	 * Freezes reviewed source and creates independent delegate repositories.
	 *
	 * @param plan immutable request
	 * @return prepared cohort identity
	 * @throws IOException if preparation fails
	 */
	public Prepared prepare(Plan plan) throws IOException
	{
		Objects.requireNonNull(plan);
		Path temporaryDirectory = scope.temporaryDirectory();
		Map<String, String> gitEnvironment = safeGitEnvironment(temporaryDirectory);
		verifyProjectIdentity(gitEnvironment);
		Path cohort = Files.createTempDirectory(temporaryDirectory, "delegation-").toRealPath();
		Path lockPath = Files.createFile(cohort.resolve("cohort.lock"));
		Path planPath = cohort.resolve("plan.json");
		Path receipts = Files.createDirectories(cohort.resolve("receipts"));
		Path owner = cohort.resolve("owner.json");
		Map<String, Object> ownerRecord = ownerRecord(plan, "creating", 0);
		writeJson(owner, ownerRecord);
		synchronized (scope.fileLocks().monitor(lockPath))
		{
			Optional<ExclusiveFileLock> available = scope.fileLocks().tryLock(lockPath,
				path -> FileChannel.open(path, StandardOpenOption.WRITE));
			if (available.isEmpty())
				throw new IOException("Could not acquire new cohort lock: " + lockPath);
			try (ExclusiveFileLock held = available.get())
			{
				if (!held.isValid())
					throw new IOException("Cohort lock is not valid: " + lockPath);
			try
			{
			plan.writeTo(planPath);
			Set<String> capturePaths = new java.util.TreeSet<>(plan.sourcePaths());
			for (Plan.Work work : plan.work())
				capturePaths.addAll(work.writePaths());
			SourceSnapshot snapshot = SourceSnapshot.capture(project, cohort, List.copyOf(capturePaths));
			Repositories.RepositorySet repositories = Repositories.create(cohort, snapshot,
				plan.work().stream().map(Plan.Work::workId).toList(), gitEnvironment,
				builder -> scope.executableFiles().run(builder));
			String snapshotId = snapshot.identity();
			List<Path> receiptPaths = new ArrayList<>();
			for (Plan.Work work : plan.work())
			{
				Path invocation = cohort.resolve("verification").resolve(work.workId());
				Path probe = invocation.resolve("project");
				Files.createDirectories(probe);
				snapshot.materialize(probe);
				Map<String, String> before = inventory(probe);
				Verification.GateResult gate = Verification.run(scope, buildAdmission, probe, work,
					invocation.resolve("roots"), environment, snapshotId, plan.excludedRoots(),
					verificationOutputRoots(plan));
				Map<String, String> after = inventory(probe);
				List<String> observed = changed(before, after);
				List<String> unexpected = observed.stream().filter(path ->
					!isAllowedOutput(path, work.verification().projectOutputRoots())).toList();
				if (!unexpected.isEmpty())
					throw new IOException("Gate wrote outside reviewed outputs: " + unexpected);
				Verification.requireOutputCoverage(work, observed);
				Map<String, Object> receipt = new LinkedHashMap<>();
				receipt.put("format_version", 1);
				receipt.put("operation", "prepare");
				receipt.put("work_id", work.workId());
				receipt.put("assignment_id", work.assignmentId());
				receipt.put("plan_id", plan.identity());
				receipt.put("snapshot_id", snapshotId);
				receipt.put("review_id", work.verification().reviewId());
				receipt.put("definition_manifest_id", definitionIdentity(probe, work));
				receipt.put("metadata_capabilities", metadataCapabilities(project, snapshot));
				receipt.put("input_manifest_id", digest(before.toString().getBytes(
					java.nio.charset.StandardCharsets.UTF_8)));
				receipt.put("gate_spec_id", gateSpecIdentity(work));
				receipt.put("invocation_id", cohort.relativize(invocation).toString());
				receipt.put("phase", "preparation_probe");
				receipt.put("scope_result", "passed");
				receipt.put("exit_code", gate.exitCode());
				receipt.put("admission_receipt_id", gate.admissionReceiptId());
				receipt.put("terminal_receipt_id", gate.terminalReceiptId());
				receipt.put("arguments", durableArguments(gate.arguments(), cohort));
				receipt.put("observed_changes", observed);
				receipt.put("declared_roots", declaredRoots(cohort, work, gate));
				receipt.put("owned_root_inventory", ownedInventory(cohort, gate));
				receipt.put("diagnostic_path", cohort.relativize(gate.diagnosticsPath()).toString());
				Path receiptPath = receipts.resolve("prepare-" + work.workId() + ".json");
				receipt.put("receipt_id", digest(scope.json().writeValueAsString(receipt).getBytes(
					java.nio.charset.StandardCharsets.UTF_8)));
				writeJson(receiptPath, receipt);
				receiptPaths.add(cohort.relativize(receiptPath));
				Map<String, Object> workRecords = castMap(ownerRecord.get("work"));
				Map<String, Object> workRecord = castMap(workRecords.get(work.workId()));
				workRecord.put("preparation_receipt_id", receipt.get("receipt_id"));
				workRecord.put("state", "claimed");
				workRecords.put(work.workId(), workRecord);
				ownerRecord.put("work", workRecords);
			}
			ownerRecord.put("state", "prepared");
			ownerRecord.put("generation", 1);
			ownerRecord.put("snapshot_id", snapshotId);
			ownerRecord.put("snapshot_commit", repositories.snapshotCommit());
			ownerRecord.put("snapshot_tree", repositories.snapshotTree());
			ownerRecord.put("receipts", receiptPaths.stream().map(Path::toString).toList());
			writeJson(owner, ownerRecord);
			List<WorkResult> workResults = plan.work().stream().map(work -> new WorkResult(work.workId(), ".",
				work.assignmentId(), "unclaimed", List.of(), List.of())).toList();
			return new Prepared(cohort, 1, snapshotId, cohort.resolve(receiptPaths.getFirst()), workResults);
			}
			catch (IOException | RuntimeException e)
			{
				ownerRecord.put("state", "failed");
				ownerRecord.put("failure", e.getMessage());
				try
			{
				Map<String, Object> failureReceipt = new LinkedHashMap<>();
				failureReceipt.put("format_version", 1);
				failureReceipt.put("plan_id", plan.identity());
				failureReceipt.put("phase", "preparation_failure");
				failureReceipt.put("scope_result", "failed");
				failureReceipt.put("failure_type", e.getClass().getName());
				failureReceipt.put("failure", String.valueOf(e.getMessage()));
				failureReceipt.put("diagnostic_paths", diagnosticPaths(cohort, plan));
				failureReceipt.put("receipt_id", digest(scope.json().writeValueAsString(failureReceipt).getBytes(
					java.nio.charset.StandardCharsets.UTF_8)));
				Path failureReceiptPath = receipts.resolve("prepare-failure.json");
				writeJson(failureReceiptPath, failureReceipt);
				ownerRecord.put("failure_receipt_id", failureReceipt.get("receipt_id"));
				ownerRecord.put("failure_receipt_path", cohort.relativize(failureReceiptPath).toString());
				writeJson(owner, ownerRecord);
				cleanupFailedPreparation(cohort, plan);
				deleteTree(cohort.resolve("work"));
				}
				catch (IOException writeFailure)
				{
					e.addSuppressed(writeFailure);
				}
				throw e;
			}
			}
		}
	}

	/**
	 * Audits one completed delegate against the cohort's immutable source snapshot.
	 *
	 * @param cohort cohort directory
	 * @param workId work identifier
	 * @return immutable audit identity
	 * @throws IOException if the audit fails
	 */
	public Audit audit(Path cohort, String workId) throws IOException
	{
		Path cohortRoot = cohort.toRealPath();
		Path lockPath = cohortRoot.resolve("cohort.lock");
		synchronized (scope.fileLocks().monitor(lockPath))
		{
			Optional<ExclusiveFileLock> available = scope.fileLocks().tryLock(lockPath,
				path -> FileChannel.open(path, StandardOpenOption.WRITE));
			if (available.isEmpty())
				throw new IOException("Cohort is busy: " + cohortRoot);
			try (ExclusiveFileLock held = available.get())
			{
				if (!held.isValid())
					throw new IOException("Cohort lock is not valid: " + lockPath);
				verifyProjectIdentity(safeGitEnvironment(scope.temporaryDirectory()));
				Plan plan = Plan.read(scope.json(), cohortRoot.resolve("plan.json"));
				JsonNode owner = scope.json().readTree(cohortRoot.resolve("owner.json").toFile());
				if (owner == null || !owner.get("plan_id").stringValue().equals(plan.identity()) ||
					!owner.get("state").stringValue().equals("prepared"))
					throw new IOException("Cohort ownership or state is invalid: " + cohortRoot);
				Plan.Work work = plan.work(workId);
				JsonNode assignment = owner.get("work").get(workId);
				if (assignment == null || !assignment.get("assignment_id").stringValue().equals(work.assignmentId()) ||
					!Set.of("claimed", "verified", "invalidated").contains(assignment.get("state").stringValue()))
					throw new IOException("Work assignment is stale: " + workId);
				Path preparationReceipt = cohortRoot.resolve("receipts/prepare-" + workId + ".json");
				if (!Files.isRegularFile(preparationReceipt))
					throw new IOException("Preparation receipt is missing: " + preparationReceipt);
				JsonNode preparation = scope.json().readTree(preparationReceipt.toFile());
				String snapshotId = owner.get("snapshot_id").stringValue();
				if (!validReceipt(preparation, preparationReceipt) ||
					!preparation.get("phase").stringValue().equals("preparation_probe") ||
					!preparation.get("work_id").stringValue().equals(workId) ||
					!preparation.get("gate_spec_id").stringValue().equals(gateSpecIdentity(work)) ||
					!preparation.get("plan_id").stringValue().equals(plan.identity()) ||
					!preparation.get("assignment_id").stringValue().equals(work.assignmentId()) ||
					!preparation.get("snapshot_id").stringValue().equals(snapshotId) ||
					!preparation.get("review_id").stringValue().equals(work.verification().reviewId()) ||
					!preparation.get("receipt_id").stringValue().equals(
						assignment.get("preparation_receipt_id").stringValue()) ||
					!preparation.get("scope_result").stringValue().equals("passed"))
					throw new IOException("Preparation receipt is invalid: " + preparationReceipt);
				SourceSnapshot snapshot = SourceSnapshot.read(scope.json(), cohortRoot);
				if (!snapshot.identity().equals(snapshotId))
					throw new IOException("Source snapshot identity changed: " + cohortRoot);
				Path candidate = cohortRoot.resolve("work").resolve(workId).resolve("project").toRealPath();
				List<String> unownedDirectories = unownedEmptyDirectories(candidate, plan.excludedRoots(),
					work.writePaths());
				if (!unownedDirectories.isEmpty())
					throw new IOException("Candidate created unreviewed empty directories: " + unownedDirectories);
				Map<String, String> candidateFiles = inventoryProject(candidate, plan.excludedRoots());
				Map<String, SourceSnapshot.Entry> original = new TreeMap<>();
				for (SourceSnapshot.Entry entry : snapshot.entries())
					if (!entry.kind().equals("directory"))
						original.put(entry.path(), entry);
				Set<String> paths = new java.util.TreeSet<>(original.keySet());
				paths.addAll(candidateFiles.keySet());
				List<String> changedPaths = new ArrayList<>();
				Set<String> selectedSourcePaths = Set.copyOf(plan.sourcePaths());
				for (String path : paths)
				{
					SourceSnapshot.Entry before = original.getOrDefault(path, absent(path));
					SourceSnapshot.Entry after = observe(candidate, path, candidateFiles.get(path));
					validateCandidateLink(candidate, path, after, selectedSourcePaths);
					if (!sameEntry(before, after))
					{
						if (work.writePaths().stream().noneMatch(write -> path.equals(write) || path.startsWith(write + "/")))
							throw new IOException("Candidate changed a path outside its write allowlist: " + path);
						changedPaths.add(path);
					}
				}
				Path workVerification = Files.createDirectories(cohortRoot.resolve("verification").resolve(workId));
				Path invocation = Files.createTempDirectory(workVerification, "candidate-");
				Path probe = invocation.resolve("project");
				Files.createDirectories(probe);
				copyProject(candidate, probe, plan.excludedRoots());
				Map<String, String> beforeProbe = inventoryScoped(probe,
					List.of(".git", SessionDirectory.ROOT, ".cat-tmp"));
				Verification.GateResult gate = Verification.run(scope, buildAdmission, probe, work,
					invocation.resolve("roots"), environment, snapshotId, plan.excludedRoots(),
					verificationOutputRoots(plan));
				Map<String, String> afterProbe = inventoryScoped(probe,
					List.of(".git", SessionDirectory.ROOT, ".cat-tmp"));
				List<String> probeChanges = changed(beforeProbe, afterProbe);
				if (hasUndeclaredOutput(probeChanges, work))
					throw new IOException("Candidate gate wrote outside reviewed outputs: " + probeChanges);
				Verification.requireOutputCoverage(work, probeChanges);
				if (gate.exitCode() != 0)
					throw new IOException("Candidate gate failed for " + workId + ": " + gate.exitCode());
				Map<String, Object> audit = new LinkedHashMap<>();
				audit.put("format_version", 1);
				audit.put("plan_id", plan.identity());
				audit.put("work_id", workId);
				audit.put("assignment_id", work.assignmentId());
				audit.put("snapshot_id", snapshotId);
				audit.put("review_id", work.verification().reviewId());
				audit.put("definition_manifest_id", definitionIdentity(candidate, work));
				audit.put("metadata_capabilities", metadataCapabilities(project, snapshot));
				audit.put("input_manifest_id", digest(beforeProbe.toString().getBytes(
					java.nio.charset.StandardCharsets.UTF_8)));
				audit.put("gate_spec_id", gateSpecIdentity(work));
				audit.put("invocation_id", cohortRoot.relativize(invocation).toString());
				audit.put("phase", "candidate_probe");
				audit.put("scope_result", "passed");
				audit.put("candidate_manifest_id", digest(candidateFiles.toString().getBytes(
					java.nio.charset.StandardCharsets.UTF_8)));
				audit.put("changed_paths", changedPaths);
				audit.put("receipt_path", cohortRoot.relativize(preparationReceipt).toString());
				audit.put("candidate_gate_arguments", durableArguments(gate.arguments(), cohortRoot));
				audit.put("candidate_gate_exit_code", gate.exitCode());
				audit.put("admission_receipt_id", gate.admissionReceiptId());
				audit.put("terminal_receipt_id", gate.terminalReceiptId());
				audit.put("diagnostic_paths", List.of(cohortRoot.relativize(gate.diagnosticsPath()).toString()));
				audit.put("observed_changes", probeChanges);
				audit.put("declared_roots", declaredRoots(cohortRoot, work, gate));
				audit.put("owned_root_inventory", ownedInventory(cohortRoot, gate));
				audit.put("receipt_id", digest(scope.json().writeValueAsString(audit).getBytes(
					java.nio.charset.StandardCharsets.UTF_8)));
				Path auditPath = cohortRoot.resolve("work").resolve(workId).resolve("audit.json");
				writeJson(auditPath, audit);
				Path receiptPath = cohortRoot.resolve("receipts/audit-" + workId + ".json");
				writeJson(receiptPath, audit);
				Map<String, Object> nextOwner = ownerRecord(plan, "prepared", owner.get("generation").longValue() + 1);
				copyReceiptIds(owner, nextOwner, plan);
				nextOwner.put("snapshot_id", snapshotId);
				nextOwner.put("snapshot_commit", owner.get("snapshot_commit").stringValue());
				nextOwner.put("snapshot_tree", owner.get("snapshot_tree").stringValue());
				Map<String, Object> workRecords = castMap(nextOwner.get("work"));
				Map<String, Object> workRecord = castMap(workRecords.get(workId));
				workRecord.put("state", "verified");
				workRecord.put("audit_receipt_id", audit.get("receipt_id"));
				workRecords.put(workId, workRecord);
				nextOwner.put("work", workRecords);
				writeJson(cohortRoot.resolve("owner.json"), nextOwner);
				WorkResult workResult = new WorkResult(workId, ".", work.assignmentId(), "verified", changedPaths,
					List.of());
				return new Audit(cohortRoot, owner.get("generation").longValue() + 1, snapshotId, receiptPath,
					List.of(workResult));
			}
		}
	}

	/**
	 * Revalidates and transactionally integrates one accepted delegate.
	 *
	 * @param cohort cohort directory
	 * @param workId work identifier
	 * @return integration identity
	 * @throws IOException if integration fails or recovery is required
	 */
	public Integration integrate(Path cohort, String workId) throws IOException
	{
		Path cohortRoot = cohort.toRealPath();
		Path lockPath = cohortRoot.resolve("cohort.lock");
		synchronized (scope.fileLocks().monitor(lockPath))
		{
			Optional<ExclusiveFileLock> available = scope.fileLocks().tryLock(lockPath,
				path -> FileChannel.open(path, StandardOpenOption.WRITE));
			if (available.isEmpty())
				throw new IOException("Cohort is busy: " + cohortRoot);
			try (ExclusiveFileLock held = available.get())
			{
				if (!held.isValid())
					throw new IOException("Cohort lock is not valid: " + lockPath);
				verifyProjectIdentity(safeGitEnvironment(scope.temporaryDirectory()));
				Plan plan = Plan.read(scope.json(), cohortRoot.resolve("plan.json"));
				JsonNode owner = scope.json().readTree(cohortRoot.resolve("owner.json").toFile());
				if (owner == null || !owner.get("plan_id").stringValue().equals(plan.identity()) ||
					!owner.get("state").stringValue().equals("prepared"))
					throw new IOException("Cohort ownership or state is invalid: " + cohortRoot);
				Plan.Work work = plan.work(workId);
				JsonNode workOwner = owner.get("work").get(workId);
				if (workOwner == null || !workOwner.get("assignment_id").stringValue().equals(work.assignmentId()))
					throw new IOException("Work is not verified for this assignment: " + workId);
				Path auditPath = cohortRoot.resolve("work").resolve(workId).resolve("audit.json");
				JsonNode audit = scope.json().readTree(auditPath.toFile());
				Path auditReceiptPath = cohortRoot.resolve("receipts/audit-" + workId + ".json");
				JsonNode auditReceipt = scope.json().readTree(auditReceiptPath.toFile());
				String snapshotId = owner.get("snapshot_id").stringValue();
				Path preparationPath = cohortRoot.resolve("receipts/prepare-" + workId + ".json");
				JsonNode preparation = scope.json().readTree(preparationPath.toFile());
				if (!validReceipt(preparation, preparationPath) ||
					!preparation.get("phase").stringValue().equals("preparation_probe") ||
					!preparation.get("work_id").stringValue().equals(workId) ||
					!preparation.get("plan_id").stringValue().equals(plan.identity()) ||
					!preparation.get("assignment_id").stringValue().equals(work.assignmentId()) ||
					!preparation.get("review_id").stringValue().equals(work.verification().reviewId()) ||
					!preparation.get("gate_spec_id").stringValue().equals(gateSpecIdentity(work)) ||
					!preparation.get("snapshot_id").stringValue().equals(snapshotId) ||
					!preparation.get("scope_result").stringValue().equals("passed") ||
					!preparation.get("receipt_id").stringValue().equals(
						workOwner.get("preparation_receipt_id").stringValue()))
					throw new IOException("Preparation receipt is invalid: " + preparationPath);
				if (audit == null || !validReceipt(audit, auditPath) || !validReceipt(auditReceipt, auditReceiptPath) ||
					!auditReceipt.get("receipt_id").stringValue().equals(audit.get("receipt_id").stringValue()) ||
					!audit.get("phase").stringValue().equals("candidate_probe") ||
					!audit.get("scope_result").stringValue().equals("passed") ||
					!audit.get("review_id").stringValue().equals(work.verification().reviewId()) ||
					!audit.get("gate_spec_id").stringValue().equals(gateSpecIdentity(work)) ||
					audit.get("candidate_gate_exit_code").intValue() != 0 ||
					!audit.get("plan_id").stringValue().equals(plan.identity()) ||
					!audit.get("work_id").stringValue().equals(workId) ||
					!audit.get("assignment_id").stringValue().equals(work.assignmentId()) ||
					!audit.get("receipt_id").stringValue().equals(workOwner.get("audit_receipt_id").stringValue()) ||
					!audit.get("snapshot_id").stringValue().equals(snapshotId))
					throw new IOException("Candidate audit is stale or invalid: " + auditPath);
				List<String> changedPaths = new ArrayList<>();
				for (JsonNode path : audit.get("changed_paths"))
					changedPaths.add(path.stringValue());
				SourceSnapshot snapshot = SourceSnapshot.read(scope.json(), cohortRoot);
				Path candidate = cohortRoot.resolve("work").resolve(workId).resolve("project").toRealPath();
				Map<String, String> candidateFiles = inventoryProject(candidate, plan.excludedRoots());
				String candidateId = digest(candidateFiles.toString().getBytes(
					java.nio.charset.StandardCharsets.UTF_8));
				if (!candidateId.equals(audit.get("candidate_manifest_id").stringValue()))
					throw new IOException("Candidate changed after audit: " + workId);
				if (!definitionIdentity(candidate, work).equals(audit.get("definition_manifest_id").stringValue()))
					throw new IOException("Candidate gate definitions changed after audit: " + workId);
				String workState = workOwner.get("state").stringValue();
				if (workState.equals("integrating") || workState.equals("recovery_required"))
					return reconcileInterrupted(cohortRoot, plan, owner, work, workOwner, audit, snapshot, candidate,
						changedPaths);
				if (!workState.equals("verified"))
					throw new IOException("Work is not verified for this assignment: " + workId);
				Path workVerification = Files.createDirectories(cohortRoot.resolve("verification").resolve(workId));
				Path integration = Files.createTempDirectory(workVerification, "integration-");
				Path combined = integration.resolve("project");
				Files.createDirectories(combined);
				copyProject(project, combined, plan.excludedRoots());
				for (String changedPath : changedPaths)
					applyCandidate(candidate, combined, changedPath);
				Map<String, String> beforeProbe = inventoryScoped(combined,
					List.of(".git", SessionDirectory.ROOT, ".cat-tmp"));
				Verification.GateResult probe = Verification.run(scope, buildAdmission, combined, work,
					integration.resolve("roots"), environment, snapshotId, plan.excludedRoots(),
					verificationOutputRoots(plan));
				Map<String, String> afterProbe = inventoryScoped(combined,
					List.of(".git", SessionDirectory.ROOT, ".cat-tmp"));
				List<String> probeChanges = changed(beforeProbe, afterProbe);
				if (hasUndeclaredOutput(probeChanges, work))
					throw new IOException("Combined gate wrote outside reviewed outputs: " + probeChanges);
				Verification.requireOutputCoverage(work, probeChanges);
				if (probe.exitCode() != 0)
					throw new IOException("Combined integration gate failed: " + probe.exitCode());
				Path transactionDirectory = Files.createDirectories(cohortRoot.resolve("transactions"));
				Path transaction = Files.createTempDirectory(transactionDirectory, "mutation-transaction-");
				Map<String, Object> integratingOwner = ownerRecord(plan, "prepared",
					owner.get("generation").longValue() + 1);
				copyReceiptIds(owner, integratingOwner, plan);
				integratingOwner.put("snapshot_id", snapshotId);
				integratingOwner.put("snapshot_commit", owner.get("snapshot_commit").stringValue());
				integratingOwner.put("snapshot_tree", owner.get("snapshot_tree").stringValue());
				Map<String, Object> integratingRecords = castMap(integratingOwner.get("work"));
				Map<String, Object> integratingWork = castMap(integratingRecords.get(workId));
				integratingWork.put("state", "integrating");
				integratingWork.put("transaction_path", cohortRoot.relativize(transaction).toString());
				integratingWork.put("audit_receipt_id", audit.get("receipt_id").stringValue());
				integratingRecords.put(workId, integratingWork);
				integratingOwner.put("work", integratingRecords);
				writeJson(cohortRoot.resolve("owner.json"), integratingOwner);
				Path finalInvocation = Files.createTempDirectory(workVerification, "parent-");
				Replay.Outcome outcome;
				List<Verification.GateResult> parentGates = new ArrayList<>();
				List<String> parentChanges = new ArrayList<>();
				try
				{
					outcome = Replay.integrate(project, candidate, snapshot, changedPaths, transaction,
						plan.excludedRoots(), verificationOutputRoots(plan),
						parent ->
						{
							Map<String, String> before = inventoryScoped(parent,
								List.of(".git", SessionDirectory.ROOT, ".cat-tmp"));
							Verification.GateResult gate = Verification.run(scope, buildAdmission, parent, work,
								finalInvocation.resolve("roots"), environment, snapshotId, plan.excludedRoots(),
								verificationOutputRoots(plan));
							Map<String, String> after = inventoryScoped(parent,
								List.of(".git", SessionDirectory.ROOT, ".cat-tmp"));
							parentGates.add(gate);
							parentChanges.addAll(changed(before, after));
							return gate.exitCode() == 0 && !hasUndeclaredOutput(changed(before, after), work);
						});
				}
				catch (IOException | RuntimeException e)
				{
					String failedState = "invalidated";
					if (e.getSuppressed().length > 0)
						failedState = "recovery_required";
					persistIntegrationFailure(cohortRoot, plan, owner, workId, work.assignmentId(), transaction,
						failedState, e);
					throw e;
				}
				Map<String, Object> integrationReceipt = new LinkedHashMap<>();
				integrationReceipt.put("format_version", 1);
				integrationReceipt.put("plan_id", plan.identity());
				integrationReceipt.put("work_id", workId);
				integrationReceipt.put("assignment_id", work.assignmentId());
				integrationReceipt.put("snapshot_id", snapshotId);
				integrationReceipt.put("candidate_manifest_id", candidateId);
				integrationReceipt.put("metadata_capabilities", metadataCapabilities(project, snapshot));
				integrationReceipt.put("phase", "integration_probe");
				integrationReceipt.put("scope_result", "passed");
				integrationReceipt.put("integration_invocation_id", cohortRoot.relativize(integration).toString());
				integrationReceipt.put("parent_invocation_id", cohortRoot.relativize(finalInvocation).toString());
				integrationReceipt.put("combined_probe_exit_code", probe.exitCode());
				integrationReceipt.put("combined_probe_admission_receipt_id", probe.admissionReceiptId());
				integrationReceipt.put("combined_probe_terminal_receipt_id", probe.terminalReceiptId());
				integrationReceipt.put("combined_probe_arguments", durableArguments(probe.arguments(), cohortRoot));
				integrationReceipt.put("diagnostic_paths", List.of(cohortRoot.relativize(probe.diagnosticsPath()).toString()));
				integrationReceipt.put("combined_definition_manifest_id", definitionIdentity(combined, work));
				integrationReceipt.put("combined_input_manifest_id", digest(beforeProbe.toString().getBytes(
					java.nio.charset.StandardCharsets.UTF_8)));
				integrationReceipt.put("combined_observed_changes", probeChanges);
				integrationReceipt.put("combined_declared_roots", declaredRoots(cohortRoot, work, probe));
				integrationReceipt.put("combined_owned_root_inventory", ownedInventory(cohortRoot, probe));
				if (!parentGates.isEmpty())
				{
					Verification.GateResult parentGate = parentGates.getFirst();
					integrationReceipt.put("parent_verification_exit_code", parentGate.exitCode());
					integrationReceipt.put("parent_verification_admission_receipt_id", parentGate.admissionReceiptId());
					integrationReceipt.put("parent_verification_terminal_receipt_id", parentGate.terminalReceiptId());
					integrationReceipt.put("parent_verification_arguments",
						durableArguments(parentGate.arguments(), cohortRoot));
					integrationReceipt.put("parent_verification_diagnostic_path",
						cohortRoot.relativize(parentGate.diagnosticsPath()).toString());
					integrationReceipt.put("parent_verification_changes", parentChanges);
					integrationReceipt.put("parent_declared_roots", declaredRoots(cohortRoot, work, parentGate));
					integrationReceipt.put("parent_owned_root_inventory", ownedInventory(cohortRoot, parentGate));
				}
				integrationReceipt.put("integrated", outcome.accepted());
				integrationReceipt.put("conflict_paths", outcome.conflictPaths());
				integrationReceipt.put("restored_paths", outcome.restoredPaths());
				Path receiptPath = cohortRoot.resolve("receipts/integration-" + workId + ".json");
				integrationReceipt.put("receipt_id", digest(scope.json().writeValueAsString(integrationReceipt).getBytes(
					java.nio.charset.StandardCharsets.UTF_8)));
				writeJson(receiptPath, integrationReceipt);
				if (!outcome.accepted())
				{
					String failedState = "invalidated";
					if (!outcome.conflictPaths().isEmpty())
						failedState = "recovery_required";
					persistIntegrationFailure(cohortRoot, plan, owner, workId, work.assignmentId(), transaction,
						failedState, new IOException("Combined parent gate rejected replay"));
					throw new IOException("Integration gate failed; restored paths " + outcome.restoredPaths() +
						", conflicts " + outcome.conflictPaths());
				}
				Map<String, Object> nextOwner = ownerRecord(plan, "prepared", owner.get("generation").longValue() + 2);
				copyReceiptIds(owner, nextOwner, plan);
				nextOwner.put("snapshot_id", snapshotId);
				nextOwner.put("snapshot_commit", owner.get("snapshot_commit").stringValue());
				nextOwner.put("snapshot_tree", owner.get("snapshot_tree").stringValue());
				Map<String, Object> workRecords = castMap(nextOwner.get("work"));
				Map<String, Object> workRecord = castMap(workRecords.get(workId));
				workRecord.put("state", "integrated");
				workRecord.put("integration_receipt_id", integrationReceipt.get("receipt_id"));
				workRecords.put(workId, workRecord);
				nextOwner.put("work", workRecords);
				writeJson(cohortRoot.resolve("owner.json"), nextOwner);
				WorkResult workResult = new WorkResult(workId, ".", work.assignmentId(), "integrated", changedPaths,
					outcome.conflictPaths());
				return new Integration(cohortRoot, owner.get("generation").longValue() + 2, snapshotId, receiptPath,
					List.of(workResult));
			}
		}
	}

	/**
	 * Copies an environment entry when it exists.
	 *
	 * @param source parent environment
	 * @param destination isolated child environment
	 * @param name environment variable
	 */
	private static void copyIfPresent(Map<String, String> source, Map<String, String> destination, String name)
	{
		String value = source.get(name);
		if (value != null)
			destination.put(name, value);
	}

	/**
	 * Creates a Git environment with configuration injection disabled.
	 *
	 * @param temporaryDirectory CAT-owned process temporary root
	 * @return complete safe Git environment
	 * @throws IOException if the empty global configuration cannot be created
	 */
	private Map<String, String> safeGitEnvironment(Path temporaryDirectory) throws IOException
	{
		Path emptyConfig = temporaryDirectory.resolve("delegation-empty-gitconfig");
		if (Files.notExists(emptyConfig))
			Files.createFile(emptyConfig);
		Map<String, String> result = new HashMap<>();
		copyIfPresent(environment, result, "PATH");
		result.put("HOME", temporaryDirectory.toString());
		result.put("GIT_CONFIG_NOSYSTEM", "1");
		result.put("GIT_CONFIG_GLOBAL", emptyConfig.toString());
		result.put("GIT_TERMINAL_PROMPT", "0");
		result.put("GIT_OPTIONAL_LOCKS", "0");
		return Map.copyOf(result);
	}

	/**
	 * Confirms the explicit project directory is its repository root using a read-only Git query.
	 *
	 * @param gitEnvironment isolated Git environment
	 * @throws IOException if the repository root differs or Git cannot answer
	 */
	private void verifyProjectIdentity(Map<String, String> gitEnvironment) throws IOException
	{
		String root = new Git(project, gitEnvironment, builder -> scope.executableFiles().run(builder)).
			run("rev-parse", "--show-toplevel").strip();
		if (!project.toString().equals(Path.of(root).toRealPath().toString()))
			throw new IOException("Project path does not identify its Git root: " + project);
	}

	/**
	 * Creates a serialized owner record for a cohort transition.
	 *
	 * @param plan validated plan
	 * @param state current cohort state
	 * @param generation current state generation
	 * @return owner record fields
	 */
	private static Map<String, Object> ownerRecord(Plan plan, String state, long generation)
	{
		Map<String, Object> owner = new LinkedHashMap<>();
		owner.put("format_version", 1);
		owner.put("plan_id", plan.identity());
		owner.put("cleanup_owner", plan.cleanupOwner());
		owner.put("state", state);
		owner.put("generation", generation);
		Map<String, Object> work = new TreeMap<>();
		plan.work().forEach(item ->
		{
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("assignment_id", item.assignmentId());
			entry.put("state", "unclaimed");
			work.put(item.workId(), entry);
		});
		owner.put("work", work);
		return owner;
	}

	/**
	 * Copies a JSON object map after validating its keys.
	 *
	 * @param value parsed JSON value
	 * @return mutable string-keyed map
	 * @throws IOException if the value is not an object with string keys
	 */
	private static Map<String, Object> castMap(Object value) throws IOException
	{
		if (!(value instanceof Map<?, ?> source))
			throw new IOException("Invalid work ownership record");
		Map<String, Object> result = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : source.entrySet())
		{
			if (!(entry.getKey() instanceof String key))
				throw new IOException("Invalid work ownership key");
			result.put(key, entry.getValue());
		}
		return result;
	}

	/**
	 * Carries receipt ownership identities across serialized cohort generations.
	 *
	 * @param current current owner record
	 * @param next next owner record
	 * @param plan cohort plan
	 * @throws IOException if owner work records are malformed
	 */
	private static void copyReceiptIds(JsonNode current, Map<String, Object> next, Plan plan) throws IOException
	{
		Map<String, Object> workRecords = castMap(next.get("work"));
		for (Plan.Work work : plan.work())
		{
			JsonNode previous = current.get("work").get(work.workId());
			Map<String, Object> workRecord = castMap(workRecords.get(work.workId()));
			if (previous.has("state"))
				workRecord.put("state", previous.get("state").stringValue());
			for (String field : List.of("preparation_receipt_id", "audit_receipt_id"))
				if (previous.has(field))
					workRecord.put(field, previous.get(field).stringValue());
			workRecords.put(work.workId(), workRecord);
		}
		next.put("work", workRecords);
	}

	/**
	 * Retains an integration failure and advances ownership to a non-success state.
	 *
	 * @param cohort cohort root
	 * @param plan typed plan
	 * @param owner current owner record
	 * @param workId work identity
	 * @param assignmentId assignment identity
	 * @param transaction retained replay transaction
	 * @param state invalidated or recovery_required
	 * @param failure operation failure
	 * @throws IOException if failure evidence cannot be persisted
	 */
	private void persistIntegrationFailure(Path cohort, Plan plan, JsonNode owner, String workId, String assignmentId,
		Path transaction, String state, Exception failure) throws IOException
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("format_version", 1);
		receipt.put("plan_id", plan.identity());
		receipt.put("work_id", workId);
		receipt.put("assignment_id", assignmentId);
		receipt.put("phase", "integration_failure");
		receipt.put("state", state);
		receipt.put("transaction_path", cohort.relativize(transaction).toString());
		receipt.put("failure_type", failure.getClass().getName());
		receipt.put("failure", String.valueOf(failure.getMessage()));
		receipt.put("receipt_id", digest(scope.json().writeValueAsString(receipt).getBytes(
			java.nio.charset.StandardCharsets.UTF_8)));
		Path receiptPath = cohort.resolve("receipts/integration-failure-" + workId + ".json");
		writeJson(receiptPath, receipt);
		Map<String, Object> nextOwner = ownerRecord(plan, "prepared", owner.get("generation").longValue() + 2);
		copyReceiptIds(owner, nextOwner, plan);
		nextOwner.put("snapshot_id", owner.get("snapshot_id").stringValue());
		nextOwner.put("snapshot_commit", owner.get("snapshot_commit").stringValue());
		nextOwner.put("snapshot_tree", owner.get("snapshot_tree").stringValue());
		Map<String, Object> workRecords = castMap(nextOwner.get("work"));
		Map<String, Object> workRecord = castMap(workRecords.get(workId));
		workRecord.put("state", state);
		workRecord.put("transaction_path", cohort.relativize(transaction).toString());
		workRecord.put("integration_failure_receipt_id", receipt.get("receipt_id"));
		workRecords.put(workId, workRecord);
		nextOwner.put("work", workRecords);
		writeJson(cohort.resolve("owner.json"), nextOwner);
	}

	/**
	 * Reconciles a retained interrupted transaction before considering another replay.
	 *
	 * @param cohort cohort root
	 * @param plan typed plan
	 * @param owner current owner record
	 * @param work work assignment
	 * @param workOwner current work state
	 * @param audit candidate audit
	 * @param snapshot immutable source preimages
	 * @param candidate retained candidate project
	 * @param changedPaths audited source paths
	 * @return completed integration when the terminal journal records success
	 * @throws IOException if recovery is incomplete or failed
	 */
	private Integration reconcileInterrupted(Path cohort, Plan plan, JsonNode owner, Plan.Work work,
		JsonNode workOwner, JsonNode audit, SourceSnapshot snapshot, Path candidate, List<String> changedPaths)
		throws IOException
	{
		String transactionPath = workOwner.get("transaction_path").stringValue();
		Path transaction = cohort.resolve(transactionPath).normalize();
		if (!transaction.startsWith(cohort) || transaction.equals(cohort))
			throw new IOException("recovery_required: invalid retained transaction path for " + work.workId());
		Replay.Outcome outcome = Replay.reconcile(scope.json(), project, candidate, snapshot, changedPaths,
			transaction, plan.excludedRoots(), verificationOutputRoots(plan));
		String state = "invalidated";
		if (outcome.accepted())
			state = "integrated";
		else if (!outcome.conflictPaths().isEmpty())
			state = "recovery_required";
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("format_version", 1);
		receipt.put("plan_id", plan.identity());
		receipt.put("work_id", work.workId());
		receipt.put("assignment_id", work.assignmentId());
		receipt.put("snapshot_id", snapshot.identity());
		receipt.put("audit_receipt_id", audit.get("receipt_id").stringValue());
		receipt.put("transaction_path", transactionPath);
		receipt.put("phase", "recovery");
		receipt.put("integrated", outcome.accepted());
		receipt.put("state", state);
		receipt.put("restored_paths", outcome.restoredPaths());
		receipt.put("conflict_paths", outcome.conflictPaths());
		receipt.put("receipt_id", digest(scope.json().writeValueAsString(receipt).getBytes(
			java.nio.charset.StandardCharsets.UTF_8)));
		Path receiptPath = cohort.resolve("receipts/recovery-" + work.workId() + ".json");
		writeJson(receiptPath, receipt);
		Map<String, Object> nextOwner = ownerRecord(plan, "prepared", owner.get("generation").longValue() + 1);
		copyReceiptIds(owner, nextOwner, plan);
		nextOwner.put("snapshot_id", snapshot.identity());
		nextOwner.put("snapshot_commit", owner.get("snapshot_commit").stringValue());
		nextOwner.put("snapshot_tree", owner.get("snapshot_tree").stringValue());
		Map<String, Object> workRecords = castMap(nextOwner.get("work"));
		Map<String, Object> workRecord = castMap(workRecords.get(work.workId()));
		workRecord.put("state", state);
		workRecord.put("transaction_path", transactionPath);
		workRecord.put("recovery_receipt_id", receipt.get("receipt_id"));
		workRecords.put(work.workId(), workRecord);
		nextOwner.put("work", workRecords);
		writeJson(cohort.resolve("owner.json"), nextOwner);
		if (!outcome.accepted())
		{
			if (state.equals("recovery_required"))
				throw new IOException("recovery_required: conflicting paths " + outcome.conflictPaths());
			throw new IOException("Interrupted replay was rolled back; re-audit required for " + work.workId());
		}
		WorkResult workResult = new WorkResult(work.workId(), ".", work.assignmentId(), state, changedPaths,
			outcome.conflictPaths());
		return new Integration(cohort, owner.get("generation").longValue() + 1, snapshot.identity(), receiptPath,
			List.of(workResult));
	}

	/**
	 * Checks a retained receipt against its canonical tool-derived digest.
	 *
	 * @param receipt serialized receipt
	 * @param path receipt path for diagnostics
	 * @return whether the receipt payload and identifier agree
	 * @throws IOException if the receipt digest is invalid
	 */
	private boolean validReceipt(JsonNode receipt, Path path) throws IOException
	{
		if (receipt == null || !receipt.isObject() || !receipt.has("receipt_id") ||
			!receipt.get("receipt_id").isString())
			return false;
		Map<String, Object> payload = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> property : receipt.properties())
			if (!property.getKey().equals("receipt_id"))
				payload.put(property.getKey(), property.getValue());
		String actual = digest(scope.json().writeValueAsString(payload).getBytes(
			java.nio.charset.StandardCharsets.UTF_8));
		if (!actual.equals(receipt.get("receipt_id").stringValue()))
			throw new IOException("Receipt digest is invalid: " + path);
		return true;
	}

	/**
	 * Computes the identity of a work unit's reviewed gate specification.
	 *
	 * @param work reviewed work unit
	 * @return digest of the typed specification
	 */
	private static String gateSpecIdentity(Plan.Work work)
	{
		return digest((Verification.PROFILE_ID + "\n" + work.verification()).getBytes(
			java.nio.charset.StandardCharsets.UTF_8));
	}

	/**
	 * Lists retained regular diagnostic logs for a failed preparation.
	 *
	 * @param cohort cohort root
	 * @param plan reviewed work plan
	 * @return cohort-relative paths of retained diagnostics
	 * @throws IOException if a present log cannot be inspected
	 */
	private static List<String> diagnosticPaths(Path cohort, Plan plan) throws IOException
	{
		List<String> result = new ArrayList<>();
		for (Plan.Work work : plan.work())
		{
			Path diagnostics = cohort.resolve("verification").resolve(work.workId()).resolve("roots/diagnostics");
			for (String name : List.of("wrapper.log", "maven.log"))
			{
				Path path = diagnostics.resolve(name);
				if (Files.exists(path, LinkOption.NOFOLLOW_LINKS))
				{
					BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
						LinkOption.NOFOLLOW_LINKS);
					if (attributes.isRegularFile())
						result.add(cohort.relativize(path).toString().replace('\\', '/'));
				}
			}
		}
		return List.copyOf(result);
	}

	/**
	 * Removes only editable preparation roots while retaining diagnostics.
	 *
	 * @param cohort cohort root
	 * @param plan reviewed work plan
	 * @throws IOException if cleanup fails
	 */
	private static void cleanupFailedPreparation(Path cohort, Plan plan) throws IOException
	{
		for (Plan.Work work : plan.work())
		{
			Path invocation = cohort.resolve("verification").resolve(work.workId());
			deleteTree(invocation.resolve("project"));
			Path roots = invocation.resolve("roots");
			for (String name : List.of("home", "repository", "temporary"))
				deleteTree(roots.resolve(name));
		}
	}

	/**
	 * Writes one JSON record.
	 *
	 * @param path destination
	 * @param value JSON object fields
	 * @throws IOException if serialization or writing fails
	 */
	private void writeJson(Path path, Map<String, Object> value) throws IOException
	{
		Path parent = path.toAbsolutePath().normalize().getParent();
		Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
		try
		{
			Files.writeString(temporary, scope.json().writeValueAsString(value));
			Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Hashes every directory entry under a probe project.
	 *
	 * @param root project root
	 * @return sorted entry identities
	 * @throws IOException if an entry cannot be read
	 */
	private static Map<String, String> inventory(Path root) throws IOException
	{
		Map<String, String> result = new TreeMap<>();
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path path : paths.filter(candidate -> !candidate.equals(root)).toList())
			{
				String relative = root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
				if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
					result.put(relative + "/", "directory:" + Files.getLastModifiedTime(path,
						LinkOption.NOFOLLOW_LINKS).toMillis() + ":" + permissions(path) + ":" +
						SourceSnapshot.metadataIdentity(path));
				else if (Files.isSymbolicLink(path))
					result.put(relative, "link:" + Files.readSymbolicLink(path) + ":" + Files.getLastModifiedTime(path,
						LinkOption.NOFOLLOW_LINKS).toMillis() + ":" + SourceSnapshot.metadataIdentity(path));
				else if (Files.isRegularFile(path))
					result.put(relative, digest(path) + ":" + permissions(path) + ":" + Files.getLastModifiedTime(path,
						LinkOption.NOFOLLOW_LINKS).toMillis() + ":" + SourceSnapshot.metadataIdentity(path));
				else
					result.put(relative, "special");
			}
		}
		return result;
	}

	/**
	 * Describes supported metadata views for every captured source and ancestor entry.
	 *
	 * @param project real source project root
	 * @param snapshot immutable source image
	 * @return per-path metadata capabilities and captured identities
	 * @throws IOException if a captured entry cannot be inspected
	 */
	private static List<Map<String, Object>> metadataCapabilities(Path project, SourceSnapshot snapshot)
		throws IOException
	{
		List<Map<String, Object>> capabilities = new ArrayList<>();
		for (SourceSnapshot.Entry entry : snapshot.entries())
		{
			Path path = project;
			if (!entry.path().equals("."))
				path = project.resolve(entry.path());
			Map<String, Object> capability = new LinkedHashMap<>();
			capability.put("path", entry.path());
			capability.put("present", entry.present());
			capability.put("metadata_id", entry.metadataIdentity());
			if (entry.present())
			{
				Path storePath = path.getParent();
				if (storePath == null)
					storePath = path;
				FileStore store = Files.getFileStore(storePath);
				List<String> views = new ArrayList<>(List.of("basic"));
				for (String view : List.of("owner", "posix", "acl", "dos", "user"))
					if (store.supportsFileAttributeView(view) &&
						!(entry.kind().equals("symlink") && !view.equals("owner") && !view.equals("posix")))
						views.add(view);
				capability.put("supported_views", views);
			}
			else
				capability.put("supported_views", List.of());
			capabilities.add(capability);
		}
		return List.copyOf(capabilities);
	}

	/**
	 * Inventories project writes including empty directories and supported entry metadata.
	 *
	 * @param root project root
	 * @param excludedRoots project-relative roots omitted from scope observation
	 * @return sorted path-to-entry identities
	 * @throws IOException if an entry cannot be observed
	 */
	private static Map<String, String> inventoryScoped(Path root, List<String> excludedRoots) throws IOException
	{
		Map<String, String> result = new TreeMap<>();
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path path : paths.filter(candidate -> !candidate.equals(root)).toList())
			{
				String relative = root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
				if (relative.equals(".git") || relative.startsWith(".git/") ||
					excludedRoots.stream().anyMatch(exclusion -> relative.equals(exclusion) ||
						relative.startsWith(exclusion + "/")))
					continue;
				if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
					result.put(relative + "/", "directory:" + Files.getLastModifiedTime(path,
						LinkOption.NOFOLLOW_LINKS).toMillis() + ":" + permissions(path) + ":" +
						SourceSnapshot.metadataIdentity(path));
				else if (Files.isSymbolicLink(path))
					result.put(relative, "link:" + Files.readSymbolicLink(path) + ":" + Files.getLastModifiedTime(path,
						LinkOption.NOFOLLOW_LINKS).toMillis() + ":" + SourceSnapshot.metadataIdentity(path));
				else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
					result.put(relative, "file:" + digest(path) + ":" + permissions(path) + ":" +
						Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis() + ":" +
						SourceSnapshot.metadataIdentity(path));
				else
					throw new IOException("Unsupported project entry in scope inventory: " + relative);
			}
		}
		return result;
	}

	/**
	 * Inventories regular files and leaf links below a project, excluding declared roots.
	 *
	 * @param root project root
	 * @param excludedRoots project-relative excluded roots
	 * @return sorted path-to-content identities
	 * @throws IOException if an unsafe or unsupported entry is encountered
	 */
	private static Map<String, String> inventoryProject(Path root, List<String> excludedRoots) throws IOException
	{
		Map<String, String> result = new TreeMap<>();
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path path : paths.filter(candidate -> !candidate.equals(root)).toList())
			{
				String relative = root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
				if (relative.equals(".git") || relative.startsWith(".git/"))
					continue;
				if (excludedRoots.stream().anyMatch(exclusion -> relative.equals(exclusion) ||
					relative.startsWith(exclusion + "/")))
					continue;
				if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
					continue;
				if (Files.isSymbolicLink(path))
				{
					BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
						LinkOption.NOFOLLOW_LINKS);
					result.put(relative, "symlink:" + Files.readSymbolicLink(path) + ":" +
						attributes.lastModifiedTime().toMillis() + ":" + SourceSnapshot.metadataIdentity(path));
				}
				else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
				{
					if (linkCount(path) > 1)
						throw new IOException("Candidate contains a hard-linked file: " + relative);
					BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
						LinkOption.NOFOLLOW_LINKS);
					Set<PosixFilePermission> permissions = permissions(path);
					boolean executable = permissions.contains(PosixFilePermission.OWNER_EXECUTE) || Files.isExecutable(path);
					result.put(relative, "file:" + digest(path) + ":" + executable + ":" + permissions + ":" +
						attributes.lastModifiedTime().toMillis() + ":" + SourceSnapshot.metadataIdentity(path));
				}
				else
					throw new IOException("Unsupported candidate file type: " + relative);
			}
		}
		return result;
	}

	/**
	 * Finds newly created empty directories unrelated to an authorized output or write path.
	 *
	 * @param root candidate project
	 * @param excludedRoots generated roots omitted from source admission
	 * @param writePaths authorized source paths
	 * @return unowned empty directory paths
	 * @throws IOException if a directory cannot be inspected
	 */
	private static List<String> unownedEmptyDirectories(Path root, List<String> excludedRoots,
		List<String> writePaths) throws IOException
	{
		List<String> result = new ArrayList<>();
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path path : paths.filter(value -> !value.equals(root) && Files.isDirectory(value,
				LinkOption.NOFOLLOW_LINKS)).toList())
			{
				String relative = root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
				if (excludedRoots.stream().anyMatch(exclusion -> relative.equals(exclusion) ||
					relative.startsWith(exclusion + "/")) ||
					writePaths.stream().anyMatch(write -> write.startsWith(relative + "/")) ||
					!isEmptyDirectory(path))
					continue;
				result.add(relative);
			}
		}
		return List.copyOf(result);
	}

	/**
	 * Checks whether a directory has no child entries.
	 *
	 * @param directory directory to inspect
	 * @return whether the directory is empty
	 * @throws IOException if the directory cannot be listed
	 */
	private static boolean isEmptyDirectory(Path directory) throws IOException
	{
		try (DirectoryStream<Path> children = Files.newDirectoryStream(directory))
		{
			return !children.iterator().hasNext();
		}
	}

	/**
	 * Removes a tree owned by this operation, from leaves towards its root.
	 *
	 * @param root operation-owned tree
	 * @throws IOException if any owned entry cannot be removed
	 */
	private static void deleteTree(Path root) throws IOException
	{
		if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS))
			return;
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
				Files.delete(path);
		}
	}

	/**
	 * Returns a file's hard-link count where the platform exposes it.
	 *
	 * @param path regular file
	 * @return observed number of directory links
	 * @throws IOException if the link count is unavailable or invalid
	 */
	private static long linkCount(Path path) throws IOException
	{
		try
		{
			Object value = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
			if (!(value instanceof Number count) || count.longValue() < 1)
				throw new IOException("Unable to verify hard-link count: " + path);
			return count.longValue();
		}
		catch (UnsupportedOperationException | IllegalArgumentException e)
		{
			throw new IOException("Unable to verify hard-link count: " + path, e);
		}
	}

	/**
	 * Copies a project tree into a fresh gate workspace while omitting generated and Git roots.
	 *
	 * @param source source project
	 * @param destination fresh project root
	 * @param excludedRoots project-relative excluded roots
	 * @throws IOException if a file cannot be copied
	 */
	private static void copyProject(Path source, Path destination, List<String> excludedRoots) throws IOException
	{
		try (Stream<Path> paths = Files.walk(source))
		{
			for (Path path : paths.toList())
			{
				if (path.equals(source))
					continue;
				String relative = source.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
				if (relative.equals(".git") || relative.startsWith(".git/") ||
					excludedRoots.stream().anyMatch(exclusion -> relative.equals(exclusion) ||
						relative.startsWith(exclusion + "/")))
					continue;
				Path target = destination.resolve(relative);
				if (Files.isSymbolicLink(path))
				{
					Files.createDirectories(target.getParent());
					Files.createSymbolicLink(target, Files.readSymbolicLink(path));
				}
				else if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
					Files.createDirectories(target);
				else
				{
					Files.createDirectories(target.getParent());
					Files.copy(path, target);
				}
			}
		}
	}

	/**
	 * Applies one audited leaf postimage to a disposable combined project.
	 *
	 * @param candidate audited delegate project
	 * @param combined combined parent copy
	 * @param relative reviewed changed path
	 * @throws IOException if the path is unsafe or cannot be copied
	 */
	private static void applyCandidate(Path candidate, Path combined, String relative) throws IOException
	{
		Path source = candidate.resolve(relative).normalize();
		Path target = combined.resolve(relative).normalize();
		if (!source.startsWith(candidate) || !target.startsWith(combined))
			throw new IOException("Unsafe audited path: " + relative);
		for (Path ancestor = target.getParent(); ancestor != null && ancestor.startsWith(combined);
			ancestor = ancestor.getParent())
			if (Files.isSymbolicLink(ancestor))
				throw new IOException("Combined project has a symlink ancestor: " + relative);
		Files.deleteIfExists(target);
		if (Files.notExists(source, LinkOption.NOFOLLOW_LINKS))
			return;
		Files.createDirectories(target.getParent());
		if (Files.isSymbolicLink(source))
			Files.createSymbolicLink(target, Files.readSymbolicLink(source));
		else if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
			Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
		else
			throw new IOException("Audited path is not a regular file or symlink: " + relative);
	}

	/**
	 * Reads the full content and restoration metadata for one candidate entry.
	 *
	 * @param root project root
	 * @param relative project-relative path
	 * @param signature entry's inventory identity, or null for absence
	 * @return candidate entry
	 * @throws IOException if the entry is unsafe or unsupported
	 */
	private static SourceSnapshot.Entry observe(Path root, String relative, String signature) throws IOException
	{
		if (signature == null)
			return absent(relative);
		Path path = root.resolve(relative).normalize();
		if (!path.startsWith(root) || Files.isSymbolicLink(path) && Files.isSymbolicLink(path.getParent()))
			throw new IOException("Unsafe candidate path: " + relative);
		BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (attributes.isSymbolicLink())
		{
			String link = Files.readSymbolicLink(path).toString();
			Path resolved = path.getParent().resolve(link).normalize();
			if (Path.of(link).isAbsolute() || !resolved.startsWith(root))
				throw new IOException("Candidate symlink leaves project: " + relative);
			return new SourceSnapshot.Entry(relative, true, "symlink",
				digest(link.getBytes(java.nio.charset.StandardCharsets.UTF_8)), false, Set.of(),
				attributes.lastModifiedTime().toMillis(), link, SourceSnapshot.metadataIdentity(path));
		}
		if (!attributes.isRegularFile())
			throw new IOException("Unsupported candidate file type: " + relative);
		Set<PosixFilePermission> permissions = permissions(path);
		boolean executable = permissions.contains(PosixFilePermission.OWNER_EXECUTE) || Files.isExecutable(path);
		return new SourceSnapshot.Entry(relative, true, "file", digest(path), executable, permissions,
			attributes.lastModifiedTime().toMillis(), "", SourceSnapshot.metadataIdentity(path));
	}

	/**
	 * Reads POSIX permissions when the active filesystem exposes that view.
	 *
	 * @param path file to inspect
	 * @return POSIX permissions, or an empty set when unsupported
	 * @throws IOException if the permission view cannot be read
	 */
	private static Set<PosixFilePermission> permissions(Path path) throws IOException
	{
		try
		{
			return Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
		}
		catch (UnsupportedOperationException _)
		{
			return Set.of();
		}
	}

	/**
	 * Creates an absent entry preimage.
	 *
	 * @param path project-relative path
	 * @return absent entry
	 */
	private static SourceSnapshot.Entry absent(String path)
	{
		return new SourceSnapshot.Entry(path, false, "absent", "", false, Set.of(), 0, "", "");
	}

	/**
	 * Ensures a candidate leaf link targets another explicitly selected source path.
	 *
	 * @param root candidate project root
	 * @param relative symlink path
	 * @param entry observed entry
	 * @param selectedSourcePaths reviewed source set
	 * @throws IOException if the symlink destination is not selected
	 */
	private static void validateCandidateLink(Path root, String relative, SourceSnapshot.Entry entry,
		Set<String> selectedSourcePaths) throws IOException
	{
		if (!entry.kind().equals("symlink"))
			return;
		Path target = Path.of(entry.linkText());
		Path resolved = root.resolve(relative).getParent().resolve(target).normalize();
		if (target.isAbsolute() || !resolved.startsWith(root))
			throw new IOException("Candidate symlink target leaves selected source: " + relative);
		String targetPath = root.relativize(resolved).toString().replace(resolved.getFileSystem().getSeparator(), "/");
		if (!selectedSourcePaths.contains(targetPath))
			throw new IOException("Candidate symlink target is not selected source: " + relative + " -> " + targetPath);
	}

	/**
	 * Compares source content and metadata, excluding OS-maintained access and change times.
	 *
	 * @param left first entry
	 * @param right second entry
	 * @return whether all retained fields match
	 */
	private static boolean sameEntry(SourceSnapshot.Entry left, SourceSnapshot.Entry right)
	{
		return left.present() == right.present() && left.kind().equals(right.kind()) &&
			left.sha256().equals(right.sha256()) && left.executable() == right.executable() &&
			left.permissions().equals(right.permissions()) && left.lastModifiedMillis() == right.lastModifiedMillis() &&
			left.linkText().equals(right.linkText()) && left.metadataIdentity().equals(right.metadataIdentity());
	}

	/**
	 * Checks that every gate-observed project write belongs to a reviewed output root.
	 *
	 * @param changes observed relative paths
	 * @param work work unit and its reviewed roots
	 * @return whether any path is undeclared
	 */
	private static boolean hasUndeclaredOutput(List<String> changes, Plan.Work work)
	{
		return changes.stream().anyMatch(path -> !isAllowedOutput(path, work.verification().projectOutputRoots()));
	}

	/**
	 * Collects every project output root that a cohort's work gates may write.
	 *
	 * @param plan reviewed cohort plan
	 * @return sorted distinct project output roots
	 */
	private static List<String> verificationOutputRoots(Plan plan)
	{
		return plan.work().stream().flatMap(work -> work.verification().projectOutputRoots().stream()).distinct().sorted().
			toList();
	}

	/**
	 * Checks a changed path and unavoidable output-ancestor metadata against reviewed roots.
	 *
	 * @param path changed project path
	 * @param outputRoots reviewed generated-output roots
	 * @return whether the change is covered by an output root
	 */
	private static boolean isAllowedOutput(String path, List<String> outputRoots)
	{
		String changedPath = path;
		if (path.endsWith("/"))
			changedPath = path.substring(0, path.length() - 1);
		for (String root : outputRoots)
			if (changedPath.equals(root) || changedPath.startsWith(root + "/") || root.startsWith(changedPath + "/"))
				return true;
		return false;
	}

	/**
	 * Rewrites invocation-owned absolute paths to paths relative to the cohort for durable records.
	 *
	 * @param arguments actual argv
	 * @param cohort cohort root
	 * @return argv with cohort-relative Maven repository location
	 * @throws IOException if an owned path is outside the cohort
	 */
	private static List<String> durableArguments(List<String> arguments, Path cohort) throws IOException
	{
		List<String> result = new ArrayList<>();
		for (String argument : arguments)
		{
			String prefix = "-Dmaven.repo.local=";
			if (argument.startsWith(prefix))
			{
				Path path = Path.of(argument.substring(prefix.length())).toAbsolutePath().normalize();
				if (!path.startsWith(cohort))
					throw new IOException("Maven repository is outside its cohort: " + path);
				result.add(prefix + "{cohort}/" + cohort.relativize(path).toString().replace('\\', '/'));
			}
			else
				result.add(argument);
		}
		return List.copyOf(result);
	}

	/**
	 * Computes a path's SHA-256 digest using a bounded streaming buffer.
	 *
	 * @param path content path
	 * @return lowercase hexadecimal digest
	 * @throws IOException if the content cannot be read
	 */
	private static String digest(Path path) throws IOException
	{
		try
		{
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (java.io.InputStream input = Files.newInputStream(path))
			{
				byte[] buffer = new byte[8192];
				int count = input.read(buffer);
				while (count != -1)
				{
					digest.update(buffer, 0, count);
					count = input.read(buffer);
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new AssertionError("SHA-256 is required by Java", e);
		}
	}

	/**
	 * Returns paths whose presence or content changed.
	 *
	 * @param before initial inventory
	 * @param after final inventory
	 * @return changed project-relative paths
	 */
	private static List<String> changed(Map<String, String> before, Map<String, String> after)
	{
		Set<String> paths = new java.util.TreeSet<>(before.keySet());
		paths.addAll(after.keySet());
		return paths.stream().filter(path -> !Objects.equals(before.get(path), after.get(path))).toList();
	}

	/**
	 * Binds the reviewed gate definitions to their observed bytes and metadata.
	 *
	 * @param project probe project
	 * @param work reviewed work unit
	 * @return identity of the exact definition preimages
	 * @throws IOException if a definition cannot be observed
	 */
	private static String definitionIdentity(Path project, Plan.Work work) throws IOException
	{
		Map<String, String> definitions = new TreeMap<>();
		for (String definition : work.verification().definitionPaths())
		{
			Path path = project.resolve(definition);
			if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS))
				definitions.put(definition, "absent");
			else
				definitions.put(definition, inventoryProject(project, List.of()).get(definition));
		}
		return digest(definitions.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	/**
	 * Lists the project and invocation-owned roots in the gate's declared write scope.
	 *
	 * @param cohort cohort root
	 * @param work reviewed work unit
	 * @param gate completed invocation
	 * @return project-relative and cohort-relative declared roots
	 * @throws IOException if an invocation root is outside its cohort
	 */
	private static Map<String, List<String>> declaredRoots(Path cohort, Plan.Work work,
		Verification.GateResult gate) throws IOException
	{
		List<String> project = new ArrayList<>(work.verification().projectOutputRoots());
		List<String> owned = new ArrayList<>();
		for (Path path : gate.ownedRoots())
		{
			Path absolute = path.toAbsolutePath().normalize();
			if (!absolute.startsWith(cohort))
				throw new IOException("Invocation root is outside its cohort: " + path);
			owned.add(cohort.relativize(absolute).toString().replace('\\', '/'));
		}
		return Map.of("project", List.copyOf(project), "cohort", List.copyOf(owned));
	}

	/**
	 * Inventories the contents of invocation-owned roots after a gate completes.
	 *
	 * @param cohort cohort root
	 * @param gate completed invocation
	 * @return sorted cohort-relative entry identities
	 * @throws IOException if an owned entry cannot be observed
	 */
	private static Map<String, String> ownedInventory(Path cohort, Verification.GateResult gate) throws IOException
	{
		Map<String, String> result = new TreeMap<>();
		for (Path root : gate.ownedRoots())
		{
			if (!Files.exists(root))
				continue;
			try (Stream<Path> paths = Files.walk(root))
			{
				for (Path path : paths.toList())
				{
					String key = cohort.relativize(path).toString().replace('\\', '/');
					if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
						result.put(key + "/", "directory:" + Files.getLastModifiedTime(path,
							LinkOption.NOFOLLOW_LINKS).toMillis() + ":" + permissions(path));
					else if (Files.isSymbolicLink(path))
						result.put(key, "symlink:" + Files.readSymbolicLink(path) + ":" +
							Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis());
					else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
						result.put(key, "file:" + digest(path) + ":" + permissions(path) + ":" +
							Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis());
					else
						throw new IOException("Unsupported invocation-owned entry: " + key);
				}
			}
		}
		return result;
	}

	/**
	 * Computes a byte array's SHA-256 identity.
	 *
	 * @param bytes content
	 * @return lowercase hexadecimal digest
	 */
	private static String digest(byte[] bytes)
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new AssertionError("SHA-256 is required by Java", e);
		}
	}

	/**
	 * A successfully prepared cohort.
	 *
	 * @param cohortPath cohort directory
	 * @param generation state generation
	 * @param snapshotId immutable source identity
	 * @param receiptPath preparation evidence
	 * @param work per-work initial state
	 */
	public record Prepared(Path cohortPath, long generation, String snapshotId, Path receiptPath,
		List<WorkResult> work)
	{
		/**
		 * Copies work results into an immutable list.
		 *
		 * @param cohortPath cohort directory
		 * @param generation state generation
		 * @param snapshotId immutable source identity
		 * @param receiptPath preparation evidence
		 * @param work per-work initial state
		 */
		public Prepared
		{
			work = List.copyOf(work);
		}
	}

	/**
	 * A completed candidate audit.
	 *
	 * @param cohortPath cohort directory
	 * @param generation state generation
	 * @param snapshotId immutable source identity
	 * @param receiptPath audit evidence
	 * @param work per-work audit state
	 */
	public record Audit(Path cohortPath, long generation, String snapshotId, Path receiptPath, List<WorkResult> work)
	{
		/**
		 * Copies work results into an immutable list.
		 *
		 * @param cohortPath cohort directory
		 * @param generation state generation
		 * @param snapshotId immutable source identity
		 * @param receiptPath audit evidence
		 * @param work per-work audit state
		 */
		public Audit
		{
			work = List.copyOf(work);
		}
	}

	/**
	 * A completed integration.
	 *
	 * @param cohortPath cohort directory
	 * @param generation state generation
	 * @param snapshotId immutable source identity
	 * @param receiptPath integration evidence
	 * @param work per-work integration state
	 */
	public record Integration(Path cohortPath, long generation, String snapshotId, Path receiptPath,
		List<WorkResult> work)
	{
		/**
		 * Copies work results into an immutable list.
		 *
		 * @param cohortPath cohort directory
		 * @param generation state generation
		 * @param snapshotId immutable source identity
		 * @param receiptPath integration evidence
		 * @param work per-work integration state
		 */
		public Integration
		{
			work = List.copyOf(work);
		}
	}

	/**
	 * One work unit's structured lifecycle result.
	 *
	 * @param workId work identifier
	 * @param projectPath project-relative project path
	 * @param assignmentId work assignment identity
	 * @param state lifecycle state
	 * @param changedPaths observed changed paths
	 * @param conflictPaths observed conflict paths
	 */
	public record WorkResult(String workId, String projectPath, String assignmentId, String state,
		List<String> changedPaths, List<String> conflictPaths)
	{
		/**
		 * Copies path lists into immutable collections.
		 *
		 * @param workId work identifier
		 * @param projectPath project-relative project path
		 * @param assignmentId work assignment identity
		 * @param state lifecycle state
		 * @param changedPaths observed changed paths
		 * @param conflictPaths observed conflict paths
		 */
		public WorkResult
		{
			changedPaths = List.copyOf(changedPaths);
			conflictPaths = List.copyOf(conflictPaths);
		}
	}
}
