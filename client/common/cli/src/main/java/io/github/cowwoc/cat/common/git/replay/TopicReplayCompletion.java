package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.git.Git;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Completes one sealed topic replay without losing the backup or finalization evidence needed to recover it.
 */
public final class TopicReplayCompletion
{
	private final Git git;
	private final History history;
	private final ManifestStore<BatchManifest> manifests;
	private final CompletedBatchManifest completedManifests;
	private final ReplayPreflight preflight;
	private final ReplayExecution execution;
	private final ReplayVerification verification;
	private final Backup backups;
	private final Finalization finalization;

	/**
	 * Creates the lifecycle owner for one repository's topic replays.
	 *
	 * @param git the repository access boundary
	 * @param history the source-history verifier
	 * @param manifests the manifest persistence boundary
	 * @param completedManifests the completed-manifest reader
	 * @param preflight the frozen-plan verifier
	 * @param execution the replay executor
	 * @param verification the replacement-history verifier
	 * @param backups the recorded-backup owner
	 * @param finalization the final receipt owner
	 */
	TopicReplayCompletion(Git git, History history, ManifestStore<BatchManifest> manifests,
		CompletedBatchManifest completedManifests, ReplayPreflight preflight, ReplayExecution execution,
		ReplayVerification verification, Backup backups, Finalization finalization)
	{
		this.git = git;
		this.history = history;
		this.manifests = manifests;
		this.completedManifests = completedManifests;
		this.preflight = preflight;
		this.execution = execution;
		this.verification = verification;
		this.backups = backups;
		this.finalization = finalization;
	}

	/**
	 * Verifies one sealed replay plan before it can change Git history.
	 *
	 * @param manifestFile the sealed replay manifest
	 * @throws IOException if the manifest or its recorded evidence is incomplete or inconsistent
	 */
	void preflight(Path manifestFile) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		preflight.verify(manifestFile, completedManifests.read(manifestFile));
	}

	/**
	 * Creates the recovery branch for an initialized replay before Git history changes.
	 *
	 * @param manifestFile the initialized replay manifest
	 * @return the recorded backup branch
	 * @throws IOException if the manifest is incomplete, the repository is unsafe, or Git cannot create the backup
	 */
	String createBackup(Path manifestFile) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		BatchManifest manifest = manifests.readUnfinalized(manifestFile);
		if (manifest.sources().isEmpty())
		{
			throw new IOException("Topic-replay backup requires an initialized version-5 manifest with captured sources; " +
				"run initialize before creating the backup");
		}
		history.verifyPlanningRepositoryState(manifest.sources().getLast().hash());
		return backups.create(manifestFile);
	}

	/**
	 * Replays one sealed plan after preserving or verifying its recorded recovery branch.
	 *
	 * @param manifestFile the sealed replay manifest
	 * @return the final replacement commit
	 * @throws IOException if preflight, backup verification, replay, or replacement verification fails
	 */
	String replay(Path manifestFile) throws IOException
	{
		preflight(manifestFile);
		if (!backups.hasInventory(manifestFile))
			createBackup(manifestFile);
		else
			backups.verify(manifestFile);
		BatchManifest manifest = completedManifests.read(manifestFile);
		String rewrittenTip = execution.execute(manifestFile, manifest);
		verification.verify(manifestFile, manifest, manifest.base(), rewrittenTip);
		return rewrittenTip;
	}

	/**
	 * Publishes finalization evidence and then removes exactly the backup recorded for that replay.
	 *
	 * @param manifestFile the sealed replay manifest
	 * @param rewrittenTip the verified final replacement commit
	 * @return the deleted backup branch
	 * @throws IOException if replay verification, receipt publication, gate-evidence validation, or backup deletion fails
	 */
	String finalizeReplay(Path manifestFile, String rewrittenTip) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(rewrittenTip, "rewrittenTip").isNotBlank();
		BatchManifest manifest = completedManifests.read(manifestFile);
		verification.verify(manifestFile, manifest, manifest.base(), rewrittenTip);
		return publishThenFinalize(() -> finalization.publish(manifestFile, Finalization.replay(manifest), rewrittenTip),
			() -> finalizeBackup(manifestFile, rewrittenTip));
	}

	/**
	 * Publishes durable replay evidence before attempting recovery-branch cleanup.
	 *
	 * @param publication the receipt publication or verification operation
	 * @param completion the recorded-backup cleanup operation
	 * @return the deleted backup branch
	 * @throws IOException if publication or cleanup fails
	 */
	public static String publishThenFinalize(ReceiptPublication publication, BackupCompletion completion)
		throws IOException
	{
		publication.publish();
		return completion.complete();
	}

	/** Publishes or verifies the durable receipt before cleanup. */
	@FunctionalInterface
	public interface ReceiptPublication
	{
		/**
		 * Establishes durable finalization evidence.
		 *
		 * @throws IOException if the receipt cannot be published or verified
		 */
		void publish() throws IOException;
	}

	/** Completes the cleanup of the replay's recorded backup. */
	@FunctionalInterface
	public interface BackupCompletion
	{
		/**
		 * Removes the recorded recovery branch after receipt publication.
		 *
		 * @return the deleted branch
		 * @throws IOException if cleanup fails
		 */
		String complete() throws IOException;
	}

	/**
	 * Verifies a replay and removes only its recorded recovery branch.
	 *
	 * @param manifestFile the sealed replay manifest
	 * @param rewrittenTip the verified final replacement commit
	 * @return the deleted backup branch
	 * @throws IOException if gate evidence, replay verification, or backup deletion fails
	 */
	String finalizeBackup(Path manifestFile, String rewrittenTip) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(rewrittenTip, "rewrittenTip").isNotBlank();
		BatchManifest manifest = completedManifests.read(manifestFile);
		finalization.requirePerOutputGateEvidence(manifestFile, Finalization.replay(manifest));
		verification.verify(manifestFile, manifest, manifest.base(), rewrittenTip);
		return backups.delete(manifestFile);
	}

	/**
	 * Verifies one finalization receipt against the requested replay chain.
	 *
	 * @param manifestFile the sealed replay manifest
	 * @param rebaseBase the commit before the replaced range
	 * @param rewrittenTip the final replacement commit
	 * @throws IOException if the receipt, manifest, gate evidence, or replacement chain is invalid
	 */
	void verifyFinalization(Path manifestFile, String rebaseBase, String rewrittenTip) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(rebaseBase, "rebaseBase").isNotBlank();
		requireThat(rewrittenTip, "rewrittenTip").isNotBlank();
		String expectedRewrittenTip = git.lines("rev-parse", rewrittenTip).getFirst();
		finalization.requireReceipt(manifestFile);
		BatchManifest manifest = completedManifests.read(manifestFile);
		verification.verify(manifestFile, manifest, rebaseBase, expectedRewrittenTip);
		finalization.verify(manifestFile, Finalization.replay(manifest), rebaseBase, expectedRewrittenTip);
	}

	/**
	 * Verifies that every replacement commit uses one approved author and committer identity.
	 *
	 * @param rebaseBase the commit before the replaced range
	 * @param rewrittenTip the final replacement commit
	 * @param name the approved Git author and committer name
	 * @param email the approved Git author and committer email
	 * @throws IOException if Git cannot read the range or an identity differs from the approved policy
	 */
	void verifyIdentity(String rebaseBase, String rewrittenTip, String name, String email) throws IOException
	{
		finalization.verifyIdentity(rebaseBase, rewrittenTip, name, email);
	}
}
