package com.holdmyspot.cat.common.delegation.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.delegation.Replay;
import com.holdmyspot.cat.common.delegation.SourceSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Tests transactional delegate replay and rollback.
 */
public final class ReplayTest
{
	/**
	 * Creates the test fixture.
	 */
	public ReplayTest()
	{
	}

	/**
	 * Verifies an ordinary failed gate restores retained preimage bytes.
	 *
	 * @throws IOException if the temporary fixture cannot be created
	 */
	@Test
	public void failedGateRestoresPreimages() throws IOException
	{
		try (TestJvmScope scope = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "replay-project-")).toRealPath();
			Path candidate =
				cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "replay-candidate-")).toRealPath();
			Path transaction =
				cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "replay-transaction-")).toRealPath();
			Files.writeString(project.resolve("unit.txt"), "before");
			Files.writeString(candidate.resolve("unit.txt"), "after");
			Files.writeString(candidate.resolve("new.txt"), "new candidate file");
			BasicFileAttributes beforeAttributes = Files.readAttributes(project.resolve("unit.txt"),
				BasicFileAttributes.class);
			String beforeMetadata = SourceSnapshot.metadataIdentity(project.resolve("unit.txt"));
			boolean posix = Files.getFileStore(project).supportsFileAttributeView("posix");
			Set<PosixFilePermission> beforePermissions = Set.of();
			if (posix)
				beforePermissions = Files.getPosixFilePermissions(project.resolve("unit.txt"));
			SourceSnapshot snapshot = SourceSnapshot.capture(project, transaction.resolve("snapshot"),
				List.of("unit.txt"));

			Replay.Outcome outcome = Replay.integrate(project, candidate, snapshot, List.of("unit.txt", "new.txt"),
				transaction, List.of(), List.of(),
				_ -> false);

			requireThat(outcome.accepted(), "accepted").isFalse();
			requireThat(Files.readString(project.resolve("unit.txt")), "restoredBytes").isEqualTo("before");
			requireThat(Files.exists(project.resolve("new.txt")), "absentPreimageRestored").isFalse();
			requireThat(outcome.restoredPaths(), "restoredPaths").contains("unit.txt");
			requireThat(Files.readAttributes(project.resolve("unit.txt"), BasicFileAttributes.class).fileKey(),
				"originalEntryRetained").isEqualTo(beforeAttributes.fileKey());
			if (posix)
			{
				requireThat(Files.getPosixFilePermissions(project.resolve("unit.txt")), "permissionsRestored").
					isEqualTo(beforePermissions);
			}
			requireThat(Files.getLastModifiedTime(project.resolve("unit.txt")), "modifiedTimeRestored").
				isEqualTo(beforeAttributes.lastModifiedTime());
			requireThat(SourceSnapshot.metadataIdentity(project.resolve("unit.txt")), "supportedMetadataRestored").
				isEqualTo(beforeMetadata);
			requireThat(Files.readAttributes(project.resolve("unit.txt"), BasicFileAttributes.class).creationTime(),
				"creationMetadataRestored").isEqualTo(beforeAttributes.creationTime());
		}
	}

	/**
	 * Verifies rollback preserves a postimage changed by the gate.
	 *
	 * @throws IOException if the temporary fixture cannot be created
	 */
	@Test
	public void changedPostimageIsPreserved() throws IOException
	{
		try (TestJvmScope scope = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "replay-project-")).toRealPath();
			Path candidate =
				cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "replay-candidate-")).toRealPath();
			Path transaction =
				cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "replay-transaction-")).toRealPath();
			Files.writeString(project.resolve("unit.txt"), "before");
			Files.writeString(candidate.resolve("unit.txt"), "after");
			SourceSnapshot snapshot = SourceSnapshot.capture(project, transaction.resolve("snapshot"),
				List.of("unit.txt"));

			Replay.Outcome outcome = Replay.integrate(project, candidate, snapshot, List.of("unit.txt"), transaction,
				List.of(), List.of(), changedProject ->
				{
					Files.writeString(changedProject.resolve("unit.txt"), "external");
					return false;
				});

			requireThat(Files.readString(project.resolve("unit.txt")), "externalBytes").isEqualTo("external");
			requireThat(outcome.conflictPaths(), "conflictPaths").contains("unit.txt");
		}
	}

	/**
	 * Verifies a stale parent edit is preserved before any delegate path is written.
	 *
	 * @throws IOException if the temporary fixture cannot be created
	 */
	@Test
	public void staleParentRejectsReplay() throws IOException
	{
		try (TestJvmScope scope = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "replay-project-")).toRealPath();
			Path candidate =
				cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "replay-candidate-")).toRealPath();
			Path transaction =
				cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "replay-transaction-")).toRealPath();
			Files.writeString(project.resolve("unit.txt"), "before");
			Files.writeString(candidate.resolve("unit.txt"), "delegate");
			SourceSnapshot snapshot = SourceSnapshot.capture(project, transaction.resolve("snapshot"),
				List.of("unit.txt"));
			Files.writeString(project.resolve("unit.txt"), "new parent edit");
			IOException failure = null;

			try
			{
				Replay.integrate(project, candidate, snapshot, List.of("unit.txt"), transaction, List.of(), List.of(),
					_ -> true);
			}
			catch (IOException e)
			{
				failure = e;
			}

			requireThat(failure, "failure").isNotNull();
			requireThat(Files.readString(project.resolve("unit.txt")), "parentBytes").isEqualTo("new parent edit");
		}
	}

	/**
	 * Verifies changed supported ancestor metadata is stale before replay.
	 *
	 * @throws IOException if the temporary fixture cannot be created
	 */
	@Test
	public void staleAncestorMetadataRejectsReplay() throws IOException
	{
		try (TestJvmScope scope = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "replay-project-")).toRealPath();
			Path candidate =
				cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "replay-candidate-")).toRealPath();
			Path transaction =
				cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "replay-transaction-")).toRealPath();
			Files.writeString(project.resolve("unit.txt"), "before");
			Files.writeString(candidate.resolve("unit.txt"), "after");
			SourceSnapshot snapshot = SourceSnapshot.capture(project, transaction.resolve("snapshot"), List.of("unit.txt"));
			String originalMetadata = SourceSnapshot.metadataIdentity(project);
			boolean posix = Files.getFileStore(project).supportsFileAttributeView("posix");
			Set<PosixFilePermission> originalPermissions = Set.of();
			boolean originallyHidden = false;
			if (posix)
				originalPermissions = Files.getPosixFilePermissions(project);
			else
				originallyHidden = Files.readAttributes(project, DosFileAttributes.class).isHidden();
			try
			{
				if (posix)
				{
					Files.setPosixFilePermissions(project, Set.of(PosixFilePermission.OWNER_READ,
						PosixFilePermission.OWNER_EXECUTE));
				}
				else
					Files.setAttribute(project, "dos:hidden", !originallyHidden);
				requireThat(SourceSnapshot.metadataIdentity(project), "ancestorMetadataChanged").isNotEqualTo(originalMetadata);
				IOException failure = null;
				try
				{
					Replay.integrate(project, candidate, snapshot, List.of("unit.txt"), transaction, List.of(), List.of(),
						_ -> true);
				}
				catch (IOException e)
				{
					failure = e;
				}
				requireThat(failure, "staleDirectoryMetadataRejected").isNotNull();
				requireThat(failure.getMessage(), "staleDirectoryMetadataReason").contains("Stale parent directory metadata");
				requireThat(Files.readString(project.resolve("unit.txt")), "sourceNotReplayed").isEqualTo("before");
			}
			finally
			{
				if (posix)
					Files.setPosixFilePermissions(project, originalPermissions);
				else
					Files.setAttribute(project, "dos:hidden", originallyHidden);
			}
		}
	}

	/**
	 * Verifies recovery restores preimages and validates every journal record before applying recovery.
	 *
	 * @throws IOException if the interrupted transaction fixture cannot be created
	 */
	@Test
	public void interruptedReplayIsReconciled() throws IOException
	{
		try (TestJvmScope scope = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "replay-project-")).toRealPath();
			Path candidate =
				cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "replay-candidate-")).toRealPath();
			Path transaction =
				cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "replay-transaction-")).toRealPath();
			Files.writeString(project.resolve("unit.txt"), "before");
			Files.writeString(candidate.resolve("unit.txt"), "after");
			java.nio.file.attribute.FileTime directoryTime = Files.getLastModifiedTime(project);
			SourceSnapshot snapshot = SourceSnapshot.capture(project, transaction.resolve("snapshot"),
				List.of("unit.txt"));
			Path retained = transaction.resolve("preimages/unit.txt");
			Files.createDirectories(retained.getParent());
			Files.move(project.resolve("unit.txt"), retained);
			String journalHeader = "{\"format_version\":1,\"state\":\"prepared\",\"directory_times\":{\".\":\"" +
				directoryTime + "\"},\"absent_directories\":[]}\n";
			Files.writeString(transaction.resolve("journal.jsonl"), journalHeader +
				"{\"path\":\"unit.txt\",\"retained\":\"unit.txt\",\"state\":\"retain_intent\"," +
				"\"preimage\":\"before\",\"postimage\":\"after\"}\n");

			Replay.Outcome outcome = Replay.reconcile(scope.json(), project, candidate, snapshot, List.of("unit.txt"),
				transaction, List.of(), List.of());

			requireThat(outcome.accepted(), "notIntegrated").isFalse();
			requireThat(outcome.restoredPaths(), "restored").contains("unit.txt");
			requireThat(Files.readString(project.resolve("unit.txt")), "preimageRestored").isEqualTo("before");
			requireThat(Files.getLastModifiedTime(project), "ancestorMetadataRestored").isEqualTo(directoryTime);
			requireThat(Files.readString(transaction.resolve("journal.jsonl")), "terminalRollback").
				contains("rolled_back");
			Files.writeString(transaction.resolve("journal.jsonl"), journalHeader +
				"{\"state\":\"integrated\"}\n{\"state\":\"rolled_back\"}\n");
			Replay.Outcome lastTerminal = Replay.reconcile(scope.json(), project, candidate, snapshot, List.of("unit.txt"),
				transaction, List.of(), List.of());
			requireThat(lastTerminal.accepted(), "lastTerminalStateApplied").isFalse();

			String malformedAfterTerminal = journalHeader + "{\"state\":\"recovery_required\"}\ncorrupt journal\n";
			Files.writeString(transaction.resolve("journal.jsonl"), malformedAfterTerminal);
			IOException corruptJournal = null;
			try
			{
				Replay.reconcile(scope.json(), project, candidate, snapshot, List.of("unit.txt"), transaction,
					List.of(), List.of());
			}
			catch (IOException e)
			{
				corruptJournal = e;
			}
			requireThat(corruptJournal, "corruptJournalRejected").isNotNull();
			requireThat(Files.readString(project.resolve("unit.txt")), "parentPreimagePreserved").
				isEqualTo("before");
			requireThat(Files.readString(transaction.resolve("journal.jsonl")), "journalUnchangedAfterInvalidRecord").
				isEqualTo(malformedAfterTerminal);
		}
	}

	/**
	 * Verifies unsafe transaction roots reject integrate and recovery before changing source.
	 *
	 * @throws IOException if an unsafe storage fixture cannot be created
	 */
	@Test
	public void unsafeStorageRejectsBeforeReplay() throws IOException
	{
		try (TestJvmScope scope = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project =
				cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "replay-unsafe-project-")).toRealPath();
			Path candidate =
				cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "replay-unsafe-candidate-")).toRealPath();
			Path evidence =
				cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "replay-safe-evidence-")).toRealPath();
			Files.createDirectories(project.resolve("src"));
			Files.createDirectories(candidate.resolve("src"));
			Files.writeString(project.resolve("src/unit.txt"), "before");
			Files.writeString(candidate.resolve("src/unit.txt"), "after");
			SourceSnapshot snapshot = SourceSnapshot.capture(project, evidence.resolve("snapshot"), List.of("src/unit.txt"));

			Path sourceNesting = createTransaction(project, "src/cache");
			assertUnsafeStorage(scope, project, candidate, snapshot, sourceNesting, List.of("src/unit.txt"),
				List.of("src"), List.of());
			Path sourceAncestor = project.resolve("src");
			assertUnsafeStorage(scope, project, candidate, snapshot, sourceAncestor, List.of("src/unit.txt"),
				List.of("src"), List.of());
			SourceSnapshot absentSnapshot = SourceSnapshot.capture(project, evidence.resolve("snapshot-absent"),
				List.of("src/unit.txt", "absent-storage"));
			Path absentPreimage = createTransaction(project, "absent-storage");
			assertUnsafeStorage(scope, project, candidate, absentSnapshot, absentPreimage, List.of("src/unit.txt"),
				List.of("absent-storage"), List.of());
			Path absentPathOverlap = createTransaction(project, "staging");
			assertUnsafeStorage(scope, project, candidate, snapshot, absentPathOverlap, List.of("staging/missing"),
				List.of("staging"), List.of());
			Path changedPathOverlap = createTransaction(project, "changed");
			assertUnsafeStorage(scope, project, candidate, snapshot, changedPathOverlap, List.of("changed/leaf"),
				List.of("changed"), List.of());
			Path unreviewed = createTransaction(project, "unreviewed");
			assertUnsafeStorage(scope, project, candidate, snapshot, unreviewed, List.of("src/unit.txt"),
				List.of(".cat/sessions"), List.of());
			Path gitAdministration = createTransaction(project, ".GIT/storage");
			assertUnsafeStorage(scope, project, candidate, snapshot, gitAdministration, List.of("src/unit.txt"),
				List.of(".GIT"), List.of());
			assertUnsafeStorage(scope, project, candidate, snapshot, project, List.of("src/unit.txt"),
				List.of("."), List.of());
			assertUnsafeStorage(scope, project, candidate, snapshot, candidate, List.of("src/unit.txt"),
				List.of(".cat/sessions"), List.of());
			Path output = createTransaction(project, "client/common/cli/target/transaction");
			assertUnsafeStorage(scope, project, candidate, snapshot, output, List.of("src/unit.txt"),
				List.of("client/common/cli/target"), List.of("client/common/cli/target"));
			Path broadExclusion = createTransaction(project, ".cat/sessions/transaction");
			assertUnsafeStorage(scope, project, candidate, snapshot, broadExclusion, List.of("src/unit.txt"),
				List.of(".cat"), List.of(".cat/target"));
			Files.createDirectories(project.resolve("client/common/cli/target"));
			Files.createSymbolicLink(project.resolve("storage-alias"), Path.of("client/common/cli/target"));
			Path symlinkAlias = Files.createDirectories(project.resolve("storage-alias/alias-transaction"));
			assertUnsafeStorage(scope, project, candidate, snapshot, symlinkAlias, List.of("src/unit.txt"),
				List.of("client/common/cli/target"), List.of("client/common/cli/target"));
		}
	}

	/**
	 * Verifies a nested session transaction restores original metadata after gate rejection.
	 *
	 * @throws IOException if the nested session fixture cannot be created
	 */
	@Test
	public void sessionStorageRestoresOriginals() throws IOException
	{
		try (TestJvmScope scope = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project =
				cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "replay-session-project-")).toRealPath();
			Path candidate =
				cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "replay-session-candidate-")).toRealPath();
			Path transaction = Files.createDirectories(project.resolve(".cat/sessions/session/temp/transaction"));
			Path parentFile = project.resolve("unit.txt");
			Path candidateFile = candidate.resolve("unit.txt");
			Files.writeString(parentFile, "before");
			Files.writeString(candidateFile, "after");
			BasicFileAttributes beforeAttributes = Files.readAttributes(parentFile, BasicFileAttributes.class);
			boolean posix = Files.getFileStore(project).supportsFileAttributeView("posix");
			Set<PosixFilePermission> beforePermissions = Set.of();
			if (posix)
				beforePermissions = Files.getPosixFilePermissions(parentFile);
			String beforeMetadata = SourceSnapshot.metadataIdentity(parentFile);
			SourceSnapshot snapshot = SourceSnapshot.capture(project, transaction.resolve("snapshot"), List.of("unit.txt"));

			Replay.Outcome outcome = Replay.integrate(project, candidate, snapshot, List.of("unit.txt"), transaction,
				List.of(".cat/sessions"), List.of("client/common/cli/target"), _ -> false);

			requireThat(outcome.accepted(), "gateRejectedIntegration").isFalse();
			requireThat(Files.readString(parentFile), "restoredBytes").isEqualTo("before");
			requireThat(Files.readAttributes(parentFile, BasicFileAttributes.class).fileKey(), "originalEntryRetained").
				isEqualTo(beforeAttributes.fileKey());
			if (posix)
				requireThat(Files.getPosixFilePermissions(parentFile), "permissionsRestored").isEqualTo(beforePermissions);
			requireThat(Files.getLastModifiedTime(parentFile), "modifiedTimeRestored").
				isEqualTo(beforeAttributes.lastModifiedTime());
			requireThat(SourceSnapshot.metadataIdentity(parentFile), "supportedMetadataRestored").
				isEqualTo(beforeMetadata);
			requireThat(Files.readAttributes(parentFile, BasicFileAttributes.class).creationTime(),
				"creationMetadataRestored").isEqualTo(beforeAttributes.creationTime());
			requireThat(Files.readString(transaction.resolve("journal.jsonl")), "retainedRollbackJournal").
				contains("rolled_back");
		}
	}

	/**
	 * Creates a transaction directory at a project-relative location.
	 *
	 * @param project project root
	 * @param relative relative transaction directory
	 * @return created transaction directory
	 * @throws IOException if the directory cannot be created
	 */
	private static Path createTransaction(Path project, String relative) throws IOException
	{
		return Files.createDirectories(project.resolve(relative));
	}

	/**
	 * Verifies integrate and reconcile refuse unsafe storage before source or evidence changes.
	 *
	 * @param scope test scope providing the JSON mapper
	 * @param project project root
	 * @param candidate candidate root
	 * @param snapshot immutable source evidence
	 * @param transaction unsafe transaction directory
	 * @param changedPaths project-relative replay paths
	 * @param excludedRoots reviewed project-relative exclusions
	 * @param outputRoots project-relative verification outputs
	 * @throws IOException if a fixture cannot be created
	 */
	private static void assertUnsafeStorage(TestJvmScope scope, Path project, Path candidate, SourceSnapshot snapshot,
		Path transaction, List<String> changedPaths, List<String> excludedRoots, List<String> outputRoots)
		throws IOException
	{
		Path retainedEvidence = Files.writeString(transaction.resolve("retained.txt"), "keep evidence");
		int[] gateCalls = {0};
		IOException integrateFailure = null;
		try
		{
			Replay.integrate(project, candidate, snapshot, changedPaths, transaction, excludedRoots, outputRoots,
				_ ->
				{
					gateCalls[0] += 1;
					return true;
				});
		}
		catch (IOException e)
		{
			integrateFailure = e;
		}
		requireThat(integrateFailure, "integrateUnsafeStorageRejected").isNotNull();
		requireThat(integrateFailure.getMessage(), "integrateUnsafeStorageDiagnostic").
			contains("Unsafe transaction storage");
		IOException reconcileFailure = null;
		try
		{
			Replay.reconcile(scope.json(), project, candidate, snapshot, changedPaths, transaction, excludedRoots,
				outputRoots);
		}
		catch (IOException e)
		{
			reconcileFailure = e;
		}
		requireThat(reconcileFailure, "reconcileUnsafeStorageRejected").isNotNull();
		requireThat(reconcileFailure.getMessage(), "reconcileUnsafeStorageDiagnostic").
			contains("Unsafe transaction storage");
		requireThat(gateCalls[0], "gateNotInvoked").isEqualTo(0);
		requireThat(Files.readString(project.resolve("src/unit.txt")), "sourceNotReplayed").isEqualTo("before");
		requireThat(Files.readString(retainedEvidence), "retainedEvidencePreserved").isEqualTo("keep evidence");
		requireThat(Files.exists(transaction.resolve("journal.jsonl")), "noJournalCreated").isFalse();
	}
}
