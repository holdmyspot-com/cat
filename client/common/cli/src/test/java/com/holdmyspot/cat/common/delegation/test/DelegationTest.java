package com.holdmyspot.cat.common.delegation.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.secrets.ExecutableFilesSecrets;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.delegation.Delegation;
import com.holdmyspot.cat.common.delegation.Plan;
import com.holdmyspot.cat.common.test.ProcessCompletionContract;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests delegation cohort preparation and lifecycle admission.
 */
public final class DelegationTest
{
	/**
	 * Creates the delegation controller backed by one test-local host fixture.
	 *
	 * @param fixture synthetic host and process observations for this test
	 * @param scope test process services
	 * @param project fixture project whose gates will be admitted
	 * @param environment child process environment
	 * @return delegation controller using the fixture's shared admission ledger
	 * @throws IOException if fixture context cannot be read
	 */
	private static Delegation delegation(DelegationFixture fixture, com.holdmyspot.cat.common.JvmScope scope,
		Path project, Map<String, String> environment) throws IOException
	{
		return new Delegation(scope, project, environment, fixture.admission(scope));
	}

	/**
	 * Creates the test fixture.
	 */
	public DelegationTest()
	{
	}

	/**
	 * Completed scripted processes keep status and external liveness consistent without wait side effects.
	 *
	 * @throws IOException if fixture setup fails
	 * @throws InterruptedException if process observation is interrupted
	 */
	@Test
	public void completedFixtureProcessStateConsistent() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DelegationFixture fixture = new DelegationFixture(cleanup.add(Files.createTempDirectory("delegation-process-")));
			try (Process process = fixture.completed(7, "fixture output"))
			{
				requireThat(process.isAlive(), "completedProcessIsNotAlive").isFalse();
				requireThat(fixture.observesLive(process), "completedProcessNotExternallyLive").isFalse();
				requireThat(process.exitValue(), "completedProcessStatus").isEqualTo(7);
				requireThat(process.waitFor(0, java.util.concurrent.TimeUnit.NANOSECONDS), "completedProcessTimedWait").
					isTrue();
				requireThat(process.waitFor(), "completedProcessWait").isEqualTo(7);
				requireThat(fixture.observesLive(process), "completedWaitPreservesObserver").isFalse();
			}
		}
	}

	/**
	 * A live delegation fixture wait observes explicit completion and does not change liveness.
	 *
	 * @throws IOException if fixture setup or cleanup fails
	 * @throws InterruptedException if the waiter is interrupted
	 */
	@Test
	public void liveFixtureWaitDoesNotCompleteProcess() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DelegationFixture fixture = new DelegationFixture(cleanup.add(Files.createTempDirectory("delegation-process-")));
			ProcessCompletionContract.waitDoesNotCompleteRunningProcess(fixture::processFixture);
		}
	}

	/**
	 * Explicit fixture completion publishes one status to process waits and external liveness.
	 *
	 * @throws IOException if fixture setup or cleanup fails
	 * @throws InterruptedException if completion is interrupted
	 */
	@Test
	public void fixtureCompletionPublishesStatus() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DelegationFixture fixture = new DelegationFixture(cleanup.add(Files.createTempDirectory("delegation-process-")));
			ProcessCompletionContract.explicitCompletionPublishesStatus(fixture::processFixture);
		}
	}

	/**
	 * Verifies a session-local transaction replays source and reaches parent verification.
	 * <p>
	 * Preparation and candidate audit supply the authenticated replay inputs. Integration must verify the combined
	 * disposable project, replay the source, and verify the parent to distinguish those verification inputs and retain
	 * the original source alongside the successful journal and receipt.
	 * Preparation, audit and replay snapshot and journal real files with durable forces, and each store-capability
	 * query reads the kernel mount table. The test keeps that boundary because it asserts the retained journal and
	 * preimage on disk.
	 *
	 * @throws IOException if the nested session fixture cannot be created
	 */
	@Test
	public void sessionStorageSupportsReplay() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path outerTemporary = cleanup.add(Files.createTempDirectory("delegation-session-storage-"));
			DelegationFixture fixture = new DelegationFixture(outerTemporary);
			Path project = cleanup.add(Files.createTempDirectory(outerTemporary, "delegation-project-"));
			Files.createDirectories(project.resolve("client/common/cli"));
			Files.createDirectories(project.resolve("client"));
			Files.writeString(project.resolve("client/mvnw"), "fixture wrapper");
			Files.writeString(project.resolve("client/pom.xml"), "project pom");
			Files.writeString(project.resolve("client/common/cli/pom.xml"), "module pom");
			Files.writeString(project.resolve("client/common/cli/src-test.txt"), "dirty source bytes");
			Path sessionTemporary = Files.createDirectories(project.resolve(".cat/sessions/storage_case/temp"));
			AtomicInteger parentGateCalls = new AtomicInteger();
			AtomicReference<String> bytesAtParentGate = new AtomicReference<>();
			ExecutableFiles executables = fixtureExecutables(builder ->
			{
				if (!builder.command().getFirst().equals("git") &&
					builder.directory().toPath().toRealPath().equals(project.toRealPath()))
				{
					parentGateCalls.incrementAndGet();
					bytesAtParentGate.set(Files.readString(project.resolve(
						"client/common/cli/src-test.txt")));
				}
				return fixture.start(builder);
			});
			try (TestJvmScope scope = new TestJvmScope(sessionTemporary, executables))
			{
				Map<String, String> environment = Map.of("PATH", "/controlled/path");
				Delegation.Prepared prepared = delegation(fixture, scope, project, environment).prepare(plan(scope, project));
				Path candidate = prepared.cohortPath().resolve("work/runtime/project");
				Files.writeString(candidate.resolve("client/common/cli/src-test.txt"), "delegate bytes");
				delegation(fixture, scope, project, environment).audit(prepared.cohortPath(), "runtime");

				Delegation.Integration integration = delegation(fixture, scope, project, environment).
					integrate(prepared.cohortPath(), "runtime");

				requireThat(Files.readString(project.resolve("client/common/cli/src-test.txt")), "replayedBytes").
					isEqualTo("delegate bytes");
				requireThat(Files.readString(project.resolve("client/common/cli/pom.xml")), "unrelatedSource").
					isEqualTo("module pom");
				requireThat(parentGateCalls.get(), "parentVerificationCalls").isEqualTo(1);
				requireThat(bytesAtParentGate.get(), "parentVerificationInput").isEqualTo("delegate bytes");
				Path transaction = findMutationTransaction(prepared.cohortPath());
				requireThat(Files.readString(transaction.resolve("journal.jsonl")), "integratedJournal").
					contains("integrated");
				requireThat(Files.readString(transaction.resolve("preimages/client/common/cli/src-test.txt")),
					"retainedOriginal").isEqualTo("dirty source bytes");
				requireThat(Files.readString(integration.receiptPath()), "parentVerificationPassed").
					contains("parent_verification_exit_code\":0");
			}
		}
	}

	/**
	 * Verifies session-local interrupted replay is reconciled before re-audit.
	 * <p>
	 * The initial preparation and audit authenticate the interrupted integration. Recovery must restore the retained
	 * source and invalidate the work; a second audit and successful integration prove that session-local storage also
	 * supports resumption after recovery.
	 *
	 * @throws IOException if the nested recovery fixture cannot be created
	 */
	@Test
	public void sessionStorageSupportsRecovery() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path outerTemporary = cleanup.add(Files.createTempDirectory("delegation-session-recovery-"));
			DelegationFixture fixture = new DelegationFixture(outerTemporary);
			Path project = cleanup.add(Files.createTempDirectory(outerTemporary, "delegation-project-"));
			Files.createDirectories(project.resolve("client/common/cli"));
			Files.createDirectories(project.resolve("client"));
			Files.writeString(project.resolve("client/mvnw"), "fixture wrapper");
			Files.writeString(project.resolve("client/pom.xml"), "project pom");
			Files.writeString(project.resolve("client/common/cli/pom.xml"), "module pom");
			Files.writeString(project.resolve("client/common/cli/src-test.txt"), "dirty source bytes");
			Path sessionTemporary = Files.createDirectories(project.resolve(".cat/sessions/storage_case/temp"));
			AtomicInteger parentGateCalls = new AtomicInteger();
			AtomicReference<String> bytesAtParentGate = new AtomicReference<>();
			ExecutableFiles executables = fixtureExecutables(builder ->
			{
				if (!builder.command().getFirst().equals("git") &&
					builder.directory().toPath().toRealPath().equals(project.toRealPath()))
				{
					parentGateCalls.incrementAndGet();
					bytesAtParentGate.set(Files.readString(project.resolve(
						"client/common/cli/src-test.txt")));
				}
				return fixture.start(builder);
			});
			try (TestJvmScope scope = new TestJvmScope(sessionTemporary, executables))
			{
				Map<String, String> environment = Map.of("PATH", "/controlled/path");
				Delegation.Prepared prepared = delegation(fixture, scope, project, environment).prepare(plan(scope, project));
				Path candidate = prepared.cohortPath().resolve("work/runtime/project");
				Files.writeString(candidate.resolve("client/common/cli/src-test.txt"), "delegate bytes");
				delegation(fixture, scope, project, environment).audit(prepared.cohortPath(), "runtime");
				Path recovery = Files.createDirectories(prepared.cohortPath().resolve("transactions/recovery"));
				Path retained = recovery.resolve("preimages/client/common/cli/src-test.txt");
				Files.createDirectories(retained.getParent());
				FileTime directoryTime = Files.getLastModifiedTime(project);
				Files.move(project.resolve("client/common/cli/src-test.txt"), retained);
				Files.writeString(recovery.resolve("journal.jsonl"),
					"{\"format_version\":1,\"state\":\"prepared\",\"directory_times\":{\".\":\"" +
						directoryTime + "\"},\"absent_directories\":[]}\n" +
					"{\"path\":\"client/common/cli/src-test.txt\",\"retained\":\"src-test.txt\"," +
					"\"state\":\"retain_intent\",\"preimage\":\"before\",\"postimage\":\"after\"}\n");
				ObjectNode owner = (ObjectNode) scope.json().readTree(
					prepared.cohortPath().resolve("owner.json").toFile());
				ObjectNode workOwner = (ObjectNode) owner.get("work").get("runtime");
				workOwner.put("state", "integrating");
				workOwner.put("transaction_path", "transactions/recovery");
				owner.put("generation", owner.get("generation").longValue() + 1);
				Files.writeString(prepared.cohortPath().resolve("owner.json"), scope.json().writeValueAsString(owner));
				IOException recoveryResult = null;
				try
				{
					delegation(fixture, scope, project, environment).integrate(prepared.cohortPath(), "runtime");
				}
				catch (IOException e)
				{
					recoveryResult = e;
				}
				requireThat(recoveryResult, "recoveryRequiresAudit").isNotNull();
				requireThat(recoveryResult.getMessage(), "rollbackRequiresReaudit").
					contains("Interrupted replay was rolled back; re-audit required");
				requireThat(Files.readString(project.resolve("client/common/cli/src-test.txt")), "recoveredPreimage").
					isEqualTo("dirty source bytes");
				JsonNode recoveredOwner = scope.json().readTree(prepared.cohortPath().resolve("owner.json").toFile());
				requireThat(recoveredOwner.get("work").get("runtime").get("state").stringValue(),
					"recoveredWorkState").isEqualTo("invalidated");
				requireThat(Files.readString(prepared.cohortPath().resolve("receipts/recovery-runtime.json")),
					"recoveryReceipt").contains("\"state\":\"invalidated\"");
				requireThat(Files.readString(recovery.resolve("journal.jsonl")), "rollbackJournal").
					contains("rolled_back");
				delegation(fixture, scope, project, environment).audit(prepared.cohortPath(), "runtime");
				Delegation.Integration integration = delegation(fixture, scope, project, environment).
					integrate(prepared.cohortPath(), "runtime");

				requireThat(Files.readString(project.resolve("client/common/cli/src-test.txt")), "replayedBytes").
					isEqualTo("delegate bytes");
				requireThat(parentGateCalls.get(), "parentVerificationCalls").isEqualTo(1);
				requireThat(bytesAtParentGate.get(), "parentVerificationInput").isEqualTo("delegate bytes");
				requireThat(Files.readString(integration.receiptPath()), "parentVerificationPassed").
					contains("parent_verification_exit_code\":0");
			}
		}
	}

	/**
	 * Verifies another work item's output root cannot authorize transaction storage.
	 * <p>
	 * Two work items distinguish the selected work's outputs from the complete output set. Preparation and audit must
	 * succeed before integration can reject storage beneath the other work's output without changing the parent.
	 *
	 * @throws IOException if the nested output fixture cannot be created
	 */
	@Test
	public void sessionStorageRejectsOtherOutput() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path outerTemporary = cleanup.add(Files.createTempDirectory("delegation-other-output-"));
			DelegationFixture fixture = new DelegationFixture(outerTemporary);
			Path project = cleanup.add(Files.createTempDirectory(outerTemporary, "delegation-project-"));
			Files.createDirectories(project.resolve("client/common/cli"));
			Files.createDirectories(project.resolve("client"));
			Files.writeString(project.resolve("client/mvnw"), "fixture wrapper");
			Files.writeString(project.resolve("client/pom.xml"), "project pom");
			Files.writeString(project.resolve("client/common/cli/pom.xml"), "module pom");
			Files.writeString(project.resolve("client/common/cli/src-test.txt"), "dirty source bytes");
			Files.writeString(project.resolve("client/common/cli/second-test.txt"), "second source bytes");
			Path sessionTemporary = Files.createDirectories(project.resolve(".cat/target/session/temp"));
			AtomicInteger parentGateCalls = new AtomicInteger();
			ExecutableFiles executables = fixtureExecutables(builder ->
			{
				List<String> command = builder.command();
				Path directory = builder.directory().toPath();
				if (command.getFirst().equals("git"))
					return fixture.start(builder);
				if (directory.toRealPath().equals(project.toRealPath()))
					parentGateCalls.incrementAndGet();
				DelegationFixture.createMavenLog(builder);
				Path outputRoot = directory.resolve("client/common/cli/target");
				if (command.stream().anyMatch(argument -> argument.contains("OtherGateTest")))
					outputRoot = directory.resolve(".cat/target");
				Files.createDirectories(outputRoot);
				Files.writeString(outputRoot.resolve("probe.txt"), "gate output");
				return fixture.running(0, "");
			});
			try (TestJvmScope scope = new TestJvmScope(sessionTemporary, executables))
			{
				Map<String, String> environment = Map.of("PATH", "/controlled/path");
				Plan plan = planWithOtherOutput(scope, project);
				Delegation.Prepared prepared = delegation(fixture, scope, project, environment).prepare(plan);
				Path candidate = prepared.cohortPath().resolve("work/runtime/project");
				Files.writeString(candidate.resolve("client/common/cli/src-test.txt"), "delegate bytes");
				delegation(fixture, scope, project, environment).audit(prepared.cohortPath(), "runtime");
				IOException rejected = null;
				try
				{
					delegation(fixture, scope, project, environment).integrate(prepared.cohortPath(), "runtime");
				}
				catch (IOException e)
				{
					rejected = e;
				}
				requireThat(rejected, "otherWorkOutputRejectsStorage").isNotNull();
				requireThat(rejected.getMessage(), "storageOutputConflict").contains("Unsafe transaction storage");
				requireThat(Files.readString(project.resolve("client/common/cli/src-test.txt")), "parentUnchanged").
					isEqualTo("dirty source bytes");
				requireThat(parentGateCalls.get(), "parentGateNotInvoked").isEqualTo(0);
			}
		}
	}

	/**
	 * Verifies dirty selected source reaches the delegate and a scope receipt is retained.
	 * <p>
	 * One preparation is reused across relocation, unreviewed-file and empty-directory refusals, candidate invalidation,
	 * interrupted replay, and successful recovery. Each later audit observes a changed candidate or ownership state;
	 * retaining those transitions proves that relocated dirty bytes and metadata remain protected through integration.
	 * Several preparations, relocations and audits run against the real filesystem; each store-capability query reads
	 * the kernel mount table, which makes the case last seconds. The test keeps that boundary because it asserts the
	 * recorded metadata capabilities and the relocated frozen bytes.
	 *
	 * @throws IOException if the isolated fixture cannot be created
	 */
	@Test
	public void dirtySourceIsFrozen() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporaryDirectory = cleanup.add(Files.createTempDirectory("delegation-scope-"));
			DelegationFixture fixture = new DelegationFixture(temporaryDirectory);
			ExecutableFiles executableFiles = ExecutableFilesSecrets.getExecutableFilesAccess().create(Thread::yield,
				Clock.systemUTC(), fixture::start);
			try (TestJvmScope scope = new TestJvmScope(temporaryDirectory, executableFiles))
			{
			Path project = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "delegation-project-"));
			Files.createDirectories(project.resolve("client/common/cli"));
			Files.createDirectories(project.resolve("client"));
			Files.writeString(project.resolve("client/mvnw"), "fixture wrapper");
			Files.writeString(project.resolve("client/pom.xml"), "project pom");
			Files.writeString(project.resolve("client/common/cli/pom.xml"), "module pom");
			Files.writeString(project.resolve("client/common/cli/src-test.txt"), "dirty source bytes");
			Map<String, String> environment = Map.of("PATH", "/controlled/path");
			Plan plan = plan(scope, project);

			Delegation.Prepared prepared = delegation(fixture, scope, project, environment).prepare(plan);
			Path relocation = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "delegation-move-"));
			Path movedProject = relocation.resolve("project");
			Path movedCohort = relocation.resolve("cohort");
			Files.move(project, movedProject);
			Files.move(prepared.cohortPath(), movedCohort);
			project = movedProject;
			cleanup.add(movedProject);
			cleanup.add(movedCohort);
			Path delegatedProject = movedCohort.resolve("work/runtime/project");
			requireThat(Files.readString(delegatedProject.resolve("client/common/cli/src-test.txt")), "frozenBytes").
				isEqualTo("dirty source bytes");
			Path prepareReceipt = movedCohort.resolve("receipts/prepare-runtime.json");
			requireThat(Files.exists(prepareReceipt), "scopeReceipt").isTrue();
			String receiptJson = Files.readString(prepareReceipt);
			requireThat(receiptJson, "metadataCapabilities").contains("metadata_capabilities");
			requireThat(receiptJson, "perPathMetadataIdentity").contains("metadata_id");
			requireThat(receiptJson, "supportedViews").contains("supported_views");
			requireThat(Files.exists(delegatedProject.resolve("client/common/cli/target")), "generatedOutput").isFalse();
			requireThat(Files.exists(project.resolve("client/common/cli/target")), "parentWasNotBuilt").isFalse();
				Path unreviewed = delegatedProject.resolve("unreviewed.txt");
			Files.writeString(unreviewed, "outside allowlist");
			IOException admission = null;
			try
			{
				delegation(fixture, scope, project, environment).audit(movedCohort, "runtime");
			}
			catch (IOException e)
			{
				admission = e;
			}
			requireThat(admission, "extraPathRejected").isNotNull();
			requireThat(Files.exists(movedCohort.resolve("work/runtime/audit.json")), "noAuditPublished").
				isFalse();
			Files.delete(unreviewed);
			Path emptyDirectory = delegatedProject.resolve("empty-unreviewed");
			Files.createDirectory(emptyDirectory);
			IOException emptyDirectoryAdmission = null;
			try
			{
				delegation(fixture, scope, project, environment).audit(movedCohort, "runtime");
			}
			catch (IOException e)
			{
				emptyDirectoryAdmission = e;
			}
			requireThat(emptyDirectoryAdmission, "emptyDirectoryRejected").isNotNull();
			Files.delete(emptyDirectory);
			Files.writeString(project.resolve("unrelated.env"), "preserve parent bytes");
			Files.writeString(delegatedProject.resolve("client/common/cli/src-test.txt"), "implemented bytes");

			Delegation.Audit audit = delegation(fixture, scope, project, environment).audit(movedCohort, "runtime");

			requireThat(Files.exists(audit.receiptPath()), "auditReceipt").isTrue();
			requireThat(Files.readString(movedCohort.resolve("work/runtime/audit.json")), "auditPaths").
				contains("client/common/cli/src-test.txt");
			Files.writeString(delegatedProject.resolve("client/common/cli/src-test.txt"), "changed after audit");
			IOException changedCandidate = null;
			try
			{
				delegation(fixture, scope, project, environment).integrate(movedCohort, "runtime");
			}
			catch (IOException e)
			{
				changedCandidate = e;
			}
			requireThat(changedCandidate, "changedCandidateRejected").isNotNull();
			requireThat(Files.readString(project.resolve("client/common/cli/src-test.txt")), "parentUnchanged").
				isEqualTo("dirty source bytes");
			Files.writeString(delegatedProject.resolve("client/common/cli/src-test.txt"), "implemented bytes");
			delegation(fixture, scope, project, environment).audit(movedCohort, "runtime");
			Path recovery = Files.createDirectories(movedCohort.resolve("transactions/recovery"));
			Path retained = recovery.resolve("preimages/client/common/cli/src-test.txt");
			Files.createDirectories(retained.getParent());
			Files.move(project.resolve("client/common/cli/src-test.txt"), retained);
			Files.writeString(recovery.resolve("journal.jsonl"),
				"{\"format_version\":1,\"state\":\"prepared\",\"directory_times\":{}," +
				"\"absent_directories\":[]}\n" +
				"{\"path\":\"client/common/cli/src-test.txt\",\"state\":\"preimage_retained\"," +
				"\"preimage\":\"before\",\"postimage\":\"after\"}\n");
			ObjectNode owner = (ObjectNode) scope.json().readTree(movedCohort.resolve("owner.json").toFile());
			ObjectNode workOwner = (ObjectNode) owner.get("work").get("runtime");
			workOwner.put("state", "integrating");
			workOwner.put("transaction_path", "transactions/recovery");
			owner.put("generation", owner.get("generation").longValue() + 1);
			Files.writeString(movedCohort.resolve("owner.json"), scope.json().writeValueAsString(owner));
			IOException recoveryResult = null;
			try
			{
				delegation(fixture, scope, project, environment).integrate(movedCohort, "runtime");
			}
			catch (IOException e)
			{
				recoveryResult = e;
			}
			requireThat(recoveryResult, "interruptedReplayRecovered").isNotNull();
			requireThat(Files.readString(project.resolve("client/common/cli/src-test.txt")), "parentRestored").
				isEqualTo("dirty source bytes");
			delegation(fixture, scope, project, environment).audit(movedCohort, "runtime");

			Delegation.Integration integration = delegation(fixture, scope, project, environment).
				integrate(movedCohort, "runtime");

			requireThat(Files.readString(project.resolve("client/common/cli/src-test.txt")), "integratedBytes").
				isEqualTo("implemented bytes");
			requireThat(Files.readString(project.resolve("unrelated.env")), "unrelatedParentBytes").
				isEqualTo("preserve parent bytes");
			requireThat(Files.exists(integration.receiptPath()), "integrationReceipt").isTrue();
			}
		}
	}

	/**
	 * Verifies preparation preserves selected link metadata accepted by audit.
	 * <p>
	 * A real link with distinct target metadata is needed to detect accidental target inspection. Preparation and one
	 * successful audit establish preservation; changing only the link timestamp and auditing again establishes metadata
	 * invalidation without changing the target's bytes or timestamp.
	 *
	 * @throws IOException if the isolated fixture cannot be created
	 */
	@Test
	public void untouchedLeafLinkPassesAudit() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporaryDirectory = cleanup.add(Files.createTempDirectory("delegation-link-audit-"));
			DelegationFixture fixture = new DelegationFixture(temporaryDirectory);
			ExecutableFiles executables = fixtureExecutables(fixture::start);
			try (TestJvmScope scope = new TestJvmScope(temporaryDirectory, executables))
			{
				Path project = fixtureProject(cleanup, scope);
				Path target = project.resolve("client/common/cli/src-test.txt");
				FileTime targetTime = FileTime.fromMillis(1_600_000_000_000L);
				Files.setLastModifiedTime(target, targetTime);
				Path link = project.resolve("client/common/cli/source-link.txt");
				Files.createSymbolicLink(link, Path.of("src-test.txt"));
				FileTime linkTime = FileTime.fromMillis(1_500_000_000_000L);
				BasicFileAttributeView linkAttributes = Files.getFileAttributeView(link,
					BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
				linkAttributes.setTimes(linkTime, null, null);

				Path request = project.resolve("delegation-plan.json");
				plan(scope, project);
				String requestText = Files.readString(request);
				int sourcePathsEnd = requestText.indexOf(']', requestText.indexOf("\"source_paths\""));
				requestText = requestText.substring(0, sourcePathsEnd) +
					",\"client/common/cli/source-link.txt\"" + requestText.substring(sourcePathsEnd);
				Files.writeString(request, requestText);
				Plan reviewedPlan = Plan.read(scope.json(), request);
				Map<String, String> environment = Map.of("PATH", "/controlled/path");
				Delegation.Prepared prepared = delegation(fixture, scope, project, environment).prepare(reviewedPlan);
				delegation(fixture, scope, project, environment).audit(prepared.cohortPath(), "runtime");
				requireThat(Files.readString(target), "parentTargetBytes").isEqualTo("dirty source bytes");
				requireThat(Files.getLastModifiedTime(target), "parentTargetTime").isEqualTo(targetTime);

				Path candidateLink = prepared.cohortPath().resolve("work/runtime/project/client/common/cli/source-link.txt");
				Files.getFileAttributeView(candidateLink, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).
					setTimes(FileTime.fromMillis(1_400_000_000_000L), null, null);
				IOException rejection = null;
				try
				{
					delegation(fixture, scope, project, environment).audit(prepared.cohortPath(), "runtime");
				}
				catch (IOException e)
				{
					rejection = e;
				}
				requireThat(rejection, "changedLinkTimeRejected").isNotNull();
				requireThat(rejection.getMessage(), "changedLinkPathReported").contains("client/common/cli/source-link.txt");
			}
		}
	}

	/**
	 * Verifies a failed probe records failure and removes disposable project copies.
	 *
	 * @throws IOException if the fixture cannot be created or evidence is malformed
	 */
	@Test
	public void probeFailurePreventsPreparation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporaryDirectory = cleanup.add(Files.createTempDirectory("delegation-failure-"));
			DelegationFixture fixture = new DelegationFixture(temporaryDirectory);
			ExecutableFiles executableFiles = ExecutableFilesSecrets.getExecutableFilesAccess().create(Thread::yield,
				Clock.systemUTC(), fixture::failMaven);
			try (TestJvmScope scope = new TestJvmScope(temporaryDirectory, executableFiles))
			{
				Path project = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "delegation-project-"));
				Files.createDirectories(project.resolve("client/common/cli"));
				Files.createDirectories(project.resolve("client"));
				Files.writeString(project.resolve("client/mvnw"), "fixture wrapper");
				Files.writeString(project.resolve("client/pom.xml"), "project pom");
				Files.writeString(project.resolve("client/common/cli/pom.xml"), "module pom");
				Files.writeString(project.resolve("client/common/cli/src-test.txt"), "dirty source bytes");
				IOException failure = null;
				try
				{
					delegation(fixture, scope, project, Map.of("PATH", "/controlled/path")).prepare(plan(scope, project));
				}
				catch (IOException e)
				{
					failure = e;
				}
				requireThat(failure, "probeStartFailure").isNotNull();
				Path cohort = null;
				try (DirectoryStream<Path> children = Files.newDirectoryStream(scope.temporaryDirectory(),
					"delegation-*"))
				{
					for (Path child : children)
						if (Files.isRegularFile(child.resolve("owner.json")))
						{
							cohort = child;
							break;
						}
				}
				requireThat(cohort, "failedCohortFound").isNotNull();
				requireThat(Files.readString(cohort.resolve("owner.json")), "failedState").contains("failed");
				requireThat(Files.exists(cohort.resolve("receipts/prepare-failure.json")), "failureReceipt").isTrue();
				requireThat(Files.exists(cohort.resolve("verification/runtime/project")), "projectCopyRemoved").isFalse();
				requireThat(Files.exists(cohort.resolve("verification/runtime/roots/home")), "homeRemoved").isFalse();
				requireThat(Files.exists(cohort.resolve("verification/runtime/roots/repository")), "repositoryRemoved").
					isFalse();
				requireThat(Files.exists(cohort.resolve("verification/runtime/roots/temporary")), "temporaryRemoved").
					isFalse();
				requireThat(Files.readString(cohort.resolve("receipts/prepare-failure.json")), "emptyDiagnostics").
					contains("diagnostic_paths\":[]");
				requireThat(Files.exists(cohort.resolve("work")), "delegateCopiesRemoved").isFalse();
			}
		}
	}

	/**
	 * Verifies a terminal wrapper download failure cannot be recorded as prepared scope.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void wrapperFailurePreventsPreparation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporaryDirectory = cleanup.add(Files.createTempDirectory("delegation-wrapper-failure-"));
			DelegationFixture fixture = new DelegationFixture(temporaryDirectory);
			ExecutableFiles executables = fixtureExecutables(builder ->
			{
				if (builder.command().getFirst().equals("git"))
					return fixture.start(builder);
				return fixture.running(1, "wget: Failed to fetch distribution\n");
			});
			try (TestJvmScope scope = new TestJvmScope(temporaryDirectory, executables))
			{
				Path project = fixtureProject(cleanup, scope);
				IOException failure = null;
				try
				{
					delegation(fixture, scope, project, Map.of("PATH", "/controlled/path")).prepare(plan(scope, project));
				}
				catch (IOException e)
				{
					failure = e;
				}
				requireThat(failure, "wrapperFailureRejected").isNotNull();
				Path cohort = findCohort(scope.temporaryDirectory());
				requireThat(Files.readString(cohort.resolve("owner.json")), "failedOwner").contains("failed");
				requireThat(Files.readString(cohort.resolve("receipts/prepare-failure.json")), "failedScope").
					contains("failed");
				try (var paths = Files.walk(cohort.resolve("verification")))
				{
					Path wrapperLog = paths.filter(path -> path.getFileName().toString().equals("wrapper.log")).findFirst().
						orElseThrow();
					requireThat(Files.readString(wrapperLog), "retainedDownloaderDiagnostic").
						contains("wget: Failed to fetch distribution");
				}
			}
		}
	}

	/**
	 * Verifies a Maven-started probe is rejected if it leaves a reviewed output root untouched.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void unexercisedOutputsPreventPreparation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporaryDirectory = cleanup.add(Files.createTempDirectory("delegation-unexercised-"));
			DelegationFixture fixture = new DelegationFixture(temporaryDirectory);
			ExecutableFiles executables = fixtureExecutables(builder ->
			{
				if (builder.command().getFirst().equals("git"))
					return fixture.start(builder);
				DelegationFixture.createMavenLog(builder);
				return fixture.running(0, "");
			});
			try (TestJvmScope scope = new TestJvmScope(temporaryDirectory, executables))
			{
				Path project = fixtureProject(cleanup, scope);
				IOException failure = null;
				try
				{
					delegation(fixture, scope, project, Map.of("PATH", "/controlled/path")).prepare(plan(scope, project));
				}
				catch (IOException e)
				{
					failure = e;
				}
				requireThat(failure, "unexercisedOutputRejected").isNotNull();
				requireThat(failure.getMessage(), "mentionsOutputCoverage").contains("output roots");
				Path cohort = findCohort(scope.temporaryDirectory());
				requireThat(Files.readString(cohort.resolve("owner.json")), "failedOwner").contains("failed");
			}
		}
	}

	/**
	 * Verifies nonzero Maven preparation records observed scope while rejecting a changed candidate.
	 * <p>
	 * Preparation and audit snapshot real files, and each store-capability query reads the kernel mount table. The
	 * test keeps that boundary because it asserts the receipt written to disk.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void nonzeroMavenStillPrepares() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporaryDirectory = cleanup.add(Files.createTempDirectory("delegation-nonzero-"));
			DelegationFixture fixture = new DelegationFixture(temporaryDirectory);
			ExecutableFiles executables = fixtureExecutables(builder -> fixture.start(builder, false, true));
			try (TestJvmScope scope = new TestJvmScope(temporaryDirectory, executables))
			{
				Path project = fixtureProject(cleanup, scope);
				Map<String, String> environment = Map.of("PATH", "/controlled/path");
				Delegation.Prepared prepared = delegation(fixture, scope, project, environment).prepare(plan(scope, project));
				String receipt = Files.readString(prepared.cohortPath().resolve("receipts/prepare-runtime.json"));
				requireThat(receipt, "scopePassed").contains("scope_result\":\"passed");
				requireThat(receipt, "nonzeroExitRecorded").contains("exit_code\":1");
				Path candidate = prepared.cohortPath().resolve("work/runtime/project");
				Files.writeString(candidate.resolve("client/common/cli/src-test.txt"), "candidate source");
				IOException auditFailure = null;
				try
				{
					delegation(fixture, scope, project, environment).audit(prepared.cohortPath(), "runtime");
				}
				catch (IOException e)
				{
					auditFailure = e;
				}
				requireThat(auditFailure, "candidateBehaviorRejected").isNotNull();
				requireThat(Files.exists(prepared.cohortPath().resolve("work/runtime/audit.json")),
					"nonzeroAuditAbsent").isFalse();
			}
		}
	}

	/**
	 * Verifies a checksum-valid receipt from the previous execution profile rejects before a new gate starts.
	 * <p>
	 * A successful preparation supplies the otherwise valid receipt. Replacing only its execution profile and updating
	 * the receipt checksums isolates profile rejection; the refused audit must not run another verification gate.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void previousExecutionProfileRejectsAudit() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporaryDirectory = cleanup.add(Files.createTempDirectory("delegation-old-profile-"));
			DelegationFixture fixture = new DelegationFixture(temporaryDirectory);
			AtomicInteger mavenCalls = new AtomicInteger();
			ExecutableFiles executables = fixtureExecutables(builder ->
			{
				if (!builder.command().getFirst().equals("git"))
					mavenCalls.incrementAndGet();
				return fixture.start(builder);
			});
			try (TestJvmScope scope = new TestJvmScope(temporaryDirectory, executables))
			{
				Path project = fixtureProject(cleanup, scope);
				Map<String, String> environment = Map.of("PATH", "/controlled/path");
				Delegation.Prepared prepared = delegation(fixture, scope, project, environment).prepare(plan(scope, project));
				Path receiptPath = prepared.cohortPath().resolve("receipts/prepare-runtime.json");
				ObjectNode receipt = (ObjectNode) scope.json().readTree(receiptPath.toFile());
				Plan.Work work = plan(scope, project).work().getFirst();
				receipt.put("gate_spec_id", sha256(work.verification().toString()));
				receipt.remove("receipt_id");
				receipt.put("receipt_id", sha256(scope.json().writeValueAsString(receipt)));
				Files.writeString(receiptPath, scope.json().writeValueAsString(receipt));
				ObjectNode owner = (ObjectNode) scope.json().readTree(
					prepared.cohortPath().resolve("owner.json").toFile());
				((ObjectNode) owner.get("work").get("runtime")).put("preparation_receipt_id",
					receipt.get("receipt_id").stringValue());
				Files.writeString(prepared.cohortPath().resolve("owner.json"), scope.json().writeValueAsString(owner));
				int priorCalls = mavenCalls.get();
				IOException rejected = null;
				try
				{
					delegation(fixture, scope, project, environment).audit(prepared.cohortPath(), "runtime");
				}
				catch (IOException e)
				{
					rejected = e;
				}
				requireThat(rejected, "oldProfileRejected").isNotNull();
				requireThat(mavenCalls.get(), "rejectedBeforeAnotherGate").isEqualTo(priorCalls);
			}
		}
	}

	/**
	 * Verifies missing, tampered, mismatched, and nonterminal preparation evidence rejects audit.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void preparationReceiptIdentityVariantsReject() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporaryDirectory = cleanup.add(Files.createTempDirectory("delegation-receipt-variants-"));
			DelegationFixture fixture = new DelegationFixture(temporaryDirectory);
			ExecutableFiles executables = fixtureExecutables(fixture::start);
			try (TestJvmScope scope = new TestJvmScope(temporaryDirectory, executables))
			{
				Path project = fixtureProject(cleanup, scope);
				Map<String, String> environment = Map.of("PATH", "/controlled/path");
				Delegation.Prepared prepared = delegation(fixture, scope, project, environment).prepare(plan(scope, project));
				Path receipt = prepared.cohortPath().resolve("receipts/prepare-runtime.json");
				String originalReceipt = Files.readString(receipt);
				Path ownerPath = prepared.cohortPath().resolve("owner.json");
				String originalOwner = Files.readString(ownerPath);
				for (Map.Entry<String, String> variant : Map.of("assignment_id", "other_assignment",
					"scope_result", "failed").entrySet())
				{
					try
					{
						ObjectNode changedReceipt = (ObjectNode) scope.json().readTree(originalReceipt);
						changedReceipt.put(variant.getKey(), variant.getValue());
						changedReceipt.remove("receipt_id");
						String digest = sha256(scope.json().writeValueAsString(changedReceipt));
						changedReceipt.put("receipt_id", digest);
						ObjectNode changedOwner = (ObjectNode) scope.json().readTree(originalOwner);
						((ObjectNode) changedOwner.get("work").get("runtime")).put("preparation_receipt_id", digest);
						Files.writeString(receipt, scope.json().writeValueAsString(changedReceipt));
						Files.writeString(ownerPath, scope.json().writeValueAsString(changedOwner));
						IOException rejected = null;
						try
						{
							delegation(fixture, scope, project, environment).audit(prepared.cohortPath(), "runtime");
						}
						catch (IOException e)
						{
							rejected = e;
						}
						requireThat(rejected, variant.getKey() + "Rejected").isNotNull();
						requireThat(rejected.getMessage(), variant.getKey() + "RefusalMessage").
							contains("Preparation receipt is invalid");
						requireThat(Files.exists(prepared.cohortPath().resolve("work/runtime/audit.json")),
							variant.getKey() + "NoAudit").isFalse();
					}
					finally
					{
						Files.writeString(receipt, originalReceipt);
						Files.writeString(ownerPath, originalOwner);
					}
				}
				ObjectNode tamperedReceipt = (ObjectNode) scope.json().readTree(originalReceipt);
				tamperedReceipt.put("input_manifest_id", "tampered_input_manifest");
				Files.writeString(receipt, scope.json().writeValueAsString(tamperedReceipt));
				IOException tampered = null;
				try
				{
					delegation(fixture, scope, project, environment).audit(prepared.cohortPath(), "runtime");
				}
				catch (IOException e)
				{
					tampered = e;
				}
				requireThat(tampered, "checksumTamperingRejected").isNotNull();
				requireThat(tampered.getMessage(), "checksumTamperingMessage").contains("Receipt digest is invalid");
				requireThat(Files.exists(prepared.cohortPath().resolve("work/runtime/audit.json")),
					"checksumTamperingNoAudit").isFalse();
				Files.writeString(receipt, originalReceipt);
				Files.delete(receipt);
				IOException missing = null;
				try
				{
					delegation(fixture, scope, project, environment).audit(prepared.cohortPath(), "runtime");
				}
				catch (IOException e)
				{
					missing = e;
				}
				requireThat(missing, "missingReceiptRejected").isNotNull();
				requireThat(Files.readString(project.resolve("client/common/cli/src-test.txt")), "parentUnchanged").
					isEqualTo("dirty source bytes");
			}
		}
	}

	/**
	 * Verifies a changed allowed definition gets a fresh candidate gate invocation.
	 * <p>
	 * Preparation supplies the baseline receipt before the allowed definition changes. The subsequent candidate audit
	 * must run its own verification and publish a candidate receipt, so both preparation and audit are retained.
	 * Preparation and audit snapshot real files, and each store-capability query reads the kernel mount table. The
	 * test keeps that boundary because it asserts the candidate bytes the probe observed and the audit receipt.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void changedDefinitionGetsCandidateProbe() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporaryDirectory = cleanup.add(Files.createTempDirectory("delegation-definition-probe-"));
			DelegationFixture fixture = new DelegationFixture(temporaryDirectory);
			AtomicReference<String> observedModulePom = new AtomicReference<>();
			AtomicReference<String> observedSource = new AtomicReference<>();
			ExecutableFiles executables = fixtureExecutables(builder ->
			{
				if (!builder.command().getFirst().equals("git"))
				{
					Path launchedProject = builder.directory().toPath();
					observedModulePom.set(Files.readString(launchedProject.resolve("client/common/cli/pom.xml")));
					observedSource.set(Files.readString(launchedProject.resolve("client/common/cli/src-test.txt")));
				}
				return fixture.start(builder);
			});
			try (TestJvmScope scope = new TestJvmScope(temporaryDirectory, executables))
			{
				Path project = fixtureProject(cleanup, scope);
				Map<String, String> environment = Map.of("PATH", "/controlled/path");
				Delegation.Prepared prepared = delegation(fixture, scope, project, environment).
					prepare(plan(scope, project, true));
				Path candidate = prepared.cohortPath().resolve("work/runtime/project");
				Files.writeString(candidate.resolve("client/common/cli/pom.xml"), "reviewed changed module POM");
				Files.writeString(candidate.resolve("client/common/cli/src-test.txt"), "candidate source");
				delegation(fixture, scope, project, environment).audit(prepared.cohortPath(), "runtime");
				requireThat(observedModulePom.get(), "candidateModulePomObserved").
					isEqualTo("reviewed changed module POM");
				requireThat(observedSource.get(), "candidateSourceObserved").isEqualTo("candidate source");
				String audit = Files.readString(prepared.cohortPath().resolve("work/runtime/audit.json"));
				requireThat(audit, "candidateProbeReceipt").contains("candidate_probe");
			}
		}
	}

	/**
	 * Verifies undeclared output blocks preparation.
	 * <p>
	 * One minimal project is prepared with a probe that writes outside its reviewed outputs. The preparation boundary
	 * must observe that output and refuse the receipt; no successful audit or integration is needed.
	 *
	 * @throws IOException if a fixture cannot be created
	 */
	@Test
	public void undeclaredOutputPreventsPreparation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporaryDirectory = cleanup.add(Files.createTempDirectory("delegation-undeclared-output-"));
			DelegationFixture fixture = new DelegationFixture(temporaryDirectory);
			try (TestJvmScope scope = new TestJvmScope(temporaryDirectory,
				fixtureExecutables(builder -> fixture.start(builder, true, false))))
			{
				Path project = fixtureProject(cleanup, scope);
				IOException rejected = null;
				try
				{
					delegation(fixture, scope, project, Map.of("PATH", "/controlled/path")).prepare(plan(scope, project));
				}
				catch (IOException e)
				{
					rejected = e;
				}
				requireThat(rejected, "undeclaredOutputRejected").isNotNull();
			}
		}
	}

	/**
	 * Computes a SHA-256 digest for a receipt fixture.
	 *
	 * @param value text to hash
	 * @return lowercase hexadecimal digest
	 * @throws IOException if SHA-256 is unavailable
	 */
	private static String sha256(String value) throws IOException
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
				value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IOException("SHA-256 is unavailable", e);
		}
	}

	/**
	 * Writes and reads a focused reviewed-plan fixture.
	 *
	 * @param scope test scope providing JSON
	 * @param project fixture project root
	 * @return typed plan
	 * @throws IOException if the request cannot be written or parsed
	 */
	private static Plan plan(TestJvmScope scope, Path project) throws IOException
	{
		return plan(scope, project, false);
	}

	/**
	 * Writes a plan optionally permitting an edited reviewed gate definition.
	 *
	 * @param scope test scope providing JSON
	 * @param project fixture project root
	 * @param allowDefinitionEdit whether the module POM is writable
	 * @return typed plan
	 * @throws IOException if the request cannot be written or parsed
	 */
	private static Plan plan(TestJvmScope scope, Path project, boolean allowDefinitionEdit) throws IOException
	{
		Path request = project.resolve("delegation-plan.json");
		String requestText = """
			{"format_version":1,"project_path":".","source_paths":["client/mvnw","client/pom.xml",
			"client/common/cli/pom.xml","client/common/cli/src-test.txt"],
			"excluded_roots":[".git",".cat/sessions",".cat-tmp","client/common/cli/target"],
			"work":[{"work_id":"runtime","assignment_id":"assignment-1",
			"write_paths":["client/common/cli/src-test.txt"],
			"verification":{"gate_kind":"project_maven","modules":["common/cli"],
			"tests":["DelegationTest"],"scope_authority":{"assignment_id":"assignment-1",
			"review_id":"review-1","reviewer":"parent","definition_paths":["client/mvnw",
			"client/pom.xml","client/common/cli/pom.xml"],"project_output_roots":["client/common/cli/target"],
			"permitted_owned_roots":["home","repository","temporary","diagnostics"]}}}],
			"cleanup_owner":"parent"}
			""";
		if (allowDefinitionEdit)
			requestText = requestText.replace("\"write_paths\":[\"client/common/cli/src-test.txt\"]",
				"\"write_paths\":[\"client/common/cli/src-test.txt\",\"client/common/cli/pom.xml\"]");
		Files.writeString(request, requestText);
		return Plan.read(scope.json(), request);
	}

	/**
	 * Writes a plan whose second work item declares a distinct output root.
	 *
	 * @param scope test scope providing JSON
	 * @param project fixture project root
	 * @return typed plan
	 * @throws IOException if the request cannot be written or parsed
	 */
	private static Plan planWithOtherOutput(TestJvmScope scope, Path project) throws IOException
	{
		Path request = project.resolve("delegation-plan.json");
		Files.writeString(request, """
			{"format_version":1,"project_path":".","source_paths":["client/mvnw","client/pom.xml",
			"client/common/cli/pom.xml","client/common/cli/src-test.txt","client/common/cli/second-test.txt"],
			"excluded_roots":[".git",".cat/sessions",".cat/target",".cat-tmp","client/common/cli/target"],
			"work":[
			{"work_id":"runtime","assignment_id":"assignment-1",
			"write_paths":["client/common/cli/src-test.txt"],
			"verification":{"gate_kind":"project_maven","modules":["common/cli"],"tests":["DelegationTest"],
			"scope_authority":{"assignment_id":"assignment-1","review_id":"review-1","reviewer":"parent",
			"definition_paths":["client/mvnw","client/pom.xml","client/common/cli/pom.xml"],
			"project_output_roots":["client/common/cli/target"],
			"permitted_owned_roots":["home","repository","temporary","diagnostics"]}}},
			{"work_id":"other","assignment_id":"assignment-2",
			"write_paths":["client/common/cli/second-test.txt"],
			"verification":{"gate_kind":"project_maven","modules":["common/cli"],"tests":["OtherGateTest"],
			"scope_authority":{"assignment_id":"assignment-2","review_id":"review-2","reviewer":"parent",
			"definition_paths":["client/mvnw","client/pom.xml","client/common/cli/pom.xml"],
			"project_output_roots":[".cat/target"],
			"permitted_owned_roots":["home","repository","temporary","diagnostics"]}}}],
			"cleanup_owner":"parent"}
			""");
		return Plan.read(scope.json(), request);
	}

	/**
	 * Creates a selected dirty-project fixture.
	 *
	 * @param cleanup cleanup owner
	 * @param scope test process scope
	 * @return project root
	 * @throws IOException if fixture creation fails
	 */
	private static Path fixtureProject(TemporaryDirectories cleanup, TestJvmScope scope) throws IOException
	{
		Path project = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "delegation-project-"));
		Files.createDirectories(project.resolve("client/common/cli"));
		Files.createDirectories(project.resolve("client"));
		Files.writeString(project.resolve("client/mvnw"), "fixture wrapper");
		Files.writeString(project.resolve("client/pom.xml"), "project pom");
		Files.writeString(project.resolve("client/common/cli/pom.xml"), "module pom");
		Files.writeString(project.resolve("client/common/cli/src-test.txt"), "dirty source bytes");
		return project;
	}

	/**
	 * Finds the single fixture cohort created in the supplied temporary directory.
	 *
	 * @param temporaryDirectory test-owned temporary directory
	 * @return cohort directory
	 * @throws IOException if no cohort was created
	 */
	private static Path findCohort(Path temporaryDirectory) throws IOException
	{
		try (DirectoryStream<Path> children = Files.newDirectoryStream(temporaryDirectory, "delegation-*"))
		{
			for (Path child : children)
				if (Files.isRegularFile(child.resolve("owner.json")))
					return child;
		}
		throw new IOException("No delegation cohort found in " + temporaryDirectory);
	}

	/**
	 * Finds the operation-owned transaction created by a successful replay.
	 *
	 * @param cohort cohort directory
	 * @return unique mutation transaction
	 * @throws IOException if the transaction directory is missing or ambiguous
	 */
	private static Path findMutationTransaction(Path cohort) throws IOException
	{
		try (DirectoryStream<Path> children = Files.newDirectoryStream(cohort.resolve("transactions"),
			"mutation-transaction-*"))
		{
			Path transaction = null;
			for (Path child : children)
			{
				if (transaction != null)
					throw new IOException("More than one mutation transaction exists in " + cohort);
				transaction = child;
			}
			if (transaction != null)
				return transaction;
		}
		throw new IOException("No mutation transaction exists in " + cohort);
	}

	/**
	 * Creates a process boundary for a controlled fixture launcher.
	 *
	 * @param launcher controlled process factory
	 * @return executable file service
	 */
	private static ExecutableFiles fixtureExecutables(ExecutableFiles.ProcessLauncher launcher)
	{
		return ExecutableFilesSecrets.getExecutableFilesAccess().create(Thread::yield, Clock.systemUTC(), launcher);
	}
}
