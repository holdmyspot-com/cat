package io.github.cowwoc.cat.common.optimizer.contract;

import tools.jackson.databind.json.JsonMapper;

/** Exposes C4's canonical mapper to the adjacent loading persistence boundary. */
public final class ContractJsonAccess
{
	/** Prevents construction. */
	private ContractJsonAccess()
	{
	}

	/**
	 * Returns the canonical C4 mapper.
	 *
	 * @return configured canonical mapper
	 */
	public static JsonMapper mapper()
	{
		return ContractJson.mapper();
	}
}
