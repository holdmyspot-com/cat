package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The certification records of one target, oldest first. A certified delivery appends its record and never changes an
 * earlier one.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param records the records in the order that the deliveries appended them
 */
public record CertificationHistory(List<CertificationRecord> records)
{
	/**
	 * The history of a target that has never been certified.
	 */
	public static final CertificationHistory EMPTY = new CertificationHistory(List.of());

	/**
	 * Creates a history.
	 *
	 * @param records the records in the order that the deliveries appended them
	 * @throws NullPointerException if {@code records} is null or contains null
	 */
	public CertificationHistory
	{
		records = List.copyOf(records);
	}

	/**
	 * Returns this history with a record appended.
	 *
	 * @param record the record of a certified delivery
	 * @return a history of the earlier records followed by {@code record}
	 * @throws NullPointerException if {@code record} is null
	 */
	public CertificationHistory appended(CertificationRecord record)
	{
		requireThat(record, "record").isNotNull();
		List<CertificationRecord> appended = new ArrayList<>(records);
		appended.add(record);
		return new CertificationHistory(appended);
	}

	/**
	 * Returns the latest record of each harness. Records that name no harness count as one harness.
	 *
	 * @return the records, in the order of the history
	 */
	public List<CertificationRecord> latest()
	{
		List<CertificationRecord> latest = new ArrayList<>();
		for (int index : latestPositions())
			latest.add(records.get(index));
		return latest;
	}

	/**
	 * Returns the records that another record of the same harness followed.
	 *
	 * @return the records, in the order of the history
	 */
	public List<CertificationRecord> earlier()
	{
		Set<Integer> latest = new HashSet<>(latestPositions());
		List<CertificationRecord> earlier = new ArrayList<>();
		for (int i = 0; i < records.size(); ++i)
		{
			if (!latest.contains(i))
				earlier.add(records.get(i));
		}
		return earlier;
	}

	/**
	 * Returns the position of the latest record of each harness.
	 *
	 * @return the positions, in ascending order
	 */
	private List<Integer> latestPositions()
	{
		List<Integer> positions = new ArrayList<>();
		Set<Optional<String>> seen = new HashSet<>();
		for (int i = records.size() - 1; i >= 0; --i)
		{
			if (seen.add(records.get(i).harness()))
				positions.add(i);
		}
		return positions.reversed();
	}
}
