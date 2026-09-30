package io.github.cowwoc.cat.common.optimizer.candidate;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.Test;

/** Tests durable transaction sequence validation before recovery file operations. */
public final class DeliveryJournalTest
{
	/** A restart reads the last contiguous phase under the same transaction id. */
	@Test
	public void reopensContiguousJournal() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaign = cleanup.add(Files.createTempDirectory("delivery-journal-"));
			DeliveryJournal writer = new DeliveryJournal(campaign);
			writer.append(transaction(0, DeliveryPhase.PREPARING));
			writer.append(transaction(1, DeliveryPhase.PREPARED));

			DeliveryTransaction restored = new DeliveryJournal(campaign).readLatest("transaction");
			requireThat(restored.phase(), "restoredPhase").isEqualTo(DeliveryPhase.PREPARED);
			requireThat(restored.journalSequence(), "restoredSequence").isEqualTo(1L);
		}
	}

	/** An attempted gap cannot become the latest recoverable transaction state. */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*sequence.*")
	public void rejectsJournalGap() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaign = cleanup.add(Files.createTempDirectory("delivery-journal-"));
			DeliveryJournal journal = new DeliveryJournal(campaign);
			journal.append(transaction(0, DeliveryPhase.PREPARING));
			journal.append(transaction(2, DeliveryPhase.PREPARED));
		}
	}

	/** A corrupt persisted phase fails before recovery chooses any file action. */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*journal.*")
	public void rejectsCorruptJournal() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaign = cleanup.add(Files.createTempDirectory("delivery-journal-"));
			new DeliveryJournal(campaign).append(transaction(0, DeliveryPhase.PREPARING));
			Path entry = campaign.resolve("delivery-transactions/transaction/00000000000000000000.json");
			Files.writeString(entry, "corrupt");
			new DeliveryJournal(campaign).readLatest("transaction");
		}
	}

	/** A canonical later phase cannot silently switch its selected candidate identity. */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*journal.*")
	public void rejectsChangedSelectedIdentity() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaign = cleanup.add(Files.createTempDirectory("delivery-journal-"));
			new DeliveryJournal(campaign).append(transaction(0, DeliveryPhase.PREPARING));
			DeliveryTransaction changed = new DeliveryTransaction(2, "transaction", 1, DeliveryKind.NO_OP,
				DeliveryPhase.PREPARED, "0".repeat(64), Optional.empty(), "2".repeat(64), List.of(
					new EditableRoot(Path.of("prompts/rule.md"), EditableRoot.Kind.FILE)), List.of(), List.of());
			Path entry = campaign.resolve("delivery-transactions/transaction/00000000000000000001.json");
			Files.write(entry, SnapshotJson.mapper().writeValueAsBytes(changed));
			new DeliveryJournal(campaign).readLatest("transaction");
		}
	}

	/** Recovery refuses a journal that regresses from a terminal applied state. */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*phase.*")
	public void rejectsPhaseRegression() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaign = cleanup.add(Files.createTempDirectory("delivery-journal-"));
			DeliveryJournal journal = new DeliveryJournal(campaign);
			journal.append(transaction(0, DeliveryPhase.PREPARING));
			journal.append(transaction(1, DeliveryPhase.PREPARED));
			journal.append(transaction(2, DeliveryPhase.APPLYING));
			journal.append(transaction(3, DeliveryPhase.APPLIED));
			Path entry = campaign.resolve("delivery-transactions/transaction/00000000000000000004.json");
			Files.write(entry, SnapshotJson.mapper().writeValueAsBytes(transaction(4, DeliveryPhase.PREPARING)));
			new DeliveryJournal(campaign).readLatest("transaction");
		}
	}

	/** A forged path phase cannot make an already applied path appear newly staged. */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*path phase.*")
	public void rejectsPathPhaseRegression() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaign = cleanup.add(Files.createTempDirectory("delivery-journal-"));
			DeliveryJournal journal = new DeliveryJournal(campaign);
			journal.append(mutating(0, DeliveryPhase.PREPARING, DeliveryPathPhase.PLANNED));
			journal.append(mutating(1, DeliveryPhase.PREPARED, DeliveryPathPhase.STAGED));
			journal.append(mutating(2, DeliveryPhase.APPLYING, DeliveryPathPhase.PREDECESSOR_CAPTURED));
			journal.append(mutating(3, DeliveryPhase.APPLYING, DeliveryPathPhase.SELECTED_ABSENT));
			journal.append(mutating(4, DeliveryPhase.APPLYING, DeliveryPathPhase.SELECTED_LINKED));
			journal.append(mutating(5, DeliveryPhase.APPLYING, DeliveryPathPhase.SELECTED_DEALIASED));
			journal.append(mutating(6, DeliveryPhase.APPLYING, DeliveryPathPhase.PATH_APPLIED));
			Path entry = campaign.resolve("delivery-transactions/transaction/00000000000000000007.json");
			Files.write(entry, SnapshotJson.mapper().writeValueAsBytes(
				mutating(7, DeliveryPhase.APPLYING, DeliveryPathPhase.STAGED)));
			new DeliveryJournal(campaign).readLatest("transaction");
		}
	}

	/**
	 * Creates one mutating phase with stable path identity.
	 *
	 * @param sequence journal sequence
	 * @param phase whole-transaction phase
	 * @param pathPhase path phase
	 * @return mutating transaction state
	 */
	private static DeliveryTransaction mutating(long sequence, DeliveryPhase phase, DeliveryPathPhase pathPhase)
	{
		Optional<String> selectedHash = Optional.empty();
		if (pathPhase == DeliveryPathPhase.SELECTED_LINKED || pathPhase == DeliveryPathPhase.SELECTED_DEALIASED ||
			pathPhase == DeliveryPathPhase.PATH_APPLIED)
			selectedHash = Optional.of("1".repeat(64));
		Path target = Path.of("prompts/rule.md");
		DeliveryPath path = new DeliveryPath(0, target, DeliveryPath.privateSibling(target, "transaction", 0, "stage"),
			DeliveryPath.privateSibling(target, "transaction", 0, "predecessor"),
			DeliveryPath.privateSibling(target, "transaction", 0, "rollback"),
			DeliveryFileState.regular("0".repeat(64)),
			DeliveryFileState.regular("1".repeat(64)), selectedHash, Optional.empty(), Optional.empty(),
			pathPhase);
		return new DeliveryTransaction(2, "transaction", sequence, DeliveryKind.MUTATING, phase,
			"0".repeat(64), Optional.empty(), "1".repeat(64), List.of(new EditableRoot(
				Path.of("prompts/rule.md"), EditableRoot.Kind.FILE)), List.of(), List.of(path));
	}

	/**
	 * Creates one no-op phase with fixed transaction identity.
	 *
	 * @param sequence journal sequence
	 * @param phase durable phase
	 * @return no-op phase
	 */
	private static DeliveryTransaction transaction(long sequence, DeliveryPhase phase)
	{
		return new DeliveryTransaction(2, "transaction", sequence, DeliveryKind.NO_OP, phase,
			"0".repeat(64), Optional.empty(), "1".repeat(64), List.of(new EditableRoot(
				Path.of("prompts/rule.md"), EditableRoot.Kind.FILE)), List.of(), List.of());
	}
}
