package io.github.cowwoc.cat.common.optimizer.loading;

import io.github.cowwoc.cat.common.optimizer.contract.ContractJsonAccess;
import tools.jackson.databind.json.JsonMapper;

/** Provides the shared canonical mapper to durable loading records. */
public final class LoadingJson
{
	/** Prevents construction. */
	private LoadingJson()
	{
	}

	/**
	 * Returns the canonical mapper.
	 *
	 * @return configured canonical mapper
	 */
	public static JsonMapper mapper()
	{
		return ContractJsonAccess.mapper();
	}
}
