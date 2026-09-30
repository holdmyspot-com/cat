package io.github.cowwoc.cat.common.optimizer.contract;

import java.nio.file.Path;
import java.io.IOException;

/**
 * An in-memory capability proving that {@link ContractService} revalidated and persisted a confirmed contract.
 */
public final class ConfirmedContractActivation
{
	private final Path campaignDirectory;
	private final String contractId;

	/**
	 * Creates a capability after contract validation and persistence.
	 *
	 * @param campaignDirectory campaign directory authorized by the capability
	 * @param contractId the persisted confirmed-contract identity
	 */
	ConfirmedContractActivation(Path campaignDirectory, String contractId)
	{
		if (campaignDirectory == null)
			throw new NullPointerException("campaignDirectory");
		if (contractId == null || !contractId.matches("[0-9a-f]{64}"))
			throw new IllegalArgumentException("Confirmed contract id must be a lowercase SHA-256 identity");
		try
		{
			this.campaignDirectory = campaignDirectory.toRealPath();
		}
		catch (IOException e)
		{
			throw new IllegalArgumentException("Campaign directory cannot be canonicalized: " + campaignDirectory, e);
		}
		this.contractId = contractId;
	}

	/**
	 * Tests whether this capability authorizes a campaign directory.
	 *
	 * @param directory campaign directory requesting activation
	 * @return {@code true} if the capability was minted for the directory
	 */
	public boolean authorizes(Path directory)
	{
		if (directory == null)
			throw new NullPointerException("directory");
		try
		{
			return campaignDirectory.equals(directory.toRealPath());
		}
		catch (IOException _)
		{
			return false;
		}
	}

	/**
	 * Returns the persisted confirmed-contract identity.
	 *
	 * @return the confirmed-contract identity
	 */
	public String contractId()
	{
		return contractId;
	}
}
