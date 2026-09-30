package io.github.cowwoc.cat.common.optimizer.state;

import java.nio.file.Path;

/** Child-JVM exact-invocation stop submitter for the process-independent owner-lock test. */
public final class CampaignStopProbe
{
	/** Prevents instantiation. */
	private CampaignStopProbe()
	{
	}

	/**
	 * Submits a stop while another process retains the scheduler owner lock.
	 *
	 * @param args campaign directory and exact invocation id
	 * @throws Exception if the exact stop cannot be durably committed
	 */
	public static void main(String[] args) throws Exception
	{
		CampaignStore store = CampaignStore.open(Path.of(args[0]));
		StopRequest stop = new CampaignLocator(store.directory().getParent()).openStopInbox(store.binding()).
			submit(store.binding(), args[1], "child-process");
		System.out.println(stop.stopRef().evidenceId());
	}
}
