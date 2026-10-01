package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.ProposalSchema;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

/**
 * Computes a confirmed contract's identity: the SHA-256 of its proposal JSON in UTF-8.
 */
public final class ContractIdentity
{
	/**
	 * Prevents instantiation.
	 */
	private ContractIdentity()
	{
	}

	/**
	 * Returns a contract's identity.
	 *
	 * @param schema the proposal schema that serializes the contract
	 * @param contract the contract
	 * @return the identity
	 * @throws NullPointerException if any argument is null
	 */
	public static Sha256 of(ProposalSchema schema, Contract contract)
	{
		requireThat(schema, "schema").isNotNull();
		requireThat(contract, "contract").isNotNull();
		return Sha256.of(schema.toJson(contract).getBytes(UTF_8));
	}
}
