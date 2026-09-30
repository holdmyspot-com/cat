package io.github.cowwoc.cat.common.optimizer.candidate;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/** Appends and validates contiguous immutable delivery transaction phases. */
final class DeliveryJournal
{
	private static final Pattern ENTRY_NAME = Pattern.compile("([0-9]{20})\\.json");
	private static final int MAX_ENTRY_BYTES = 64 * 1024 * 1024;
	private final Path campaignDirectory;
	private final JsonMapper json;

	/**
	 * Opens a campaign-local transaction journal.
	 *
	 * @param campaignDirectory pinned campaign evidence directory
	 */
	DeliveryJournal(Path campaignDirectory)
	{
		this.campaignDirectory = Objects.requireNonNull(campaignDirectory, "campaignDirectory").toAbsolutePath().
			normalize();
		json = SnapshotJson.mapper();
	}

	/**
	 * Appends exactly the next immutable phase after validating the existing complete sequence.
	 *
	 * @param transaction next durable transaction state
	 * @throws IOException if the sequence is missing, corrupt, or conflicting
	 */
	void append(DeliveryTransaction transaction) throws IOException
	{
		Objects.requireNonNull(transaction, "transaction");
		Path directory = directory(transaction.transactionId());
		DeliveryTransaction previous = readLatestOrNull(transaction.transactionId());
		long expected = 0;
		if (previous != null)
			expected = previous.journalSequence() + 1;
		if (transaction.journalSequence() != expected)
			throw new IOException("Delivery journal sequence must be contiguous: expected " + expected);
		if (previous != null && !sameTransaction(previous, transaction))
			throw new IOException("Delivery journal identity or path plan changed between phases");
		if (previous != null && !allowsPhase(previous.phase(), transaction.phase()))
			throw new IOException("Delivery journal phase transition is invalid");
		if (previous != null && !allowsPathPhases(previous, transaction))
			throw new IOException("Delivery journal path phase transition is invalid");
		Files.createDirectories(directory);
		byte[] bytes = json.writeValueAsBytes(transaction);
		if (bytes.length > MAX_ENTRY_BYTES)
			throw new IOException("Delivery journal entry exceeds maximum bytes");
		PromptSnapshotRepository.writeOnce(entry(directory, expected), bytes);
	}

	/**
	 * Reads the latest verified phase and rejects every sequence gap or malformed entry.
	 *
	 * @param transactionId transaction identity
	 * @return latest durable state
	 * @throws IOException if no valid journal exists
	 */
	DeliveryTransaction readLatest(String transactionId) throws IOException
	{
		DeliveryTransaction latest = readLatestOrNull(transactionId);
		if (latest == null)
			throw new IOException("Delivery journal is missing: " + transactionId);
		return latest;
	}

	/**
	 * Reads a complete transaction sequence when present.
	 *
	 * @param transactionId transaction identity
	 * @return latest state or null for a new transaction
	 */
	private DeliveryTransaction readLatestOrNull(String transactionId) throws IOException
	{
		Path directory = directory(transactionId);
		if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS))
			return null;
		if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Delivery journal directory is unsafe: " + directory);
		long max = -1;
		long count = 0;
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory))
		{
			for (Path file: stream)
			{
				Matcher matcher = ENTRY_NAME.matcher(file.getFileName().toString());
				if (!matcher.matches() || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
					throw new IOException("Delivery journal contains an unsupported entry: " + file);
				long sequence;
				try
				{
					sequence = Long.parseLong(matcher.group(1));
				}
				catch (NumberFormatException e)
				{
					throw new IOException("Delivery journal sequence is invalid: " + file, e);
				}
				max = Math.max(max, sequence);
				++count;
			}
		}
		if (count == 0)
			throw new IOException("Delivery journal directory contains no phases: " + directory);
		if (max != count - 1)
			throw new IOException("Delivery journal has a noncontiguous sequence: " + transactionId);
		DeliveryTransaction previous = null;
		for (long sequence = 0; sequence <= max; ++sequence)
		{
			DeliveryTransaction current = readEntry(entry(directory, sequence));
			if (!current.transactionId().equals(transactionId) || current.journalSequence() != sequence ||
				previous != null && !sameTransaction(previous, current))
				throw new IOException("Delivery journal phase or path plan conflicts at sequence " + sequence);
			if (previous != null && !allowsPhase(previous.phase(), current.phase()))
				throw new IOException("Delivery journal phase transition is invalid at sequence " + sequence);
			if (previous != null && !allowsPathPhases(previous, current))
				throw new IOException("Delivery journal path phase transition is invalid at sequence " + sequence);
			previous = current;
		}
		return previous;
	}

	/**
	 * Parses one bounded canonical phase.
	 *
	 * @param file phase pathname
	 * @return validated transaction phase
	 */
	private DeliveryTransaction readEntry(Path file) throws IOException
	{
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_ENTRY_BYTES)
			throw new IOException("Delivery journal phase is missing or too large: " + file);
		byte[] bytes = Files.readAllBytes(file);
		try
		{
			DeliveryTransaction transaction = json.readValue(bytes, DeliveryTransaction.class);
			if (!Arrays.equals(bytes, json.writeValueAsBytes(transaction)))
				throw new IOException("Delivery journal phase is not canonical: " + file);
			return transaction;
		}
		catch (RuntimeException e)
		{
			throw new IOException("Delivery journal phase is malformed: " + file, e);
		}
	}

	/**
	 * Compares durable planned path and directory identities across phase updates.
	 *
	 * @param prior earlier phase
	 * @param next later phase
	 * @return whether the planned identities and states remain unchanged
	 */
	private static boolean sameTransaction(DeliveryTransaction prior, DeliveryTransaction next)
	{
		if (!prior.transactionId().equals(next.transactionId()) || prior.kind() != next.kind() ||
			!prior.baselineId().equals(next.baselineId()) ||
			!prior.previousAppliedCandidateId().equals(next.previousAppliedCandidateId()) ||
			!prior.selectedCandidateId().equals(next.selectedCandidateId()) ||
			!prior.confirmedRoots().equals(next.confirmedRoots()))
			return false;
		if (prior.paths().size() != next.paths().size() || prior.directories().size() != next.directories().size())
			return false;
		for (int index = 0; index < prior.paths().size(); ++index)
		{
			DeliveryPath left = prior.paths().get(index);
			DeliveryPath right = next.paths().get(index);
			if (left.ordinal() != right.ordinal() || !left.target().equals(right.target()) ||
				!left.selectedStage().equals(right.selectedStage()) ||
				!left.predecessorCapture().equals(right.predecessorCapture()) ||
				!left.rollbackCapture().equals(right.rollbackCapture()) || !left.before().equals(right.before()) ||
				!left.after().equals(right.after()))
				return false;
		}
		for (int index = 0; index < prior.directories().size(); ++index)
		{
			if (!prior.directories().get(index).relativePath().equals(next.directories().get(index).relativePath()))
				return false;
		}
		return true;
	}

	/**
	 * Validates whole-transaction phase progress before any recovery action.
	 *
	 * @param prior durable earlier phase
	 * @param next proposed later phase
	 * @return whether the transition can occur
	 */
	private static boolean allowsPhase(DeliveryPhase prior, DeliveryPhase next)
	{
		return switch (prior)
		{
			case PREPARING -> next == DeliveryPhase.PREPARING || next == DeliveryPhase.PREPARED ||
				next == DeliveryPhase.ROLLING_BACK ||
				next == DeliveryPhase.RECOVERY_REQUIRED;
			case PREPARED -> next == DeliveryPhase.APPLYING || next == DeliveryPhase.ROLLING_BACK ||
				next == DeliveryPhase.RECOVERY_REQUIRED;
			case APPLYING -> next == DeliveryPhase.APPLYING || next == DeliveryPhase.APPLIED ||
				next == DeliveryPhase.ROLLING_BACK || next == DeliveryPhase.CONCURRENT_EDIT_PRESERVED ||
				next == DeliveryPhase.RECOVERY_REQUIRED;
			case ROLLING_BACK -> next == DeliveryPhase.ROLLING_BACK || next == DeliveryPhase.ROLLED_BACK ||
				next == DeliveryPhase.CONCURRENT_EDIT_PRESERVED || next == DeliveryPhase.RECOVERY_REQUIRED;
			case APPLIED, ROLLED_BACK, CONCURRENT_EDIT_PRESERVED, RECOVERY_REQUIRED -> false;
		};
	}

	/**
	 * Checks every path's durable progress after plan identity has been confirmed.
	 *
	 * @param prior earlier transaction state
	 * @param next later transaction state
	 * @return whether each path stayed or advanced to a permitted phase
	 */
	private static boolean allowsPathPhases(DeliveryTransaction prior, DeliveryTransaction next)
	{
		for (int index = 0; index < prior.paths().size(); ++index)
		{
			if (!allowsPathPhase(prior.paths().get(index).phase(), next.paths().get(index).phase()))
				return false;
		}
		return true;
	}

	/**
	 * Checks one path's publication or capture-first rollback progression.
	 *
	 * @param prior earlier path phase
	 * @param next later path phase
	 * @return whether the transition can occur
	 */
	private static boolean allowsPathPhase(DeliveryPathPhase prior, DeliveryPathPhase next)
	{
		if (prior == next)
			return true;
		if (next == DeliveryPathPhase.RECOVERY_REQUIRED || next == DeliveryPathPhase.CONCURRENT_EDIT_PRESERVED)
			return prior != DeliveryPathPhase.PATH_ROLLED_BACK && prior != DeliveryPathPhase.RECOVERY_REQUIRED &&
				prior != DeliveryPathPhase.CONCURRENT_EDIT_PRESERVED;
		return switch (prior)
		{
			case PLANNED -> next == DeliveryPathPhase.STAGED || next == DeliveryPathPhase.PREDECESSOR_ABSENT ||
				next == DeliveryPathPhase.PREDECESSOR_CAPTURED ||
				next == DeliveryPathPhase.ROLLBACK_TARGET_ABSENT;
			case STAGED -> next == DeliveryPathPhase.PREDECESSOR_ABSENT ||
				next == DeliveryPathPhase.PREDECESSOR_CAPTURED ||
				next == DeliveryPathPhase.ROLLBACK_TARGET_ABSENT;
			case PREDECESSOR_ABSENT, PREDECESSOR_CAPTURED -> next == DeliveryPathPhase.SELECTED_ABSENT ||
				next == DeliveryPathPhase.ROLLBACK_TARGET_ABSENT;
			case SELECTED_ABSENT -> next == DeliveryPathPhase.SELECTED_LINKED ||
				next == DeliveryPathPhase.PATH_APPLIED || next == DeliveryPathPhase.ROLLBACK_TARGET_ABSENT ||
				next == DeliveryPathPhase.ROLLBACK_CAPTURED;
			case SELECTED_LINKED -> next == DeliveryPathPhase.SELECTED_DEALIASED ||
				next == DeliveryPathPhase.ROLLBACK_CAPTURED ||
				next == DeliveryPathPhase.ROLLBACK_TARGET_ABSENT;
			case SELECTED_DEALIASED -> next == DeliveryPathPhase.PATH_APPLIED ||
				next == DeliveryPathPhase.ROLLBACK_TARGET_ABSENT;
			case PATH_APPLIED -> next == DeliveryPathPhase.ROLLBACK_TARGET_ABSENT ||
				next == DeliveryPathPhase.ROLLBACK_CAPTURED;
			case ROLLBACK_TARGET_ABSENT, ROLLBACK_CAPTURED -> next == DeliveryPathPhase.RESTORE_ABSENT;
			case RESTORE_ABSENT -> next == DeliveryPathPhase.RESTORE_LINKED ||
				next == DeliveryPathPhase.PATH_ROLLED_BACK;
			case RESTORE_LINKED -> next == DeliveryPathPhase.RESTORED_DEALIASED;
			case RESTORED_DEALIASED -> next == DeliveryPathPhase.PATH_ROLLED_BACK;
			case PATH_ROLLED_BACK, CONCURRENT_EDIT_PRESERVED, RECOVERY_REQUIRED -> false;
		};
	}

	/**
	 * Resolves a transaction-owned journal directory.
	 *
	 * @param transactionId validated identifier
	 * @return campaign-local directory
	 */
	private Path directory(String transactionId)
	{
		if (transactionId == null || !transactionId.matches("[a-z0-9-]{1,100}"))
			throw new IllegalArgumentException("Delivery transaction id is invalid");
		return campaignDirectory.resolve("delivery-transactions").resolve(transactionId);
	}

	/**
	 * Resolves a numbered immutable journal phase.
	 *
	 * @param directory transaction directory
	 * @param sequence contiguous phase sequence
	 * @return immutable entry path
	 */
	private static Path entry(Path directory, long sequence)
	{
		return directory.resolve(String.format(java.util.Locale.ROOT, "%020d.json", sequence));
	}
}
