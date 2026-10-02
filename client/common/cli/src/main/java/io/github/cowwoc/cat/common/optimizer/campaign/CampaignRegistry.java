package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Decides whether to create or resume a campaign for a project root and prompt target. At most one active or
 * cancelled campaign may exist for that pair, and only its starting harness may advance it.
 */
public final class CampaignRegistry
{
	/**
	 * The decision to resume, refuse or create a campaign for a root and target.
	 */
	public sealed interface Opening
	{
		/**
		 * Resume the resumable campaign that the same harness started.
		 * <p>
		 * <b>Thread Safety:</b> This class is immutable and thread-safe.
		 *
		 * @param header the campaign
		 */
		record Resume(CampaignHeader header) implements Opening
		{
			/**
			 * Creates the result.
			 *
			 * @param header the campaign
			 * @throws NullPointerException if {@code header} is null
			 */
			public Resume
			{
				requireThat(header, "header").isNotNull();
			}
		}

		/**
		 * Refuse, because the other harness started the resumable campaign. Nothing changes.
		 * <p>
		 * <b>Thread Safety:</b> This class is immutable and thread-safe.
		 *
		 * @param owner the campaign, which names its starting harness
		 */
		record Refuse(CampaignHeader owner) implements Opening
		{
			/**
			 * Creates the result.
			 *
			 * @param owner the campaign, which names its starting harness
			 * @throws NullPointerException if {@code owner} is null
			 */
			public Refuse
			{
				requireThat(owner, "owner").isNotNull();
			}
		}

		/**
		 * Create a new campaign, because no resumable campaign exists for the root and target.
		 * <p>
		 * <b>Thread Safety:</b> This class is immutable and thread-safe.
		 */
		record Create() implements Opening
		{
		}
	}

	/**
	 * Prevents instantiation.
	 */
	private CampaignRegistry()
	{
	}

	/**
	 * Selects whether the caller should resume, refuse or create a campaign; does not change any campaign.
	 *
	 * @param headers every campaign's header
	 * @param root the canonical project root
	 * @param target the canonical target path
	 * @param harness the harness that asks
	 * @return the decision
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalStateException if more than one resumable campaign exists for the root and target
	 */
	public static Opening open(List<CampaignHeader> headers, Path root, Path target, Harness harness)
	{
		requireThat(headers, "headers").isNotNull();
		requireThat(root, "root").isNotNull();
		requireThat(target, "target").isNotNull();
		requireThat(harness, "harness").isNotNull();
		Path normalizedRoot = root.normalize();
		Path normalizedTarget = target.normalize();
		List<CampaignHeader> resumable = new ArrayList<>();
		for (CampaignHeader header : headers)
		{
			if (header.lifecycle().isResumable() && header.root().normalize().equals(normalizedRoot) &&
				header.target().normalize().equals(normalizedTarget))
			{
				resumable.add(header);
			}
		}
		if (resumable.size() > 1)
		{
			throw new IllegalStateException("More than one resumable campaign exists for " + target + " in " +
				root + ": " + resumable);
		}
		if (resumable.isEmpty())
			return new Opening.Create();
		CampaignHeader header = resumable.getFirst();
		if (header.harness() == harness)
			return new Opening.Resume(header);
		return new Opening.Refuse(header);
	}
}
