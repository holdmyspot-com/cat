package io.github.cowwoc.cat.common.usage;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks durable report delivery with harness-neutral request bodies.
 */
public final class UsageReportOutboxTest
{
	/**
	 * Creates the TestNG test class.
	 */
	public UsageReportOutboxTest()
	{
	}

	/**
	 * A new client process reuses the pending event and sends it only for its authenticated user.
	 *
	 * @throws IOException if the test-owned data directory fails
	 * @throws InterruptedException if delivery is interrupted
	 */
	@Test
	public void retainsAndDeliversAfterRestart() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-usage-outbox-"));
			Path data = root.resolve("outbox");
			UsageReportOutbox firstProcess = new UsageReportOutbox(data);
			UsageReportOutbox.PendingReport report = firstProcess.enqueue("provider:user-a", 17,
				"product-a/turn-1/42", 42, eventId -> "{\"event_id\":\"" + eventId + "\",\"harness_id\":\"harness-a\"}");
			UsageReportOutbox restarted = new UsageReportOutbox(data);
			requireThat(restarted.enqueue("provider:user-a", 17, "product-a/turn-1/42", 42,
				eventId -> "{\"event_id\":\"" + eventId + "\",\"harness_id\":\"harness-a\"}"),
				"retainedReport").isEqualTo(report);

			AtomicInteger sends = new AtomicInteger();
			AtomicReference<String> sentBody = new AtomicReference<>();
			UsageReportOutbox.Sender sender = (token, accountId, pending) ->
			{
				requireThat(token, "token").isEqualTo("access");
				requireThat(accountId, "accountId").isEqualTo(17);
				sends.incrementAndGet();
				sentBody.set(pending.body());
				return new UsageReportOutbox.Acknowledgement(pending.eventId(), 42);
			};
			requireThat(restarted.flush("provider:user-b", "access", sender), "otherUserSent").isEqualTo(0);
			requireThat(sends.get(), "sendsForOtherUser").isEqualTo(0);
			requireThat(restarted.flush("provider:user-a", "access", sender), "confirmed").isEqualTo(1);
			requireThat(sentBody.get(), "sentBody").isEqualTo(report.body());
			requireThat(restarted.flush("provider:user-a", "access", sender), "resent").isEqualTo(0);
			requireThat(sends.get(), "sendCount").isEqualTo(1);
		}
	}

	/**
	 * An unconfirmed send leaves its exact body for a new client process to retry.
	 *
	 * @throws IOException for the first unconfirmed result or data failure
	 * @throws InterruptedException if delivery is interrupted
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "Usage server did not confirm this delivery and count")
	public void retriesUnconfirmedDelivery() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-usage-outbox-"));
			Path data = root.resolve("outbox");
			UsageReportOutbox firstProcess = new UsageReportOutbox(data);
			UsageReportOutbox.PendingReport report = firstProcess.enqueue("provider:user-a", 17,
				"product-a/turn-1/42", 42, eventId -> "{\"event_id\":\"" + eventId + "\"}");
			AtomicReference<String> firstBody = new AtomicReference<>();
			try
			{
				firstProcess.flush("provider:user-a", "access", (token, accountId, pending) ->
				{
					firstBody.set(pending.body());
					return new UsageReportOutbox.Acknowledgement(report.eventId(), 41);
				});
			}
			finally
			{
				UsageReportOutbox restarted = new UsageReportOutbox(data);
				requireThat(restarted.enqueue("provider:user-a", 17, "product-a/turn-1/42", 42,
					eventId -> "{\"event_id\":\"" + eventId + "\"}"), "retainedReport").isEqualTo(report);
				requireThat(restarted.flush("provider:user-a", "access", (token, accountId, pending) ->
				{
					requireThat(pending.body(), "retryBody").isEqualTo(firstBody.get());
					return new UsageReportOutbox.Acknowledgement(pending.eventId(), 42);
				}), "retryConfirmed").isEqualTo(1);
			}
		}
	}

	/**
	 * Reusing a measurement key with different content cannot overwrite its pending report.
	 *
	 * @throws IOException for the conflicting report or data failure
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "Usage measurement changed under a retained key")
	public void rejectsChangedMeasurement() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-usage-outbox-"));
			UsageReportOutbox outbox = new UsageReportOutbox(root.resolve("outbox"));
			outbox.enqueue("provider:user-a", 17, "product-a/turn-1/42", 42,
				eventId -> "{\"event_id\":\"" + eventId + "\"}");
			outbox.enqueue("provider:user-a", 17, "product-a/turn-1/42", 43,
				eventId -> "{\"event_id\":\"" + eventId + "\"}");
		}
	}

	/**
	 * A restarted sender refuses a pending report whose own file mode permits group or other read access.
	 *
	 * @throws IOException for the exposed report or test-owned data failure
	 * @throws InterruptedException if an unexpected delivery is interrupted
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "Usage outbox record must be private: .*")
	public void rejectsBroadPendingReportPermissions() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-usage-outbox-"));
			if (!Files.getFileStore(root).supportsFileAttributeView("posix"))
				throw new SkipException("This filesystem does not support POSIX file permissions");
			Path data = root.resolve("outbox");
			UsageReportOutbox outbox = new UsageReportOutbox(data);
			UsageReportOutbox.PendingReport report = outbox.enqueue("provider:user-a", 17,
				"product-a/turn-1/42", 42, eventId -> "{\"event_id\":\"" + eventId + "\"}");
			Files.setPosixFilePermissions(data.resolve(report.eventId() + ".json"),
				PosixFilePermissions.fromString("rw-r--r--"));
			AtomicInteger sends = new AtomicInteger();
			try
			{
				new UsageReportOutbox(data).flush("provider:user-a", "access", (token, accountId, pending) ->
				{
					sends.incrementAndGet();
					return new UsageReportOutbox.Acknowledgement(pending.eventId(), 42);
				});
			}
			finally
			{
				requireThat(sends.get(), "sentExposedReport").isEqualTo(0);
			}
		}
	}
}
