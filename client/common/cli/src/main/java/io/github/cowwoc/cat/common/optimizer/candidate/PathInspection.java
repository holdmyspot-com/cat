package io.github.cowwoc.cat.common.optimizer.candidate;

import java.util.Optional;

/**
 * Presence and exact-byte identity of a path.
 *
 * @param presence supported presence state
 * @param kind regular kind when present
 * @param contentSha256 exact-byte hash when present
 */
record PathInspection(DeliveryPresence presence, Optional<PromptSnapshot.FileKind> kind, Optional<String> contentSha256)
{
	/**
	 * Validates consistent path-inspection fields.
	 */
	PathInspection
	{
		if (presence == null || kind == null || contentSha256 == null)
			throw new NullPointerException("Inspection fields must be non-null");
		if (presence == DeliveryPresence.ABSENT && (kind.isPresent() || contentSha256.isPresent()))
			throw new IllegalArgumentException("Absent path cannot have kind or hash");
		if (presence == DeliveryPresence.REGULAR &&
			(kind.orElse(null) != PromptSnapshot.FileKind.REGULAR || contentSha256.isEmpty()))
		{
			throw new IllegalArgumentException("Regular path must have regular kind and hash");
		}
		contentSha256.ifPresent(LinkPublication::new);
	}
}
