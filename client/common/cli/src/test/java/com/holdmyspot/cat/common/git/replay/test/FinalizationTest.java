package com.holdmyspot.cat.common.git.replay.test;

import com.holdmyspot.cat.common.fakes.test.TestJvmScope;

import com.holdmyspot.cat.common.git.Git;
import com.holdmyspot.cat.common.git.replay.Finalization;
import com.holdmyspot.cat.common.git.replay.History;
import com.holdmyspot.cat.common.git.replay.ReplayJson;

import static com.holdmyspot.cat.common.git.replay.test.TopicReplayRepository.initializeRepository;
import static com.holdmyspot.cat.common.git.replay.test.TopicReplayRepository.invocationDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static java.nio.charset.StandardCharsets.UTF_8;
import org.testng.annotations.Test;

/**
 * Tests Git topic-replay finalization behavior.
 */
public final class FinalizationTest
{
	/**
	 * Creates the test fixture.
	 */
	public FinalizationTest()
	{
	}

	/**
	 * Verifies a missing receipt leaves replay history unreviewed regardless of how that history was edited.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		".*receipt is missing.*this history is unreviewed.*")
	public void rejectsManualRewordWithoutReceipt() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "receipt-missing-"));
			Path manifest = directory.resolve("manifest.json");
			Files.writeString(manifest, "{}\n");

			ReceiptFixtures.finalization(directory).requireReceipt(manifest);
		}
	}

	/**
	 * Verifies canonical receipt publication binds ordered outputs, selection, source facts, and filesystem evidence.
	 *
	 * @throws IOException if receipt evidence cannot be published or read
	 */
	@Test
	public void publishesCapturedReceiptFacts() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "receipt-facts-"));
			Path manifest = directory.resolve("manifest.json");
			Files.writeString(manifest, "{}\n");
			ReceiptFixtures.writeGates(manifest);
			Finalization finalization = ReceiptFixtures.finalization(directory);
			Finalization.FinalizationReceipt captured = ReceiptFixtures.capture(finalization, manifest);

			finalization.requirePerOutputGateEvidence(manifest, ReceiptFixtures.replay());
			finalization.publishReceipt(manifest, captured);

			Finalization.FinalizationReceipt receipt = ReplayJson.newMapper().readValue(
				finalization.requireReceipt(manifest).toFile(), Finalization.FinalizationReceipt.class);
			requireThat(receipt, "publishedReceipt").isEqualTo(captured);
			requireThat(Finalization.matchesSelection(receipt, "base", "replacement-2"), "selectedReplay").isTrue();
			requireThat(Finalization.matchesSelection(receipt, "other-base", "replacement-2"), "differentBase").isFalse();
			requireThat(Finalization.matchesSelection(receipt, "base", "other-tip"), "differentTip").isFalse();
			requireThat(Finalization.matchesSourceFacts(receipt, ReceiptFixtures.replay()), "capturedSources").isTrue();
			Finalization.Replay changed = new Finalization.Replay("base", "source-2", "changed-tree", "tree-2",
				ReceiptFixtures.replay().outputs(), ReceiptFixtures.replay().witnesses());
			requireThat(Finalization.matchesSourceFacts(receipt, changed), "changedSource").isFalse();
			requireThat(Finalization.matchesChainOutputs(receipt, ReceiptFixtures.replay(), ReceiptFixtures.chain()),
				"orderedOutputs").isTrue();
			requireThat(Finalization.matchesChainOutputs(receipt, ReceiptFixtures.replay(), List.of(
				new History.RangeCommit("replacement-2", "base"),
				new History.RangeCommit("replacement-1", "replacement-2"))), "reorderedOutputs").isFalse();
			finalization.verifyManifestDigest(manifest, receipt);
			finalization.verifyFinalizationEvidence(manifest, receipt.evidence());
			requireThat(receipt.evidence().stream().map(Finalization.ArtifactDigest::path).toList(), "evidencePaths").
				contains("gates/replay-result.log").
				doesNotContain("manifest.json.finalization-receipt.json");
		}
	}

	/**
	 * Verifies changed gate bytes invalidate a receipt captured and published by production.
	 *
	 * @throws IOException for the expected changed evidence
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*evidence changed or is missing.*")
	public void rejectsChangedEvidence() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "receipt-evidence-"));
			Path manifest = directory.resolve("manifest.json");
			Files.writeString(manifest, "{}\n");
			ReceiptFixtures.writeGates(manifest);
			Finalization finalization = ReceiptFixtures.finalization(directory);
			Finalization.FinalizationReceipt receipt = ReceiptFixtures.capture(finalization, manifest);
			finalization.publishReceipt(manifest, receipt);
			Files.writeString(manifest.resolveSibling("gates/replay-result.log"), "tampered\n");

			finalization.verifyFinalizationEvidence(manifest, receipt.evidence());
		}
	}

	/**
	 * Verifies changed manifest bytes invalidate the production-captured digest.
	 *
	 * @throws IOException for the expected changed manifest
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*does not match its manifest.*")
	public void rejectsChangedManifest() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "receipt-manifest-"));
			Path manifest = directory.resolve("manifest.json");
			Files.writeString(manifest, "{}\n");
			Finalization finalization = ReceiptFixtures.finalization(directory);
			Finalization.FinalizationReceipt receipt = ReceiptFixtures.capture(finalization, manifest);
			Files.writeString(manifest, "{\"changed\":true}\n");

			finalization.verifyManifestDigest(manifest, receipt);
		}
	}

	/**
	 * Verifies every retained output needs build evidence; discarded outputs require no logs.
	 *
	 * @throws IOException for the expected missing build
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*requires per-output-build.*")
	public void rejectsMissingOutputBuild() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "receipt-build-"));
			Path manifest = directory.resolve("manifest.json");
			Files.writeString(manifest, "{}\n");
			ReceiptFixtures.writeGates(manifest);
			Files.delete(manifest.resolveSibling("gates/per-output-build-first.log"));

			ReceiptFixtures.finalization(directory).requirePerOutputGateEvidence(manifest, ReceiptFixtures.replay());
		}
	}

	/**
	 * Verifies an existing build log cannot substitute for retained-output behavior acceptance evidence.
	 *
	 * @throws IOException for the expected missing acceptance
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*requires per-output-acceptance.*")
	public void rejectsMissingOutputAcceptance() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "receipt-acceptance-"));
			Path manifest = directory.resolve("manifest.json");
			Files.writeString(manifest, "{}\n");
			ReceiptFixtures.writeGates(manifest);
			Files.delete(manifest.resolveSibling("gates/per-output-acceptance-first.log"));

			ReceiptFixtures.finalization(directory).requirePerOutputGateEvidence(manifest, ReceiptFixtures.replay());
		}
	}

	/**
	 * Verifies CAT interprets four identity fields as the approved author and committer.
	 *
	 * @throws IOException if approved captured identities are rejected
	 */
	@Test
	public void acceptsApprovedIdentity() throws IOException
	{
		byte[] identity = "Approved\0approved@example.invalid\0Approved\0approved@example.invalid\n".getBytes(UTF_8);
		Finalization.verifyCommitIdentity("replacement", identity, "Approved", "approved@example.invalid");
	}

	/**
	 * Verifies a different committer cannot satisfy the approved identity policy.
	 *
	 * @throws IOException for the expected identity mismatch
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*approved author and committer.*")
	public void rejectsUnapprovedIdentity() throws IOException
	{
		byte[] identity = "Approved\0approved@example.invalid\0Other\0other@example.invalid\n".getBytes(UTF_8);
		Finalization.verifyCommitIdentity("replacement", identity, "Approved", "approved@example.invalid");
	}

	/**
	 * Verifies an actual Git chain publishes and reuses one receipt, accepts symbolic or resolved tips, and passes the
	 * identity check for the identity that made its commits.
	 *
	 * @throws IOException if Git or receipt publication fails
	 * @throws NoSuchAlgorithmException if the JDK lacks SHA-256
	 */
	@Test
	public void publishAndVerifyGitReceipt() throws IOException, NoSuchAlgorithmException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "receipt-git-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			git.lines("commit", "--allow-empty", "--quiet", "-m", "feat: first");
			git.lines("commit", "--allow-empty", "--quiet", "-m", "feat: second");
			List<String> resolved = git.lines("rev-parse", "HEAD~2", "HEAD~1", "HEAD", "HEAD^{tree}");
			String base = resolved.getFirst();
			String tip = resolved.get(2);
			String tree = resolved.get(3);
			Path manifest = invocationDirectory(repository, "receipt").resolve("manifest.json");
			Files.writeString(manifest, "{}\n");
			ReceiptFixtures.writeGates(manifest);
			Finalization.Replay replay = new Finalization.Replay(base, tip, tree, tree,
				List.of(new Finalization.Output("first", true), new Finalization.Output("second", true)),
				List.of(new Finalization.OutputWitness("first", tree, tree),
					new Finalization.OutputWitness("second", tree, tree)));
			Finalization finalization = ReceiptFixtures.finalization(repository);

			finalization.publish(manifest, replay, tip);
			Path receiptFile = finalization.requireReceipt(manifest);
			String published = Files.readString(receiptFile);
			Finalization.FinalizationReceipt receipt = ReplayJson.newMapper().readValue(receiptFile.toFile(),
				Finalization.FinalizationReceipt.class);
			String first = resolved.get(1);
			requireThat(receipt.outputs().stream().map(Finalization.FinalizedOutput::commit).toList(), "replacementCommits").
				isEqualTo(List.of(first, tip));
			requireThat(receipt.outputs().stream().map(Finalization.FinalizedOutput::parent).toList(), "replacementParents").
				isEqualTo(List.of(base, first));
			byte[] identities = git.bytes("show", "-s", "--format=%H%x00%an%x00%ae%x00%cn%x00%ce", first, tip);
			requireThat(receipt.identityDigest(), "identityDigest").isEqualTo(
				HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identities)));
			finalization.verify(manifest, replay, base, "HEAD");
			finalization.verify(manifest, replay, base, tip);
			finalization.publish(manifest, replay, tip);
			finalization.verifyIdentity(base, tip, "CAT Test", "cat-test@example.invalid");
			requireThat(Files.readString(receiptFile), "reusedReceipt").isEqualTo(published);
		}
	}
}
