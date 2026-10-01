package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.ProposalSchema;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.util.List;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link ContractIdentity}, the confirmed-contract hash of handoff H-1.
 */
public final class ContractIdentityTest
{
	/**
	 * Creates the test.
	 */
	public ContractIdentityTest()
	{
	}

	/**
	 * Verifies that the identity hashes the contract's canonical JSON, so that equal contracts share it and a changed
	 * contract does not.
	 */
	@Test
	public void identityHashesCanonicalJson()
	{
		// Requirement: R-L2
		ProposalSchema schema = new ProposalSchema(JsonMapper.builder().build());
		Contract contract = ContractFixtures.contract();
		requireThat(ContractIdentity.of(schema, contract), "identity").
			isEqualTo(Sha256.of(schema.toJson(contract).getBytes(UTF_8)));
		requireThat(ContractIdentity.of(schema, ContractFixtures.contract()), "equal").
			isEqualTo(ContractIdentity.of(schema, contract));
		Contract revised = new Contract(contract.requirements(), contract.categories(), contract.profiles(),
			contract.judge(), contract.fixtures(), List.of("Only the English and French prompts are covered."));
		requireThat(ContractIdentity.of(schema, revised), "revised").
			isNotEqualTo(ContractIdentity.of(schema, contract));
	}
}
