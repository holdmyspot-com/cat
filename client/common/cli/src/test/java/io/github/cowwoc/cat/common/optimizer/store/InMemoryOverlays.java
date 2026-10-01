package io.github.cowwoc.cat.common.optimizer.store;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.port.CandidateOverlays;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

/**
 * Candidate overlays kept in memory. A test may record an overlay under a snapshot that is not its identity, so that
 * campaign builder snapshots resolve to overlays.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class InMemoryOverlays implements CandidateOverlays
{
	private final Object lock = new Object();
	private final Map<Sha256, Overlay> overlays = new HashMap<>();

	/**
	 * Creates an empty store.
	 */
	public InMemoryOverlays()
	{
	}

	/**
	 * Records the overlay of a snapshot.
	 *
	 * @param snapshot the snapshot that a campaign records
	 * @param overlay the overlay
	 * @throws NullPointerException if any of the arguments are null
	 */
	public void put(Sha256 snapshot, Overlay overlay)
	{
		synchronized (lock)
		{
			requireThat(snapshot, "snapshot").isNotNull();
			requireThat(overlay, "overlay").isNotNull();
			overlays.put(snapshot, overlay);
		}
	}

	/**
	 * Returns the snapshot under which an overlay is recorded.
	 *
	 * @param identity the overlay's identity
	 * @return the snapshot, or empty if no recorded overlay has the identity
	 */
	public Optional<Sha256> snapshotOf(Sha256 identity)
	{
		synchronized (lock)
		{
			for (Entry<Sha256, Overlay> entry : overlays.entrySet())
			{
				if (entry.getValue().identity().equals(identity))
					return Optional.of(entry.getKey());
			}
			return Optional.empty();
		}
	}

	@Override
	public Optional<Overlay> find(Sha256 snapshot)
	{
		synchronized (lock)
		{
			requireThat(snapshot, "snapshot").isNotNull();
			return Optional.ofNullable(overlays.get(snapshot));
		}
	}

	@Override
	public void save(Overlay overlay)
	{
		synchronized (lock)
		{
			requireThat(overlay, "overlay").isNotNull();
			overlays.put(overlay.identity(), overlay);
		}
	}
}
