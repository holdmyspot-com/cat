package io.github.cowwoc.cat.common.optimizer.store;

import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.port.CandidateOverlays;

/**
 * Runs the {@link CandidateOverlays} contract against {@link InMemoryOverlays}.
 */
public final class InMemoryOverlaysTest extends CandidateOverlaysContract
{
	/**
	 * Creates the test.
	 */
	public InMemoryOverlaysTest()
	{
	}

	@Override
	protected OverlaysFixture open(FileInventory fork)
	{
		InMemoryOverlays overlays = new InMemoryOverlays();
		return new OverlaysFixture()
		{
			@Override
			public CandidateOverlays overlays()
			{
				return overlays;
			}

			@Override
			public CandidateOverlays reopen()
			{
				return overlays;
			}

			@Override
			public void close()
			{
			}
		};
	}
}
