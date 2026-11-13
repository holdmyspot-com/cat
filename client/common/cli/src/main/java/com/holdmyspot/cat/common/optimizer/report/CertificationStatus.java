package com.holdmyspot.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Applicability;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationHistory;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationRecord;
import com.holdmyspot.cat.common.optimizer.campaign.DeliveryEntry;
import com.holdmyspot.cat.common.optimizer.campaign.HistoryEntry;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementCondition;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementIdentity;
import com.holdmyspot.cat.common.optimizer.campaign.Outcome;
import com.holdmyspot.cat.common.optimizer.contract.CertificationScope;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The deliveries of a target and whether each harness's latest entry still applies.
 * <p>
 * Only a certification record supports a certification claim. When a harness's latest entry is another delivery's,
 * that harness's latest certification, if any, is reported as historical, naming the prompt sources that differ
 * between the two.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param latest the latest entry of each harness with its applicability, in the order of the history; not empty
 * @param earlier the entries that a later entry of the same harness followed, in the order of the history
 */
public record CertificationStatus(List<Latest> latest, List<HistoryEntry> earlier)
{
	/**
	 * Creates a status.
	 *
	 * @param latest the latest entry of each harness with its applicability, in the order of the history
	 * @param earlier the entries that a later entry of the same harness followed, in the order of the history
	 * @throws NullPointerException if any of the arguments are null or contain null
	 * @throws IllegalArgumentException if {@code latest} is empty
	 */
	public CertificationStatus
	{
		requireThat(latest, "latest").isNotEmpty();
		latest = List.copyOf(latest);
		earlier = List.copyOf(earlier);
	}

	/**
	 * Compares the latest entry of each harness with what CAT knows now.
	 *
	 * @param history the target's certification history
	 * @param currentPromptFiles the content hash of each prompt file that exists now, keyed by its path relative to
	 *   the project; it must cover every prompt file that a latest entry lists and that still exists
	 * @param known the conditions that CAT knows now
	 * @param minimumPassRate the minimum pass rate {@code p_min} that a certification must prove now, for a record
	 * @return the status
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code history} holds no entry
	 */
	public static CertificationStatus of(CertificationHistory history, Map<Path, Sha256> currentPromptFiles,
		MeasurementIdentity known, Function<CertificationRecord, BigDecimal> minimumPassRate)
	{
		requireThat(history, "history").isNotNull();
		requireThat(currentPromptFiles, "currentPromptFiles").isNotNull();
		requireThat(known, "known").isNotNull();
		requireThat(minimumPassRate, "minimumPassRate").isNotNull();
		List<Latest> latest = new ArrayList<>();
		for (HistoryEntry entry : history.latest())
		{
			Map<Path, Sha256> recordedFiles = new LinkedHashMap<>();
			for (Path path : entry.promptFiles().keySet())
			{
				Sha256 hash = currentPromptFiles.get(path);
				if (hash != null)
					recordedFiles.put(path, hash);
			}
			switch (entry)
			{
				case CertificationRecord record -> latest.add(new Latest(record,
					record.applicability(recordedFiles, known, minimumPassRate.apply(record))));
				case DeliveryEntry delivery ->
				{
					Optional<Superseded> superseded = history.latestCertification(delivery.harness()).
						map(record -> Superseded.of(record, delivery));
					latest.add(new Latest(delivery, delivery.applicability(recordedFiles, known), superseded));
				}
			}
		}
		return new CertificationStatus(latest, history.earlier());
	}

	/**
	 * Returns the target that the entries are of.
	 *
	 * @return the target, relative to the project
	 */
	public Path target()
	{
		return latest.getFirst().entry().target();
	}

	/**
	 * Returns the human summary. Each latest certification is followed by the environment that it was measured in.
	 * Any other latest entry states its label and claim, and never presents itself as a certification.
	 *
	 * @return the summary text
	 */
	public String summary()
	{
		List<String> lines = new ArrayList<>();
		lines.add("Certification history of " + target());
		for (Latest latestEntry : latest)
		{
			if (lines.size() > 1)
				lines.add("");
			switch (latestEntry.entry())
			{
				case CertificationRecord record ->
				{
					CertificationScope scope = record.scope();
					lines.add("Latest certification in " + harnessName(record) + ": scope " + scope.id() + " (" +
						scope.summary() + ")");
					ReportSummary.attempt(lines, AttemptSummary.of(record.certificationIndex(),
						Outcome.CERTIFIED.label(), latestEntry.applicability(), List.of()));
					lines.add("");
					lines.addAll(CertifiedEnvironment.of(record, latestEntry.applicability().unchecked()).lines());
				}
				case DeliveryEntry delivery -> deliveryLines(lines, delivery, latestEntry);
			}
		}
		if (!earlier.isEmpty())
		{
			lines.add("");
			lines.add("Earlier deliveries:");
			for (HistoryEntry entry : earlier)
			{
				switch (entry)
				{
					case CertificationRecord record -> lines.add("  Attempt " + record.certificationIndex() + " in " +
						harnessName(record) + ": certified, scope " + record.scope().id());
					case DeliveryEntry delivery -> lines.add("  " + attemptName(delivery) + " in " +
						harnessName(delivery) + ": " + delivery.label().label());
				}
			}
		}
		return String.join("\n", lines);
	}

	/**
	 * Describes a harness's latest entry that is not a certification.
	 *
	 * @param lines the lines to add to
	 * @param delivery the entry
	 * @param latestEntry the entry with its applicability and the certification that it superseded
	 */
	private static void deliveryLines(List<String> lines, DeliveryEntry delivery, Latest latestEntry)
	{
		lines.add("Latest delivery in " + harnessName(delivery) + ": " + delivery.label().label());
		Applicability applicability = latestEntry.applicability();
		StringBuilder status = new StringBuilder(200).append("  ").append(attemptName(delivery)).append(": ").
			append(delivery.claim());
		if (applicability.status() == Applicability.Status.HISTORICAL)
		{
			List<String> changed = new ArrayList<>();
			if (applicability.promptContentChanged())
				changed.add("prompt_content");
			for (MeasurementCondition condition : applicability.changed())
				changed.add(EnumNames.lowerCase(condition));
			status.append(" (historical; changed: ").append(String.join(", ", changed)).append(')');
		}
		else
			status.append(" (current)");
		lines.add(status.toString());
		lines.add("  Compared with incumbent " + delivery.incumbent().hex());
		if (!applicability.unchecked().isEmpty())
		{
			List<String> names = new ArrayList<>();
			for (MeasurementCondition condition : applicability.unchecked())
				names.add(condition.displayName());
			lines.add("  Not checked: " + String.join(", ", names) + ".");
		}
		lines.add("  This delivery is not a certification.");
		Optional<Superseded> superseded = latestEntry.superseded();
		if (superseded.isPresent())
		{
			CertificationRecord record = superseded.get().record();
			StringBuilder text = new StringBuilder(200).append("  Earlier certification, attempt ").
				append(record.certificationIndex()).append(" (scope ").append(record.scope().id()).append("): ");
			if (superseded.get().isHistorical())
			{
				text.append("historical, followed by this delivery; changed sources: ");
				List<String> names = new ArrayList<>();
				for (Path path : superseded.get().changedSources())
					names.add(path.toString());
				text.append(String.join(", ", names));
			}
			else
				text.append("current, because none of the sources that it certified has changed");
			lines.add(text.toString());
		}
	}

	/**
	 * Names the attempt that a delivery entry delivered.
	 *
	 * @param delivery the entry
	 * @return the name
	 */
	private static String attemptName(DeliveryEntry delivery)
	{
		if (delivery.attempt() == 0)
			return "No certification attempt";
		return "Attempt " + delivery.attempt();
	}

	/**
	 * Names the harness of an entry.
	 *
	 * @param entry the entry
	 * @return the harness, or a phrase for an entry that records none
	 */
	private static String harnessName(HistoryEntry entry)
	{
		return entry.harness().orElse("an unrecorded harness");
	}

	/**
	 * A harness's latest entry and whether it still applies.
	 *
	 * @param entry the harness's latest entry
	 * @param applicability indicates whether the entry applies to the current prompt files and to the conditions that
	 *   CAT knows
	 * @param superseded the harness's latest certification, which a later entry of another label followed, or empty if
	 *   {@code entry} is a certification record or the harness has no certification
	 */
	public record Latest(HistoryEntry entry, Applicability applicability, Optional<Superseded> superseded)
	{
		/**
		 * Creates a latest entry.
		 *
		 * @param entry the harness's latest entry
		 * @param applicability whether the entry applies to the current prompt files and to the conditions that CAT
		 *   knows
		 * @param superseded the harness's latest certification, which a later entry of another label followed, or
		 *   empty if {@code entry} is a certification record or the harness has no certification
		 * @throws NullPointerException if any of the arguments are null
		 * @throws IllegalArgumentException if a certification record supersedes a certification
		 */
		public Latest
		{
			requireThat(entry, "entry").isNotNull();
			requireThat(applicability, "applicability").isNotNull();
			requireThat(superseded, "superseded").isNotNull();
			if (entry instanceof CertificationRecord && superseded.isPresent())
				throw new IllegalArgumentException("A certification record supersedes no certification.");
		}

		/**
		 * Creates a latest entry that follows no certification of another label.
		 *
		 * @param entry the harness's latest entry
		 * @param applicability whether the entry applies to the current prompt files and to the conditions that CAT
		 *   knows
		 * @throws NullPointerException if any of the arguments are null
		 */
		public Latest(HistoryEntry entry, Applicability applicability)
		{
			this(entry, applicability, Optional.empty());
		}

		/**
		 * Indicates whether the entry supports a certification claim.
		 *
		 * @return {@code true} if the entry is a certification record
		 */
		public boolean isCertification()
		{
			return entry instanceof CertificationRecord;
		}
	}

	/**
	 * A harness's latest certification, which a later delivery of another label followed. It is historical if a source
	 * that it certified has changed, and current otherwise.
	 *
	 * @param record the certification record
	 * @param changedSources the prompt files whose content hash differs between the certification and the later
	 *   delivery, or that only one of them lists, in path order
	 */
	public record Superseded(CertificationRecord record, List<Path> changedSources)
	{
		/**
		 * Creates a superseded certification.
		 *
		 * @param record the certification record
		 * @param changedSources the prompt files whose content hash differs between the certification and the later
		 *   delivery, or that only one of them lists, in path order
		 * @throws NullPointerException if any of the arguments are null or {@code changedSources} contains null
		 */
		public Superseded
		{
			requireThat(record, "record").isNotNull();
			changedSources = List.copyOf(changedSources);
		}

		/**
		 * Indicates whether a source that the certification certified has changed.
		 *
		 * @return {@code true} if the certification no longer describes the delivered prompt
		 */
		public boolean isHistorical()
		{
			return !changedSources.isEmpty();
		}

		/**
		 * Returns a certification that a later delivery followed.
		 *
		 * @param record the certification record
		 * @param later the later delivery's entry
		 * @return the superseded certification
		 */
		static Superseded of(CertificationRecord record, DeliveryEntry later)
		{
			Set<Path> paths = new TreeSet<>(record.promptFiles().keySet());
			paths.addAll(later.promptFiles().keySet());
			List<Path> changed = new ArrayList<>();
			for (Path path : paths)
			{
				if (!Objects.equals(record.promptFiles().get(path), later.promptFiles().get(path)))
					changed.add(path);
			}
			return new Superseded(record, changed);
		}
	}
}
