package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * A cell's frozen approach order for one round.
 *
 * @param approaches every available approach, in assignment order
 * @param reasons    one reason per approach in the same order
 * @param fallback   indicates whether ranking failed and declaration-order rotation was selected
 */
public record ApproachOrder(List<PromptAuthorApproach> approaches, List<String> reasons, boolean fallback)
{
	/**
	 * Creates a frozen order.
	 *
	 * @param approaches every available approach exactly once
	 * @param reasons    nonblank reasons in approach order
	 * @param fallback   indicates whether the driver exhausted ranking retries
	 * @throws NullPointerException     if an argument or element is null
	 * @throws IllegalArgumentException if an approach is missing or duplicated, or a reason is blank
	 */
	public ApproachOrder
	{
		approaches = List.copyOf(approaches);
		reasons = List.copyOf(reasons);
		requireThat(approaches.size(), "approaches.size()").isEqualTo(PromptAuthorApproach.values().length);
		requireThat(EnumSet.copyOf(approaches).size(), "distinctApproaches").isEqualTo(approaches.size());
		requireThat(reasons.size(), "reasons.size()").isEqualTo(approaches.size());
		for (String reason : reasons)
			requireThat(reason, "reason").isNotBlank();
	}

	/**
	 * Creates the declaration-order fallback, continuing the cell's earlier attempt count.
	 *
	 * @param earlier the cell's earlier attempts in this campaign
	 * @param reason  why ranking failed
	 * @return the frozen fallback
	 * @throws IllegalArgumentException if {@code earlier} is negative or {@code reason} is blank
	 */
	public static ApproachOrder fallback(int earlier, String reason)
	{
		requireThat(earlier, "earlier").isNotNegative();
		List<PromptAuthorApproach> approaches = new ArrayList<>();
		List<String> reasons = new ArrayList<>();
		PromptAuthorApproach[] available = PromptAuthorApproach.values();
		for (int index = 0; index < available.length; ++index)
		{
			approaches.add(available[(earlier + index) % available.length]);
			reasons.add(reason);
		}
		return new ApproachOrder(approaches, reasons, true);
	}
}
