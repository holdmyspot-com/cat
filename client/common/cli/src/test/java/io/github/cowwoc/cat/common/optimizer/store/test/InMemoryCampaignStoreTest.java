package io.github.cowwoc.cat.common.optimizer.store.test;

import io.github.cowwoc.cat.common.optimizer.store.CampaignStore;

/**
 * Runs the {@link CampaignStore} contract against {@link InMemoryCampaignStore}.
 */
public final class InMemoryCampaignStoreTest extends CampaignStoreContract
{
	/**
	 * Creates the test.
	 */
	public InMemoryCampaignStoreTest()
	{
	}

	@Override
	protected StoreFixture open()
	{
		InMemoryCampaignStore store = new InMemoryCampaignStore();
		return new StoreFixture()
		{
			@Override
			public CampaignStore store()
			{
				return store;
			}

			@Override
			public CampaignStore reopen()
			{
				return store;
			}

			@Override
			public void close()
			{
			}
		};
	}
}
