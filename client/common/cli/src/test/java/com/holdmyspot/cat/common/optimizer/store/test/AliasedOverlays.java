package com.holdmyspot.cat.common.optimizer.store.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.inventory.Overlay;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.port.CandidateOverlays;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

/**
 * Candidate overlays that a real store holds, which a test can also look up under the snapshot that a campaign builder
 * fabricated for them. A campaign built by {@code Builder} records snapshots that are not the identity of any
 * overlay, while the real store finds an overlay only by its identity. The test names each fabricated snapshot
 * with {@link #put(Sha256, Overlay)}; every other lookup and save goes to the real store unchanged. The store forces
 * every file to the storage device, so an overlay that {@code put} names reaches the store when it is first looked up:
 * most tests look up few of the candidates that their fixture names.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class AliasedOverlays implements CandidateOverlays
{
	private final CandidateOverlays store;
	private final Object lock = new Object();
	private final Map<Sha256, Sha256> identities = new HashMap<>();
	private final Map<Sha256, Overlay> unsaved = new HashMap<>();

	/**
	 * Creates overlays over a store.
	 *
	 * @param store the store that holds the overlays
	 * @throws NullPointerException if {@code store} is null
	 */
	public AliasedOverlays(CandidateOverlays store)
	{
		requireThat(store, "store").isNotNull();
		this.store = store;
	}

	/**
	 * Makes an overlay findable under a snapshot that a campaign records for it.
	 *
	 * @param snapshot the snapshot that a campaign records, which may differ from the overlay's identity
	 * @param overlay the overlay
	 * @throws NullPointerException if any of the arguments are null
	 */
	public void put(Sha256 snapshot, Overlay overlay)
	{
		requireThat(snapshot, "snapshot").isNotNull();
		requireThat(overlay, "overlay").isNotNull();
		synchronized (lock)
		{
			identities.put(snapshot, overlay.identity());
			unsaved.put(overlay.identity(), overlay);
		}
	}

	/**
	 * Returns the snapshot under which an overlay is recorded.
	 *
	 * @param identity the overlay's identity
	 * @return the snapshot, or empty if no snapshot was recorded for the identity
	 */
	public Optional<Sha256> snapshotOf(Sha256 identity)
	{
		synchronized (lock)
		{
			for (Entry<Sha256, Sha256> entry : identities.entrySet())
			{
				if (entry.getValue().equals(identity))
					return Optional.of(entry.getKey());
			}
			return Optional.empty();
		}
	}

	@Override
	public Optional<Overlay> find(Sha256 snapshot) throws IOException
	{
		requireThat(snapshot, "snapshot").isNotNull();
		Sha256 identity;
		Overlay pending;
		synchronized (lock)
		{
			identity = identities.getOrDefault(snapshot, snapshot);
			pending = unsaved.remove(identity);
		}
		if (pending != null)
			store.save(pending);
		return store.find(identity);
	}

	@Override
	public void save(Overlay overlay) throws IOException
	{
		store.save(overlay);
	}
}
