package io.github.cowwoc.cat.common.runner;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.runner.NativeEventEvidence;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests the retained native event validation boundary.
 */
public final class NativePromptPathCollectorTest
{
	/**
	 * Creates the test fixture.
	 */
	public NativePromptPathCollectorTest()
	{
	}

	/**
	 * Supplies the exact retained bytes when their content identity matches.
	 *
	 * @throws IOException if the retained event cannot be read
	 * @throws NoSuchAlgorithmException if the JDK lacks SHA-256
	 */
	@Test
	public void readsVerifiedEvent() throws IOException, NoSuchAlgorithmException
	{
		byte[] bytes = "native event".getBytes(StandardCharsets.UTF_8);
		String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		NativeEventEvidence event = new NativeEventEvidence("session", 1, new EvidenceRef("native-events", hash));

		requireThat(NativePromptPathCollector.readIndexedEvent(event, _ -> bytes), "bytes").isEqualTo(bytes);
	}

	/**
	 * Refuses a reader that returns bytes other than the indexed native event.
	 *
	 * @throws IOException if retained evidence fails verification
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "Retained native event hash mismatch: .*")
	public void rejectsMismatchedEvent() throws IOException
	{
		NativeEventEvidence event = new NativeEventEvidence("session", 1,
			new EvidenceRef("native-events", "a".repeat(64)));
		NativePromptPathCollector.readIndexedEvent(event, _ -> "other".getBytes(StandardCharsets.UTF_8));
	}
}
