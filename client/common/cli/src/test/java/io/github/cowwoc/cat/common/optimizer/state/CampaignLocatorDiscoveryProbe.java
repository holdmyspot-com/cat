package io.github.cowwoc.cat.common.optimizer.state;

import java.nio.file.Path;
import java.util.List;

/** Launches a fresh JVM to discover one durable campaign from only its lookup inputs. */
public final class CampaignLocatorDiscoveryProbe
{
	/**
	 * Discovers one campaign and writes its durable summary in a line-oriented result.
	 *
	 * @param args workflow root, project root, and target-relative path
	 * @throws Exception if discovery fails
	 */
	public static void main(String[] args) throws Exception
	{
		if (args.length != 3)
			throw new IllegalArgumentException("Expected workflow root, project root, and target path");
		List<CampaignHandle> campaigns = new CampaignLocator(Path.of(args[0])).find(
			new CampaignLookupKey(Path.of(args[1]), Path.of(args[2])));
		if (campaigns.size() != 1)
			throw new IllegalStateException("Expected exactly one durable campaign");
		CampaignHandle campaign = campaigns.getFirst();
		System.out.println(campaign.campaignId());
		System.out.println(campaign.transitionCounter());
		System.out.println(campaign.status());
	}

	/** Prevents instantiation. */
	private CampaignLocatorDiscoveryProbe()
	{
	}
}
