package io.github.cowwoc.cat.common.optimization;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests durable local evidence-deletion receipts.
 */
public final class DeletionReceiptLedgerTest
{
	private static final String FIRST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String SECOND = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
	private static final String THIRD = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

	/**
	 * Creates the test fixture.
	 */
	public DeletionReceiptLedgerTest()
	{
	}

	/**
	 * Records two chained receipts, returns the first idempotently, and finds it without retaining the whole ledger.
	 *
	 * @throws IOException if the temporary fixture cannot be used
	 */
	@Test
	public void recordsFindsAndStreamsChainedReceipts() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("deletion-receipt-ledger-"));
			JsonMapper json = new JsonMapper();
			List<String> firstIds = List.of("00000001-assign-allowance-a001");
			DeletionReceiptLedger.Entry first = DeletionReceiptLedger.record(json, root, FIRST,
				Instant.parse("2026-09-01T12:00:00Z"), "retention elapsed", firstIds);
			DeletionReceiptLedger.Entry retry = DeletionReceiptLedger.record(json, root, FIRST,
				Instant.parse("2026-09-01T13:00:00Z"), "retention elapsed", firstIds);
			DeletionReceiptLedger.Entry second = DeletionReceiptLedger.record(json, root, SECOND,
				Instant.parse("2026-09-01T14:00:00Z"), "manual deletion",
				List.of("00000002-record-outcome-a001"));

			requireThat(retry, "idempotentRetry").isEqualTo(first);
			requireThat(second.previousFingerprint(), "chainPredecessor").isEqualTo(first.entryFingerprint());
			requireThat(DeletionReceiptLedger.find(json, root, FIRST, firstIds), "foundReceipt").contains(first);
			List<DeletionReceiptLedger.Entry> entries = new java.util.ArrayList<>();
			DeletionReceiptLedger.read(json, root, entries::add);
			requireThat(entries, "streamedReceipts").isEqualTo(List.of(first, second));
		}
	}

	/**
	 * Rejects a receipt whose persisted hash-chain predecessor was changed.
	 *
	 * @throws IOException if the temporary fixture cannot be used
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*hash chain.*")
	public void rejectsBrokenHashChain() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("deletion-receipt-ledger-"));
			JsonMapper json = new JsonMapper();
			DeletionReceiptLedger.record(json, root, FIRST, Instant.parse("2026-09-01T12:00:00Z"), "retention elapsed",
				List.of("00000001-assign-allowance-a001"));
			Path ledger = root.resolve(".cat/history/deletions.jsonl");
			String record = Files.readString(ledger).replace("\"previous_sha256\":\"ROOT\"",
				"\"previous_sha256\":\"" + THIRD + "\"");
			Files.writeString(ledger, record);

			DeletionReceiptLedger.read(json, root, ignored ->
			{
			});
		}
	}
}
