package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The delivery entries of one target, oldest first. Every delivery appends its entry and never changes an earlier one:
 * a certified delivery its certification record, any other delivery a labelled {@link DeliveryEntry}.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param entries the entries in the order that the deliveries appended them
 */
public record CertificationHistory(List<HistoryEntry> entries)
{
	/**
	 * The history of a target that has never been delivered.
	 */
	public static final CertificationHistory EMPTY = new CertificationHistory(List.of());

	/**
	 * Creates a history.
	 *
	 * @param entries the entries in the order that the deliveries appended them
	 * @throws NullPointerException if {@code entries} is null or contains null
	 */
	public CertificationHistory
	{
		entries = List.copyOf(entries);
	}

	/**
	 * Returns this history with an entry appended.
	 *
	 * @param entry the entry of a delivery
	 * @return a history of the earlier entries followed by {@code entry}
	 * @throws NullPointerException if {@code entry} is null
	 */
	public CertificationHistory appended(HistoryEntry entry)
	{
		requireThat(entry, "entry").isNotNull();
		List<HistoryEntry> appended = new ArrayList<>(entries);
		appended.add(entry);
		return new CertificationHistory(appended);
	}

	/**
	 * Returns the certification records of the history.
	 *
	 * @return the records, in the order of the history
	 */
	public List<CertificationRecord> records()
	{
		List<CertificationRecord> records = new ArrayList<>();
		for (HistoryEntry entry : entries)
		{
			if (entry instanceof CertificationRecord record)
				records.add(record);
		}
		return records;
	}

	/**
	 * Returns the latest entry of each harness. Entries that name no harness count as one harness.
	 *
	 * @return the entries, in the order of the history
	 */
	public List<HistoryEntry> latest()
	{
		List<HistoryEntry> latest = new ArrayList<>();
		for (int index : latestPositions())
			latest.add(entries.get(index));
		return latest;
	}

	/**
	 * Returns the entries that another entry of the same harness followed.
	 *
	 * @return the entries, in the order of the history
	 */
	public List<HistoryEntry> earlier()
	{
		Set<Integer> latest = new HashSet<>(latestPositions());
		List<HistoryEntry> earlier = new ArrayList<>();
		for (int i = 0; i < entries.size(); ++i)
		{
			if (!latest.contains(i))
				earlier.add(entries.get(i));
		}
		return earlier;
	}

	/**
	 * Returns the latest certification record of a harness.
	 *
	 * @param harness the harness, or empty for the entries that name no harness
	 * @return the record, or empty if the harness has none
	 * @throws NullPointerException if {@code harness} is null
	 */
	public Optional<CertificationRecord> latestCertification(Optional<String> harness)
	{
		requireThat(harness, "harness").isNotNull();
		for (HistoryEntry entry : entries.reversed())
		{
			if (entry instanceof CertificationRecord record && record.harness().equals(harness))
				return Optional.of(record);
		}
		return Optional.empty();
	}

	/**
	 * Returns the position of the latest entry of each harness.
	 *
	 * @return the positions, in ascending order
	 */
	private List<Integer> latestPositions()
	{
		List<Integer> positions = new ArrayList<>();
		Set<Optional<String>> seen = new HashSet<>();
		for (int i = entries.size() - 1; i >= 0; --i)
		{
			if (seen.add(entries.get(i).harness()))
				positions.add(i);
		}
		return positions.reversed();
	}
}
