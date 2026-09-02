package io.github.cowwoc.cat.common.optimization.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.Fingerprints;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimization.OperationKind;
import io.github.cowwoc.cat.common.optimization.OperationResultKind;
import io.github.cowwoc.cat.common.optimization.OptimizationContext;
import io.github.cowwoc.cat.common.optimization.OptimizationResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/** Tests strict identity of the durable contract-extraction input. */
public final class ContractExtractionInputTest
{
	private static final String SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

	/** Creates the test fixture. */
	public ContractExtractionInputTest()
	{
	}

	/** Verifies the inventory fingerprint is the operation input fingerprint. */
	@Test
	public void exposesExtractionIdentity()
	{
		ContractExtractionInput input = new ContractExtractionInput(draft(), SHA);

		requireThat(input.operationKind(), "operationKind").isEqualTo(OperationKind.EXTRACT_CONTRACT);
		requireThat(input.inputFingerprint(), "inputFingerprint").isEqualTo(SHA);
	}

	/** Verifies a malformed inventory fingerprint cannot reach an operation handler. */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*inventoryFingerprint must be a lowercase SHA-256 value.*")
	public void rejectsMalformedFingerprint()
	{
		new ContractExtractionInput(draft(), "not-a-fingerprint");
	}

	/**
	 * Verifies a blocked extraction retains evidence and cannot advance to evaluator validation.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void retainsBlockedExtraction() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("contract-extraction-handler-"));
			JsonMapper json = new JsonMapper();
			CertificationContract.Draft inventory = draft();
			Path source = root.resolve("inventory.json");
			CertificationContractDocument.writeDraft(json, source, inventory);
			ContractExtractionInput input = new ContractExtractionInput(inventory, Fingerprints.ofFile(source));
			ContractExtractionHandler handler = new ContractExtractionHandler(value -> value, value -> value,
				(stage, value) -> value, (stage, value) -> value, (stage, value) -> value,
				(stage, value) -> value, (disagreements, value, pass) -> List.of());
			OptimizationContext context = new OptimizationContext(json, Clock.systemUTC(), root,
				root.resolve("operations/extract"), OperationKind.EXTRACT_CONTRACT,
				"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
				"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

			OptimizationResult result = handler.execute(context, input);

			requireThat(result.resultKind(), "resultKind").isEqualTo(OperationResultKind.BLOCKED);
			requireThat(result.successorKind(), "successorKind").isEqualTo(Optional.of(OperationKind.FINISH_PREPARATION));
			requireThat(Files.isRegularFile(result.evidencePath()), "evidenceExists").isTrue();
		}
	}

	/**
	 * Creates an empty source inventory suitable for input-boundary validation.
	 *
	 * @return an immutable draft
	 */
	private static CertificationContract.Draft draft()
	{
		return new CertificationContract.Draft("contract-v1", List.of(), List.of(), List.of(), List.of(), List.of(),
			List.of(), List.of(), List.of(), List.of());
	}
}
