package io.github.cowwoc.cat.common.optimization.contract;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

/**
 * Tests the strict bounded JSON boundary for certification contracts.
 */
public final class CertificationContractDocumentTest
{
	private static final String VALID = """
		{
		  "schemaVersion":"certification-contract-v1",
		  "artifacts":[{
		    "identifier":"prompt", "projectRelativePath":"AGENTS.md",
		    "contentFingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
		  }],
		  "sourceSpans":[{
		    "identifier":"span", "artifactIdentifier":"prompt", "startPosition":0, "endPosition":4,
		    "normalizedSource":"Rule"
		  }],
		  "requirements":[{
		    "identifier":"req", "atomicProposition":"Follow the rule", "sourceSpanIdentifiers":["span"],
		    "applicabilityRuleIdentifiers":["always"], "dependencyIdentifiers":[]
		  }],
		  "applicabilityRules":[{
		    "identifier":"always", "predicateSchema":"true", "requiredEvidenceFields":["output"],
		    "outcomes":["APPLICABLE","NOT_APPLICABLE","INVALID_EVIDENCE"]
		  }],
		  "distributions":[{
		    "identifier":"dist", "samplerIdentifier":"sampler-v1",
		    "superficialVariationDimensions":["wording"], "exclusionPredicates":[],
		    "seedNamespace":"family-v1", "supportValidationReceipt":"support-v1"
		  }],
		  "families":[{
		    "identifier":"family", "parentIdentifier":null, "requirementIdentifiers":["req"],
		    "applicabilityRuleIdentifiers":["always"], "substantiveBehavior":"Rule-following requests",
		    "distributionIdentifier":"dist", "generatorIdentifier":"generator-v1",
		    "assertionIdentifiers":["assert"]
		  }],
		  "assertions":[{
		    "identifier":"assert", "requirementIdentifier":"req", "classification":"SEMANTIC",
		    "atomicPassProposition":"The output follows the rule", "requiredEvidenceFields":["output"],
		    "evaluatorProtocolIdentifier":"judge-v1",
		    "infrastructureFailureRule":"EXCLUDE_FROM_FORMAL_OUTCOMES"
		  }],
		  "evaluatorProtocolIds":["judge-v1"],
		  "promptTests":[{
		    "identifier":"test", "projectRelativePath":".cat/tests/rule.json",
		    "contentFingerprint":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
		  }]
		}
		""";
	private final ToolScope scope = new ToolScope();

	/**
	 * Creates the test fixture.
	 */
	public CertificationContractDocumentTest()
	{
	}

	/**
	 * Closes the mapper scope after the tests finish.
	 */
	@AfterClass(alwaysRun = true)
	public void closeScope()
	{
		scope.close();
	}

	/**
	 * Verifies a strict contract can be read, frozen, canonically written, and read with the same identity.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void roundTripsFrozenContract() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("certification-contract-document-"));
			Path input = Files.writeString(root.resolve("input.json"), VALID);
			Path output = root.resolve("output.json");

			CertificationContract first = CertificationContractDocument.read(scope.json(), input).contract().orElseThrow();
			CertificationContractDocument.write(scope.json(), output, first);
			CertificationContract second = CertificationContractDocument.read(scope.json(), output).contract().orElseThrow();

			requireThat(second.fingerprint(), "fingerprint").isEqualTo(first.fingerprint());
		}
	}

	/**
	 * Verifies an unknown field is rejected instead of silently becoming an unowned extension.
	 *
	 * @throws IOException for the expected schema rejection
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Invalid certification contract JSON.*unknown.*")
	public void rejectsUnknownField() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("certification-contract-document-"));
			String invalid = VALID.replace("\"promptTests\"", "\"unknown\":true,\"promptTests\"");
			Path input = Files.writeString(root.resolve("input.json"), invalid);

			CertificationContractDocument.read(scope.json(), input);
		}
	}

	/**
	 * Verifies an intermediate extraction draft can be persisted strictly before it has enough semantics to freeze.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void roundTripsIncompleteExtractionDraft() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("certification-contract-draft-"));
			CertificationContract.Draft complete = CertificationContractTest.validDraft(
				"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", false);
			CertificationContract.Draft incomplete = new CertificationContract.Draft(complete.schemaVersion(),
				complete.artifacts(), complete.sourceSpans(), complete.requirements(), complete.applicabilityRules(),
				java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), complete.promptTests());
			Path draft = root.resolve("draft.json");

			CertificationContractDocument.writeDraft(scope.json(), draft, incomplete);
			CertificationContract.Draft reloaded = CertificationContractDocument.readDraft(scope.json(), draft);

			requireThat(reloaded, "reloaded").isEqualTo(incomplete);
			requireThat(CertificationContract.freeze(reloaded).status(), "freezeStatus").isEqualTo(
				CertificationContract.Status.BLOCKED);
		}
	}
}
