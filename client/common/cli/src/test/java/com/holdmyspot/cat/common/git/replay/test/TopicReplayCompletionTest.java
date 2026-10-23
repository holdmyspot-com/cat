package com.holdmyspot.cat.common.git.replay.test;

import com.holdmyspot.cat.common.fakes.test.TestJvmScope;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.git.replay.Finalization;
import com.holdmyspot.cat.common.git.replay.ReplayJson;
import com.holdmyspot.cat.common.git.replay.TopicReplayCompletion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import org.testng.annotations.Test;

/**
 * Tests receipt publication and recorded-backup cleanup ordering across failures and retries.
 */
public final class TopicReplayCompletionTest
{
	/** Creates the test fixture. */
	public TopicReplayCompletionTest()
	{
	}

	/**
	 * Verifies cleanup failure preserves a canonical receipt that a later cleanup attempt can reuse.
	 *
	 * @throws IOException if the fixture or receipt operation fails unexpectedly
	 */
	@Test
	public void preservesReceiptAcrossCleanupRetry() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "completion-retry-"));
			Path manifest = directory.resolve("manifest.json");
			Files.writeString(manifest, "{}\n");
			ReceiptFixtures.writeGates(manifest);
			Finalization finalization = ReceiptFixtures.finalization(directory);
			Finalization.FinalizationReceipt captured = ReceiptFixtures.capture(finalization, manifest);
			AtomicBoolean failed = new AtomicBoolean();
			try
			{
				TopicReplayCompletion.publishThenFinalize(() -> finalization.publishReceipt(manifest, captured), () ->
				{
					requireThat(finalization.requireReceipt(manifest), "publishedReceipt").isRegularFile();
					throw new IOException("Recorded-backup cleanup failed");
				});
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "cleanupFailure").isEqualTo("Recorded-backup cleanup failed");
				failed.set(true);
			}
			requireThat(failed.get(), "cleanupFailed").isTrue();
			Path receiptFile = finalization.requireReceipt(manifest);
			String receiptBytes = Files.readString(receiptFile);
			Finalization.FinalizationReceipt receipt = ReplayJson.newMapper().readValue(receiptFile.toFile(),
				Finalization.FinalizationReceipt.class);
			requireThat(receipt, "durableReceipt").isEqualTo(captured);

			String deleted = TopicReplayCompletion.publishThenFinalize(() ->
			{
				finalization.verifyManifestDigest(manifest, receipt);
				finalization.verifyFinalizationEvidence(manifest, receipt.evidence());
			}, () ->
			{
				requireThat(Files.readString(receiptFile), "receiptBeforeCleanup").isEqualTo(receiptBytes);
				return "recorded-backup";
			});
			requireThat(deleted, "deletedBackup").isEqualTo("recorded-backup");
			requireThat(Files.readString(receiptFile), "receiptAfterCleanup").isEqualTo(receiptBytes);
		}
	}

	/**
	 * Verifies failed receipt publication never reaches recorded-backup cleanup.
	 *
	 * @throws IOException if the expected publication failure occurs
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = "Receipt publication failed")
	public void retainsBackupOnPublicationFailure() throws IOException
	{
		AtomicBoolean cleanupAttempted = new AtomicBoolean();
		try
		{
			TopicReplayCompletion.publishThenFinalize(() ->
			{
				throw new IOException("Receipt publication failed");
			}, () ->
			{
				cleanupAttempted.set(true);
				return "recorded-backup";
			});
		}
		finally
		{
			requireThat(cleanupAttempted.get(), "cleanupAttempted").isFalse();
		}
	}
}
