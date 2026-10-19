package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Decides whether to create or resume a campaign for a project root and prompt target. At most one active or
 * cancelled campaign may exist for that pair across every session, and only the session that started it, in its
 * starting harness, may advance it.
 */
public final class Registry
{
	/**
	 * A campaign's header and the session that started the campaign.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param sessionId the ID of the harness session that started the campaign and alone runs, changes or delivers it
	 * @param header the campaign's header
	 */
	public record OwnedHeader(String sessionId, Header header)
	{
		/**
		 * Creates the value.
		 *
		 * @param sessionId the ID of the harness session that started the campaign and alone runs, changes or delivers
		 *   it
		 * @param header the campaign's header
		 * @throws NullPointerException if any argument is null
		 * @throws IllegalArgumentException if {@code sessionId} is blank
		 */
		public OwnedHeader
		{
			requireThat(sessionId, "sessionId").isNotBlank();
			requireThat(header, "header").isNotNull();
		}
	}

	/**
	 * The decision to resume, refuse or create a campaign for a root and target.
	 */
	public sealed interface Opening
	{
		/**
		 * Resume the resumable campaign that the same session started in the same harness.
		 * <p>
		 * <b>Thread Safety:</b> This class is immutable and thread-safe.
		 *
		 * @param header the campaign
		 */
		record Resume(Header header) implements Opening
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
		 * Refuse, because another session or the other harness started the resumable campaign. Nothing changes.
		 * <p>
		 * <b>Thread Safety:</b> This class is immutable and thread-safe.
		 *
		 * @param owner the campaign, which names its starting harness, and the session that started it
		 */
		record Refuse(OwnedHeader owner) implements Opening
		{
			/**
			 * Creates the result.
			 *
			 * @param owner the campaign, which names its starting harness, and the session that started it
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
	private Registry()
	{
	}

	/**
	 * Selects whether the caller should resume, refuse or create a campaign; does not change any campaign.
	 *
	 * @param headers the header of every campaign of every session
	 * @param sessionId the ID of the session that asks
	 * @param root the canonical project root
	 * @param target the canonical target path
	 * @param harness the harness that asks
	 * @return the decision
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalStateException if more than one resumable campaign exists for the root and target
	 */
	public static Opening open(List<OwnedHeader> headers, String sessionId, Path root, Path target, Harness harness)
	{
		requireThat(headers, "headers").isNotNull();
		requireThat(sessionId, "sessionId").isNotNull();
		requireThat(root, "root").isNotNull();
		requireThat(target, "target").isNotNull();
		requireThat(harness, "harness").isNotNull();
		Path normalizedRoot = root.normalize();
		Path normalizedTarget = target.normalize();
		List<OwnedHeader> resumable = new ArrayList<>();
		for (OwnedHeader owned : headers)
		{
			Header header = owned.header();
			if (header.lifecycle().isResumable() && header.root().normalize().equals(normalizedRoot) &&
				header.target().normalize().equals(normalizedTarget))
			{
				resumable.add(owned);
			}
		}
		if (resumable.size() > 1)
		{
			throw new IllegalStateException("More than one resumable campaign exists for " + target + " in " +
				root + ": " + resumable);
		}
		if (resumable.isEmpty())
			return new Opening.Create();
		OwnedHeader owned = resumable.getFirst();
		if (owned.sessionId().equals(sessionId) && owned.header().harness() == harness)
			return new Opening.Resume(owned.header());
		return new Opening.Refuse(owned);
	}

	/**
	 * Returns a target's campaign in any session: its resumable campaign, or else its campaign with the greatest
	 * identifier, which is the latest to start.
	 *
	 * @param headers the header of every campaign of every session
	 * @param root the canonical project root
	 * @param target the canonical target path
	 * @return the campaign, or empty if the target has none
	 * @throws NullPointerException if any argument is null
	 */
	public static Optional<OwnedHeader> ofTarget(List<OwnedHeader> headers, Path root, Path target)
	{
		requireThat(headers, "headers").isNotNull();
		requireThat(root, "root").isNotNull();
		requireThat(target, "target").isNotNull();
		Path normalizedRoot = root.normalize();
		Path normalizedTarget = target.normalize();
		OwnedHeader latest = null;
		for (OwnedHeader owned : headers)
		{
			Header header = owned.header();
			if (!header.root().normalize().equals(normalizedRoot) ||
				!header.target().normalize().equals(normalizedTarget))
			{
				continue;
			}
			if (header.lifecycle().isResumable())
				return Optional.of(owned);
			if (latest == null || header.id().compareTo(latest.header().id()) > 0)
				latest = owned;
		}
		return Optional.ofNullable(latest);
	}
}
