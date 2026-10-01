package io.github.cowwoc.cat.common.optimizer.port;

import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.io.IOException;
import java.util.Optional;

/**
 * Holds the overlay of every candidate that the slice driver stages, materializes or delivers.
 * <p>
 * A campaign records each candidate only by its snapshot, the overlay's identity. The driver needs the overlay
 * itself to stage a trial's source, to prepare an author's copy, to materialize the working candidate and to deliver
 * the selected one.
 */
public interface CandidateOverlays
{
	/**
	 * Looks up the overlay of a candidate.
	 *
	 * @param snapshot the candidate's snapshot
	 * @return the overlay, or empty if no overlay was saved for {@code snapshot}
	 * @throws NullPointerException if {@code snapshot} is null
	 * @throws IOException if the overlays cannot be read
	 */
	Optional<Overlay> find(Sha256 snapshot) throws IOException;

	/**
	 * Returns the overlay of a candidate that the campaign records.
	 *
	 * @param snapshot the candidate's snapshot
	 * @return the overlay
	 * @throws NullPointerException if {@code snapshot} is null
	 * @throws IOException if the overlays cannot be read, or no overlay was saved for {@code snapshot}
	 */
	default Overlay require(Sha256 snapshot) throws IOException
	{
		Optional<Overlay> overlay = find(snapshot);
		if (overlay.isEmpty())
			throw new IOException("No overlay was saved for candidate snapshot " + snapshot.hex());
		return overlay.get();
	}

	/**
	 * Saves the overlay of a new candidate under its identity. Saving an overlay that is already saved does nothing.
	 *
	 * @param overlay the overlay
	 * @throws NullPointerException if {@code overlay} is null
	 * @throws IOException if the overlay cannot be saved
	 */
	void save(Overlay overlay) throws IOException;
}
