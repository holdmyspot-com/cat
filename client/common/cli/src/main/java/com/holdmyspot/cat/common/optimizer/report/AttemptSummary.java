package com.holdmyspot.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Applicability;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationParameter;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementCondition;

import java.util.ArrayList;
import java.util.List;

/**
 * One certification attempt's outcome and whether it still describes the current prompt system and conditions.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param index the certification index
 * @param outcome the attempt's outcome label, or {@code null} while it runs
 * @param historical {@code true} if CAT knows of a material change since the attempt measured its candidate
 * @param changed the names of the changed conditions, including {@code PROMPT_CONTENT} if the snapshot differs,
 *   followed by the names of the certification parameters whose recorded value does not imply their current value
 * @param unchecked the conditions that the attempt recorded but the current conditions do not
 */
public record AttemptSummary(int index, String outcome, boolean historical, List<String> changed,
	List<MeasurementCondition> unchecked)
{
	/**
	 * Creates a summary.
	 *
	 * @param index the certification index
	 * @param outcome the attempt's outcome label, or {@code null} while it runs
	 * @param historical {@code true} if CAT knows of a material change since the attempt measured its candidate
	 * @param changed the names of the changed conditions and certification parameters
	 * @param unchecked the conditions that the attempt recorded but the current conditions do not
	 * @throws NullPointerException if {@code changed} or {@code unchecked} are null
	 * @throws IllegalArgumentException if {@code index} is not positive
	 */
	public AttemptSummary
	{
		requireThat(index, "index").isPositive();
		requireThat(changed, "changed").isNotNull();
		requireThat(unchecked, "unchecked").isNotNull();
		changed = List.copyOf(changed);
		unchecked = List.copyOf(unchecked);
	}

	/**
	 * Summarizes a certification attempt.
	 *
	 * @param index the certification index
	 * @param outcome the attempt's outcome label, or {@code null} while it runs
	 * @param applicability whether the attempt applies to the current prompt system and conditions
	 * @return the summary
	 * @throws NullPointerException if {@code applicability} is null
	 * @throws IllegalArgumentException if {@code index} is not positive
	 */
	public static AttemptSummary of(int index, String outcome, Applicability applicability)
	{
		requireThat(applicability, "applicability").isNotNull();
		List<String> changed = new ArrayList<>();
		if (applicability.promptContentChanged())
			changed.add("prompt_content");
		for (MeasurementCondition condition : applicability.changed())
			changed.add(EnumNames.lowerCase(condition));
		for (CertificationParameter parameter : applicability.parameters())
			changed.add(EnumNames.lowerCase(parameter));
		return new AttemptSummary(index, outcome, applicability.status() == Applicability.Status.HISTORICAL, changed,
			applicability.unchecked());
	}
}
