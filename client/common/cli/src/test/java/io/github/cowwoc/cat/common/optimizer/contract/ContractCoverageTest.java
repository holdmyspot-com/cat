package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.cat.common.optimizer.contract.ContractTestFixtures.bytes;
import static io.github.cowwoc.cat.common.optimizer.contract.ContractTestFixtures.identified;
import static io.github.cowwoc.cat.common.optimizer.contract.ContractTestFixtures.readiness;
import static io.github.cowwoc.cat.common.optimizer.contract.ContractTestFixtures.test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.candidate.DeliveryWriteAuthority;
import io.github.cowwoc.cat.common.optimizer.candidate.EditableRoot;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.node.ObjectNode;

/** Exercises the complete C4 graph, edit surface, lineage semantics, and strict schema. */
public final class ContractCoverageTest
{
	/** Creates the test fixture. */
	public ContractCoverageTest()
	{
	}

	/**
	 * A loaded read-only source remains in the contract while target write authority stays narrow.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test
	public void keepsReadOnlySourceOutsideEditRoots() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("contract-read-only-"));
			ContractProposal proposal = ContractTestFixtures.recorded(directory).proposal();
			ContractValidator.validateProposal(proposal);
			DeliveryWriteAuthority authority = new DeliveryWriteAuthority(
				proposal.promptSystem().editableRoots());
			requireThat(authority.permits(Path.of("prompt.md")), "editableTarget").isTrue();
			requireThat(authority.permits(Path.of("rules.md")), "readOnlyRule").isFalse();
			requireThat(proposal.promptSystem().sources().size(), "loadedSources").isEqualTo(2);
			PromptSystem foreign = new PromptSystem(proposal.promptSystem().targetPath(),
				proposal.promptSystem().sources(), List.of(new EditableRoot(Path.of("rules.md"),
					EditableRoot.Kind.FILE)));
			ContractProposal invalid = new ContractProposal(ContractProposal.SCHEMA_VERSION, "pending",
				proposal.loadingEvidenceId(), foreign, proposal.requirements(), proposal.categories(),
				proposal.tests(), proposal.developmentAllocationPolicy(), proposal.developmentReadiness(),
				proposal.developmentRoundPolicy(), proposal.formalProcedure(),
				proposal.evaluator(), proposal.limitations());
			try
			{
				ContractValidator.validateProposal(invalid);
				throw new AssertionError("The selected target must remain editable");
			}
			catch (IllegalArgumentException expected)
			{
				requireThat(expected.getMessage(), "message").contains("Target is outside editable roots");
			}
		}
	}

	/**
	 * A reviewed proposal must name the development category-allocation policy before confirmation can use it.
	 *
	 * @throws IOException if fixture persistence or proposal parsing fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*allocation policy.*")
	public void rejectsMissingAllocationPolicy() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("contract-allocation-policy-"));
			ContractProposal proposal = ContractTestFixtures.recorded(directory).proposal();
			ObjectNode document = (ObjectNode) ContractTestFixtures.json().readTree(bytes(proposal));
			document.remove("developmentAllocationPolicy");
			new ContractProposalParser().parse(ContractTestFixtures.json().writeValueAsBytes(document));
		}
	}

	/**
	 * Proposal parsing excludes an allocation policy that the scheduler cannot execute.
	 *
	 * @throws IOException when the unsupported policy is rejected
	 */
	@Test(expectedExceptions = IOException.class)
	public void rejectsUnknownAllocationPolicy() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("contract-unknown-policy-"));
			ContractProposal proposal = ContractTestFixtures.recorded(directory).proposal();
			ObjectNode document = (ObjectNode) ContractTestFixtures.json().readTree(bytes(proposal));
			document.put("developmentAllocationPolicy", "UNSUPPORTED");
			new ContractProposalParser().parse(ContractTestFixtures.json().writeValueAsBytes(document));
		}
	}

	/**
	 * A confirmed development round retains separate limits for diagnosis and candidate generation.
	 *
	 * @throws IOException if proposal parsing or serialization fails
	 */
	@Test
	public void retainsDevelopmentRoundLimits() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("contract-round-limits-"));
			ContractProposal proposal = ContractTestFixtures.recorded(directory).proposal();
			ObjectNode document = (ObjectNode) ContractTestFixtures.json().readTree(bytes(proposal));
			ObjectNode policy = document.putObject("developmentRoundPolicy");
			policy.put("diagnosticVariationLimitPerCategory", 4);
			policy.put("generationAttemptLimitPerCategory", 2);
			ContractProposal parsed = new ContractProposalParser().parse(
				ContractTestFixtures.json().writeValueAsBytes(document));
			ObjectNode retained = (ObjectNode) ContractTestFixtures.json().readTree(bytes(parsed));
			requireThat(retained.get("developmentRoundPolicy").get(
				"diagnosticVariationLimitPerCategory").intValue(), "diagnosticLimit").isEqualTo(4);
			requireThat(retained.get("developmentRoundPolicy").get(
				"generationAttemptLimitPerCategory").intValue(), "generationLimit").isEqualTo(2);
		}
	}

	/**
	 * A proposal without bounded development attempts cannot be confirmed.
	 *
	 * @throws IOException when the round policy is missing
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Development round policy.*")
	public void rejectsMissingDevelopmentRoundLimits() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("contract-missing-round-limits-"));
			ContractProposal proposal = ContractTestFixtures.recorded(directory).proposal();
			ObjectNode document = (ObjectNode) ContractTestFixtures.json().readTree(bytes(proposal));
			document.remove("developmentRoundPolicy");
			new ContractProposalParser().parse(ContractTestFixtures.json().writeValueAsBytes(document));
		}
	}

	/**
	 * A zero candidate-generation limit cannot support the required bounded attempt for a failing category.
	 *
	 * @throws IOException when the round policy is invalid
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Development round limits must be positive.*")
	public void rejectsZeroGenerationAttemptLimit() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("contract-zero-round-limit-"));
			ContractProposal proposal = ContractTestFixtures.recorded(directory).proposal();
			ObjectNode document = (ObjectNode) ContractTestFixtures.json().readTree(bytes(proposal));
			document.withObject("developmentRoundPolicy").put("generationAttemptLimitPerCategory", 0);
			new ContractProposalParser().parse(ContractTestFixtures.json().writeValueAsBytes(document));
		}
	}

	/**
	 * Round trips the complete proposal and rejects unknown or trailing JSON.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test
	public void roundTripsCompleteStrictSchema() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("contract-schema-"));
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.recorded(directory);
			ContractProposal parsed = new ContractProposalParser().parse(bytes(fixture.proposal()));
			requireThat(parsed, "parsedProposal").isEqualTo(fixture.proposal());
			requireThat(parsed.schemaVersion(), "schemaVersion").isEqualTo(ContractProposal.SCHEMA_VERSION);
			requireThat(parsed.requirements().size(), "requirementCount").isEqualTo(2);
			requireThat(parsed.promptSystem().sources().size(), "sourceCount").isEqualTo(2);
			requireThat(parsed.developmentAllocationPolicy(), "reviewedAllocationPolicy").isEqualTo(
				DevelopmentAllocationPolicy.BALANCED_COVERAGE_ADAPTIVE);
			requireThat(parsed.developmentAllocationPolicy().batchSize(), "reviewedBatchSize").isEqualTo(50);

			String json = new String(bytes(fixture.proposal()), StandardCharsets.UTF_8);
			assertInvalidJson(json.substring(0, json.length() - 1) + ",\"unknown\":true}");
			assertInvalidJson(json + " {}");
		}
	}

	/**
	 * Rejects every newly required graph relation at the public validator boundary.
	 *
	 * @param defect invalid graph relationship
	 * @throws IOException if fixture persistence fails
	 */
	@Test(dataProvider = "graphDefects")
	public void rejectsGraphDefects(String defect) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("contract-defect-"));
			ContractProposal base = ContractTestFixtures.recorded(directory).proposal();
			ContractProposal invalid = invalid(base, defect);
			try
			{
				ContractValidator.validateProposal(invalid);
				throw new AssertionError("Invalid graph must fail: " + defect);
			}
			catch (IllegalArgumentException e)
			{
				requireThat(e.getMessage(), "message").isNotBlank();
			}
		}
	}

	/**
	 * Supplies each invalid cross-reference and uniqueness relationship.
	 *
	 * @return invalid graph names
	 */
	@DataProvider
	public Object[][] graphDefects()
	{
		return new Object[][] {{"unobserved requirement source"}, {"undeclared placeholder"},
			{"missing placeholder"}, {"duplicate dimension id"}, {"duplicate dependence source"},
			{"uncovered requirement"}, {"category without test"}, {"invalid probability"}};
	}

	/**
	 * Records every add/remove/revise proposal edit without granting lineage authority.
	 *
	 * @param operation edit operation
	 * @param field edited field
	 * @throws IOException if fixture persistence fails
	 */
	@Test(dataProvider = "semanticEdits")
	public void recordsEverySemanticEdit(String operation, String field) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("contract-edit-"));
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.recorded(directory);
			ContractProposal edited = identified(edit(fixture.proposal(), operation, field));
			ContractValidator.validateProposal(edited);
			assertExpectedEdit(fixture.proposal(), edited, operation, field);
			StoredProposal stored = fixture.service().recordProposal(bytes(edited), fixture.loading());
			requireThat(fixture.service().loadProposal(stored.evidenceId()).proposal(), "editedProposal").
				isEqualTo(edited);
			requireThat(fixture.store().load().lineages().size(), "lineageCount").isEqualTo(0);
			requireThat(fixture.store().load().transitionCounter(), "counter").isEqualTo(0L);
			requireThat(directory.resolve("proposals").resolve(stored.evidenceId() + ".json"), "proposalArtifact").
				isRegularFile();
			requireThat(Files.exists(directory.resolve("contracts")), "contractArtifact").isFalse();
		}
	}

	/**
	 * Verifies the confirmed development target can be revised within the probability range.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test
	public void acceptsRevisedDevelopmentTarget() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("contract-readiness-"));
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.recorded(directory);
			ContractProposal base = fixture.proposal();
			DevelopmentReadiness original = base.developmentReadiness();
			DevelopmentReadiness revised = new DevelopmentReadiness(new BigDecimal("0.98"),
				original.minimumEvidence(), original.recentBatchMinimumRate(), original.recentBatchCount(),
				original.weakUpperEndpointThreshold(), original.weakIntervalConfidenceLevel(),
				original.reviewCheckpoint(), original.comparisonPolicy());
			ContractProposal edited = identified(replace(base, base.requirements(), base.categories(), base.tests(),
				revised));

			ContractValidator.validateProposal(edited);
			StoredProposal stored = fixture.service().recordProposal(bytes(edited), fixture.loading());
			requireThat(fixture.service().loadProposal(stored.evidenceId()).proposal().developmentReadiness(),
				"readiness").isEqualTo(revised);
		}
	}

	/**
	 * Verifies a development target outside the probability range is rejected at the proposal boundary.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsOutOfRangeDevelopmentTarget() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("contract-readiness-invalid-"));
			ContractProposal base = ContractTestFixtures.recorded(directory).proposal();
			DevelopmentReadiness original = base.developmentReadiness();
			DevelopmentReadiness invalid = new DevelopmentReadiness(new BigDecimal("1.01"),
				original.minimumEvidence(), original.recentBatchMinimumRate(), original.recentBatchCount(),
				original.weakUpperEndpointThreshold(), original.weakIntervalConfidenceLevel(),
				original.reviewCheckpoint(), original.comparisonPolicy());
			ContractValidator.validateProposal(replace(base, base.requirements(), base.categories(), base.tests(), invalid));
		}
	}

	/**
	 * Confirmation preserves the reviewed thresholds used to classify weak, stable, and unresolved development evidence.
	 *
	 * @throws IOException if proposal parsing or persistence fails
	 */
	@Test
	public void retainsRevisedDevelopmentConditions() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("contract-development-conditions-"));
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.recorded(directory);
			ObjectNode document = (ObjectNode) ContractTestFixtures.json().readTree(bytes(fixture.proposal()));
			ObjectNode readiness = (ObjectNode) document.get("developmentReadiness");
			readiness.put("recentBatchMinimumRate", new BigDecimal("0.97"));
			readiness.put("recentBatchCount", 3);
			readiness.put("weakUpperEndpointThreshold", new BigDecimal("0.94"));
			readiness.put("weakIntervalConfidenceLevel", new BigDecimal("0.90"));
			readiness.put("reviewCheckpoint", 250);
			ContractProposal revised = identified(new ContractProposalParser().parse(
				ContractTestFixtures.json().writeValueAsBytes(document)));

			fixture.service().recordProposal(bytes(revised), fixture.loading());
			ConfirmedContractActivation activation = fixture.service().prepareConfirmation(revised.proposalId(),
				bytes(revised));
			ConfirmedContract confirmed = fixture.service().loadConfirmedContract(activation.contractId());
			ObjectNode retained = (ObjectNode) ContractTestFixtures.json().valueToTree(confirmed.developmentReadiness());
			requireThat(retained.get("recentBatchMinimumRate").decimalValue(), "recentBatchMinimumRate").
				isEqualTo(new BigDecimal("0.97"));
			requireThat(retained.get("recentBatchCount").intValue(), "recentBatchCount").isEqualTo(3);
			requireThat(retained.get("weakUpperEndpointThreshold").decimalValue(), "weakUpperEndpointThreshold").
				isEqualTo(new BigDecimal("0.94"));
			requireThat(retained.get("weakIntervalConfidenceLevel").decimalValue(), "weakIntervalConfidenceLevel").
				isEqualTo(new BigDecimal("0.90"));
			requireThat(retained.get("reviewCheckpoint").intValue(), "reviewCheckpoint").isEqualTo(250);
		}
	}

	/**
	 * A proposal without the latest-batch stability threshold cannot supply a confirmed readiness rule.
	 *
	 * @throws IOException when the proposal omits that threshold
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*recentBatchMinimumRate.*")
	public void rejectsMissingStabilityThreshold() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("contract-missing-stability-"));
			ContractProposal proposal = ContractTestFixtures.recorded(directory).proposal();
			ObjectNode document = (ObjectNode) ContractTestFixtures.json().readTree(bytes(proposal));
			ObjectNode readiness = (ObjectNode) document.get("developmentReadiness");
			readiness.remove("recentBatchMinimumRate");
			new ContractProposalParser().parse(ContractTestFixtures.json().writeValueAsBytes(document));
		}
	}

	/**
	 * The batch stability rate must remain a probability in the reviewed proposal.
	 *
	 * @throws IOException when the proposed threshold exceeds one
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*recentBatchMinimumRate.*")
	public void rejectsInvalidStabilityThreshold() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("contract-invalid-stability-"));
			ContractProposal proposal = ContractTestFixtures.recorded(directory).proposal();
			ObjectNode document = (ObjectNode) ContractTestFixtures.json().readTree(bytes(proposal));
			ObjectNode readiness = (ObjectNode) document.get("developmentReadiness");
			readiness.put("recentBatchMinimumRate", new BigDecimal("1.01"));
			new ContractProposalParser().parse(ContractTestFixtures.json().writeValueAsBytes(document));
		}
	}

	/**
	 * Proposal parsing rejects missing or contradictory development decision settings.
	 *
	 * @param field changed readiness setting
	 * @param value invalid setting, or null to omit it
	 * @throws IOException if fixture serialization fails
	 */
	@Test(dataProvider = "invalidDevelopmentConditions")
	public void rejectsInvalidDevelopmentConditions(String field, Object value) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("contract-invalid-development-"));
			ContractProposal proposal = ContractTestFixtures.recorded(directory).proposal();
			ObjectNode document = (ObjectNode) ContractTestFixtures.json().readTree(bytes(proposal));
			ObjectNode readiness = (ObjectNode) document.get("developmentReadiness");
			if (value == null)
				readiness.remove(field);
			else
				readiness.set(field, ContractTestFixtures.json().valueToTree(value));
			try
			{
				new ContractProposalParser().parse(ContractTestFixtures.json().writeValueAsBytes(document));
				throw new AssertionError("Invalid development condition was accepted: " + field);
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "message").contains(field);
			}
		}
	}

	/**
	 * Supplies readiness settings that cannot determine a valid development decision.
	 *
	 * @return invalid field/value pairs
	 */
	@DataProvider
	public Object[][] invalidDevelopmentConditions()
	{
		return new Object[][] {{"recentBatchCount", 0}, {"weakUpperEndpointThreshold", BigDecimal.ONE},
			{"weakIntervalConfidenceLevel", BigDecimal.ONE}, {"reviewCheckpoint", 0},
			{"weakIntervalConfidenceLevel", null}, {"reviewCheckpoint", null}};
	}

	/**
	 * Verifies the proposal parser accepts the planning rate and minimum power the user must confirm.
	 *
	 * @throws IOException if fixture serialization or parsing fails
	 */
	@Test
	public void acceptsFormalPowerPlanningValues() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("contract-power-"));
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.recorded(directory);
			ContractProposal base = fixture.proposal();
			ObjectNode document = (ObjectNode) ContractTestFixtures.json().readTree(bytes(base));
			ObjectNode procedure = (ObjectNode) document.get("formalProcedure");
			procedure.put("planningRate", new BigDecimal("0.985"));
			procedure.put("minimumPower", new BigDecimal("0.85"));

			ContractProposal parsed = new ContractProposalParser().parse(ContractTestFixtures.json().writeValueAsBytes(
				document));
			requireThat(parsed.formalProcedure().threshold(), "threshold").isEqualTo(new BigDecimal("0.95"));
			requireThat(parsed.formalProcedure().planningRate(), "planningRate").isEqualTo(new BigDecimal("0.985"));
			requireThat(parsed.formalProcedure().minimumPower(), "minimumPower").isEqualTo(new BigDecimal("0.85"));
			ContractProposal edited = identified(withProcedure(base, parsed.formalProcedure()));
			StoredProposal stored = fixture.service().recordProposal(bytes(edited), fixture.loading());
			requireThat(fixture.service().loadProposal(stored.evidenceId()).proposal().formalProcedure(),
				"storedProcedure").isEqualTo(edited.formalProcedure());
		}
	}

	/**
	 * A linked target's proposed edit root may name its resolved file while retaining the requested link path.
	 * This checks proposal parsing; confirmation must separately verify the actual link against H1 evidence.
	 *
	 * @throws IOException if proposal serialization or parsing fails
	 */
	@Test
	public void acceptsResolvedEditRootForLinkedSource() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("linked-contract-shape-"));
			ContractProposal base = ContractTestFixtures.recorded(directory).proposal();
			ObjectNode document = linkedSourceProposal(base);
			ObjectNode system = (ObjectNode) document.get("promptSystem");
			ObjectNode root = (ObjectNode) system.path("editableRoots").get(0);
			root.put("path", "actual.md");

			ContractProposal parsed = new ContractProposalParser().parse(ContractTestFixtures.json().
				writeValueAsBytes(document));
			requireThat(parsed.promptSystem().targetPath(), "requestedLinkPath").isEqualTo(Path.of("prompt.md"));
			PromptSource selected = parsed.promptSystem().sources().getFirst();
			requireThat(selected.path(), "selectedLinkPath").isEqualTo(Path.of("prompt.md"));
			requireThat(selected.resolvedSourcePath(), "resolvedSourcePath").isEqualTo(Path.of("actual.md"));
			requireThat(selected.linkTarget(), "linkTarget").isEqualTo("actual.md");
			requireThat(parsed.promptSystem().editableRoots().getFirst().path(), "resolvedEditRoot").isEqualTo(
				Path.of("actual.md"));
		}
	}

	/**
	 * A root covering only the link entry does not authorize edits to the resolved file.
	 *
	 * @throws IOException if proposal serialization or parsing fails
	 */
	@Test
	public void rejectsLinkEntryAsResolvedEditAuthority() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("linked-contract-authority-"));
			ContractProposal base = ContractTestFixtures.recorded(directory).proposal();
			ObjectNode document = linkedSourceProposal(base);
			try
			{
				new ContractProposalParser().parse(ContractTestFixtures.json().writeValueAsBytes(document));
				throw new AssertionError("A link entry cannot grant resolved-file edit authority");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "linkEntryOnlyAuthority").contains("editable roots");
			}
		}
	}

	/**
	 * Gives the selected source a distinct logical link path and resolved regular-file path.
	 *
	 * @param base complete regular-source proposal
	 * @return proposal JSON with a linked selected source
	 * @throws IOException if the proposal cannot be serialized
	 */
	private static ObjectNode linkedSourceProposal(ContractProposal base) throws IOException
	{
		ObjectNode document = (ObjectNode) ContractTestFixtures.json().readTree(bytes(base));
		ObjectNode system = (ObjectNode) document.get("promptSystem");
		ObjectNode selected = (ObjectNode) system.path("sources").get(0);
		selected.put("resolvedSourcePath", "actual.md");
		selected.put("linkTarget", "actual.md");
		return document;
	}

	/**
	 * Verifies that a reviewed proposal can select the anytime Bernoulli procedure and its frozen cap.
	 *
	 * @throws IOException if fixture serialization or parsing fails
	 */
	@Test
	public void acceptsAnytimeFormalProcedure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("contract-anytime-"));
			ContractProposal base = ContractTestFixtures.recorded(directory).proposal();
			ObjectNode document = (ObjectNode) ContractTestFixtures.json().readTree(bytes(base));
			ObjectNode procedure = (ObjectNode) document.get("formalProcedure");
			procedure.put("method", "ANYTIME_BERNOULLI");
			ObjectNode assumptions = (ObjectNode) procedure.get("assumptions");
			assumptions.put("stoppingRule", "ANYTIME_FROZEN_CAP");

			ContractProposal parsed = new ContractProposalParser().parse(
				ContractTestFixtures.json().writeValueAsBytes(document));
			requireThat(parsed.formalProcedure().method().name(), "method").isEqualTo("ANYTIME_BERNOULLI");
			requireThat(parsed.formalProcedure().assumptions().stoppingRule().name(), "stoppingRule").
				isEqualTo("ANYTIME_FROZEN_CAP");
		}
	}

	/**
	 * Rejects a fixed-sample procedure when confirming a new campaign contract.
	 *
	 * @throws IOException if fixture serialization or parsing fails
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsFixedSampleProposal() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("contract-fixed-rejected-"));
			ContractProposal base = ContractTestFixtures.recorded(directory).proposal();
			FormalProcedure current = base.formalProcedure();
			FormalAssumptions assumptions = new FormalAssumptions(current.assumptions().outcomeModel(),
				StoppingRule.FIXED_N_NO_EARLY_DECISION, current.assumptions().dependenceConditions());
			FormalProcedure fixed = new FormalProcedure(FormalMethod.FIXED_SAMPLE_EXACT,
				current.threshold(), current.planningRate(), current.minimumPower(), current.allowanceFormula(),
				current.freshSessionPolicy(), assumptions);
			ContractValidator.validateProposal(withProcedure(base, fixed));
		}
	}

	/**
	 * Keeps a previously confirmed fixed-sample contract readable as historical evidence.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test
	public void acceptsHistoricalFixedConfirmedContract() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("contract-fixed-history-"));
			ContractProposal base = ContractTestFixtures.recorded(directory).proposal();
			FormalProcedure current = base.formalProcedure();
			FormalAssumptions assumptions = new FormalAssumptions(current.assumptions().outcomeModel(),
				StoppingRule.FIXED_N_NO_EARLY_DECISION, current.assumptions().dependenceConditions());
			FormalProcedure fixed = new FormalProcedure(FormalMethod.FIXED_SAMPLE_EXACT,
				current.threshold(), current.planningRate(), current.minimumPower(), current.allowanceFormula(),
				current.freshSessionPolicy(), assumptions);
			ConfirmedContract historical = new ConfirmedContract(ConfirmedContract.SCHEMA_VERSION,
				"historical-contract", base.proposalId(), base.promptSystem(), base.requirements(), base.categories(),
				base.tests(), base.developmentAllocationPolicy(), base.developmentReadiness(),
				base.developmentRoundPolicy(), fixed,
				base.evaluator(), base.limitations());

			requireThat(new ContractProposalParser().parseStored(bytes(withProcedure(base, fixed))).formalProcedure(),
				"storedProcedure").isEqualTo(fixed);
			ContractValidator.validateConfirmed(historical);
		}
	}

	/**
	 * Verifies a formal sample cannot be planned with a non-discriminating rate or unattainable power.
	 *
	 * @param planningRate proposed planning rate
	 * @param minimumPower proposed minimum power
	 * @throws IOException if fixture persistence fails
	 */
	@Test(dataProvider = "invalidPowerPlanningValues", expectedExceptions = IllegalArgumentException.class)
	public void rejectsInvalidPowerPlanningValues(BigDecimal planningRate, BigDecimal minimumPower) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("contract-power-invalid-"));
			ContractProposal base = ContractTestFixtures.recorded(directory).proposal();
			FormalProcedure original = base.formalProcedure();
			FormalProcedure invalid = new FormalProcedure(original.method(), original.threshold(), planningRate,
				minimumPower, original.allowanceFormula(), original.freshSessionPolicy(), original.assumptions());
			ContractValidator.validateProposal(withProcedure(base, invalid));
		}
	}

	/**
	 * Supplies planning values that cannot define the confirmed fixed-sample test.
	 *
	 * @return invalid planning rate and minimum power pairs
	 */
	@DataProvider
	public Object[][] invalidPowerPlanningValues()
	{
		return new Object[][] {{new BigDecimal("0.95"), new BigDecimal("0.90")},
			{new BigDecimal("0.99"), BigDecimal.ONE}, {new BigDecimal("1.01"), new BigDecimal("0.90")}};
	}

	/**
	 * Verifies the proposal cannot silently omit the sample-planning decision.
	 *
	 * @throws IOException if fixture serialization fails
	 */
	@Test
	public void rejectsMissingFormalPowerPlanningValue() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("contract-power-missing-"));
			ContractProposal base = ContractTestFixtures.recorded(directory).proposal();
			ObjectNode document = (ObjectNode) ContractTestFixtures.json().readTree(bytes(base));
			ObjectNode procedure = (ObjectNode) document.get("formalProcedure");
			procedure.remove("minimumPower");
			assertInvalidJson(new String(ContractTestFixtures.json().writeValueAsBytes(document),
				StandardCharsets.UTF_8));
		}
	}

	/**
	 * Supplies all user-editable semantic operations required by G2.
	 *
	 * @return edit matrix
	 */
	@DataProvider
	public Object[][] semanticEdits()
	{
		return new Object[][] {{"add", "requirement"}, {"remove", "requirement"}, {"revise", "requirement"},
			{"add", "category"}, {"remove", "category"}, {"revise", "category"}, {"add", "test"},
			{"remove", "test"}, {"revise", "test"}, {"revise", "applicability"}, {"add", "expected"},
			{"remove", "expected"}, {"revise", "expected"}, {"add", "variation"},
			{"remove", "variation"}, {"revise", "variation"}, {"revise", "probability"}};
	}

	/**
	 * Accepts a repeated declared placeholder across ordered multi-turn templates and makes order identity-bearing.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test
	public void validatesOrderedMultiTurnTemplates() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("contract-turns-"));
			ContractProposal base = ContractTestFixtures.recorded(directory).proposal();
			TestDefinition original = base.tests().getFirst();
			TestDefinition forward = new TestDefinition(original.id(), original.categoryId(), original.requirementIds(),
				original.applicabilityPredicate(), List.of("First {{first}}", "Second {{first}}"),
				original.expectedOutcome(), SessionMode.MULTI_TURN, original.requiredHostTools(),
				original.variationDimensions());
			TestDefinition reversed = new TestDefinition(original.id(), original.categoryId(), original.requirementIds(),
				original.applicabilityPredicate(), List.of("Second {{first}}", "First {{first}}"),
				original.expectedOutcome(), SessionMode.MULTI_TURN, original.requiredHostTools(),
				original.variationDimensions());
			ContractProposal first = identified(withTests(base, List.of(forward, base.tests().get(1))));
			ContractProposal second = identified(withTests(base, List.of(reversed, base.tests().get(1))));

			ContractValidator.validateProposal(first);
			ContractValidator.validateProposal(second);
			requireThat(first.proposalId(), "forwardId").isNotEqualTo(second.proposalId());
		}
	}

	/** Rejects one template labeled as multi-turn. */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsOneTemplateForMultiTurn()
	{
		new TestDefinition("test", "category", List.of("requirement"), "always", List.of("one"), "expected",
			SessionMode.MULTI_TURN, List.of(), List.of());
	}

	/** Rejects multiple templates labeled as single-turn. */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsMultipleTemplatesForSingleTurn()
	{
		new TestDefinition("test", "category", List.of("requirement"), "always", List.of("one", "two"), "expected",
			SessionMode.SINGLE_TURN, List.of(), List.of());
	}

	/**
	 * Builds one invalid proposal.
	 *
	 * @param base valid proposal
	 * @param defect defect name
	 * @return invalid proposal
	 */
	private static ContractProposal invalid(ContractProposal base, String defect)
	{
		return switch (defect)
		{
			case "unobserved requirement source" -> withRequirements(base, List.of(
				new Requirement("first-requirement", "First obligation",
					new SourceLocation(Path.of("unobserved.md"), "1")), base.requirements().get(1)));
			case "undeclared placeholder" -> withFirstTest(base, new TestDefinition("first-test", "first",
				List.of("first-requirement"), "always", List.of("Task {{first}} {{extra}}"), "Expected first",
				SessionMode.SINGLE_TURN, List.of("tool"), base.tests().getFirst().variationDimensions()));
			case "missing placeholder" -> withFirstTest(base, new TestDefinition("first-test", "first",
				List.of("first-requirement"), "always", List.of("Task fixed"), "Expected first",
				SessionMode.SINGLE_TURN, List.of("tool"), base.tests().getFirst().variationDimensions()));
			case "duplicate dimension id" -> withFirstTest(base, new TestDefinition("first-test", "first",
				List.of("first-requirement"), "always", List.of("{{first}} {{other}}"), "Expected first",
				SessionMode.SINGLE_TURN, List.of("tool"), List.of(
					new VariationDimension("duplicate", "{{first}}", List.of(new VariationValue("one", BigDecimal.ONE))),
					new VariationDimension("duplicate", "{{other}}", List.of(new VariationValue("two", BigDecimal.ONE))))));
			case "duplicate dependence source" -> withProcedure(base, duplicateDependenceProcedure());
			case "uncovered requirement" -> replace(base, base.requirements(), List.of(base.categories().getFirst()),
				base.tests(), readiness("first"));
			case "category without test" -> replace(base, base.requirements(), base.categories(),
				List.of(base.tests().getFirst()), base.developmentReadiness());
			case "invalid probability" -> withFirstTest(base, test("first-test", "first", "first-requirement", "always",
				List.of("Task {{first}}"), "Expected first", "first", "{{first}}",
				List.of(new VariationValue("one", new BigDecimal("0.9")))));
			default -> throw new IllegalArgumentException("Unknown defect: " + defect);
		};
	}

	/**
	 * Applies one real semantic edit.
	 *
	 * @param base valid proposal
	 * @param operation operation
	 * @param field field
	 * @return edited proposal with pending identity
	 */
	private static ContractProposal edit(ContractProposal base, String operation, String field)
	{
		return switch (field)
		{
			case "requirement" -> editRequirement(base, operation);
			case "category" -> editCategory(base, operation);
			case "test", "applicability" -> editTest(base, operation, field);
			case "expected" -> editExpected(base, operation);
			case "variation", "probability" -> editVariation(base, operation, field);
			default -> throw new IllegalArgumentException("Unknown edit field: " + field);
		};
	}

	/**
	 * Applies a requirement edit.
	 *
	 * @param base valid proposal
	 * @param operation operation
	 * @return edited proposal
	 */
	private static ContractProposal editRequirement(ContractProposal base, String operation)
	{
		if (operation.equals("remove"))
			return firstOnly(base);
		if (operation.equals("revise"))
		{
			Requirement revised = new Requirement("second-requirement", "revise-requirement",
				base.requirements().get(1).source());
			return withRequirements(base, List.of(base.requirements().getFirst(), revised));
		}
		Requirement added = new Requirement("add-requirement", "add-requirement",
			new SourceLocation(Path.of("prompt.md"), "3"));
		Category category = new Category("add-requirement-category", "added", List.of(added.id()));
		TestDefinition addedTest = test("add-requirement-test", category.id(), added.id(), "always",
			List.of("{{added}}"), "added", "added", "{{added}}",
			List.of(new VariationValue("one", BigDecimal.ONE)));
		return replace(base, append(base.requirements(), added), append(base.categories(), category),
			append(base.tests(), addedTest), readiness("first", "second", category.id()));
	}

	/**
	 * Applies a category edit.
	 *
	 * @param base valid proposal
	 * @param operation operation
	 * @return edited proposal
	 */
	private static ContractProposal editCategory(ContractProposal base, String operation)
	{
		if (operation.equals("remove"))
			return firstOnly(base);
		if (operation.equals("revise"))
		{
			Category revised = new Category("second", "revise-category", List.of("second-requirement"));
			return replace(base, base.requirements(), List.of(base.categories().getFirst(), revised), base.tests(),
				base.developmentReadiness());
		}
		Category added = new Category("add-category", "add-category", List.of("first-requirement"));
		TestDefinition addedTest = test("add-category-test", added.id(), "first-requirement", "always",
			List.of("{{added}}"), "added", "added", "{{added}}",
			List.of(new VariationValue("one", BigDecimal.ONE)));
		return replace(base, base.requirements(), append(base.categories(), added), append(base.tests(), addedTest),
			readiness("first", "second", added.id()));
	}

	/**
	 * Applies a test or applicability edit.
	 *
	 * @param base valid proposal
	 * @param operation operation
	 * @param field test or applicability
	 * @return edited proposal
	 */
	private static ContractProposal editTest(ContractProposal base, String operation, String field)
	{
		if (operation.equals("remove"))
			return firstOnly(base);
		if (operation.equals("revise") && field.equals("test"))
		{
			TestDefinition first = base.tests().getFirst();
			return withFirstTest(base, new TestDefinition(first.id(), first.categoryId(), first.requirementIds(),
				first.applicabilityPredicate(), first.turnTemplates(), "revise-test", first.sessionMode(),
				first.requiredHostTools(), first.variationDimensions()));
		}
		if (field.equals("applicability"))
		{
			TestDefinition first = base.tests().getFirst();
			return withFirstTest(base, new TestDefinition(first.id(), first.categoryId(), first.requirementIds(),
				"revise-applicability", first.turnTemplates(), first.expectedOutcome(), first.sessionMode(),
				first.requiredHostTools(), first.variationDimensions()));
		}
		TestDefinition added = test("add-test", "first", "first-requirement", "add-test", List.of("{{added}}"),
			"added", "added", "{{added}}", List.of(new VariationValue("one", BigDecimal.ONE)));
		return withTests(base, append(base.tests(), added));
	}

	/**
	 * Applies an expected-result edit.
	 *
	 * @param base valid proposal
	 * @param operation operation
	 * @return edited proposal
	 */
	private static ContractProposal editExpected(ContractProposal base, String operation)
	{
		TestDefinition first = base.tests().getFirst();
		String expected = switch (operation)
		{
			case "add" -> first.expectedOutcome() + " add-expected";
			case "remove" -> "Expected";
			case "revise" -> "revise-expected";
			default -> throw new IllegalArgumentException("Unknown expected edit: " + operation);
		};
		return withFirstTest(base, new TestDefinition(first.id(), first.categoryId(), first.requirementIds(),
			first.applicabilityPredicate(), first.turnTemplates(), expected, first.sessionMode(), first.requiredHostTools(),
			first.variationDimensions()));
	}

	/**
	 * Applies a variation edit.
	 *
	 * @param base valid proposal
	 * @param operation operation
	 * @param field variation or probability
	 * @return edited proposal
	 */
	private static ContractProposal editVariation(ContractProposal base, String operation, String field)
	{
		TestDefinition first = base.tests().getFirst();
		List<String> turns = first.turnTemplates();
		List<VariationDimension> dimensions = first.variationDimensions();
		if (field.equals("probability"))
		{
			dimensions = List.of(new VariationDimension("first", "{{first}}", List.of(
				new VariationValue("forty", new BigDecimal("0.4")),
				new VariationValue("sixty", new BigDecimal("0.6")))));
		}
		else if (operation.equals("add"))
		{
			turns = List.of("Task {{first}} {{add-variation}}");
			dimensions = append(dimensions, new VariationDimension("add-variation", "{{add-variation}}",
				List.of(new VariationValue("one", BigDecimal.ONE))));
		}
		else if (operation.equals("remove"))
		{
			turns = List.of("Task fixed");
			dimensions = List.of();
		}
		else
		{
			turns = List.of("Task {{revised}}");
			dimensions = List.of(new VariationDimension("revise-variation", "{{revised}}",
				List.of(new VariationValue("one", BigDecimal.ONE))));
		}
		return withFirstTest(base, new TestDefinition(first.id(), first.categoryId(), first.requirementIds(),
			first.applicabilityPredicate(), turns, first.expectedOutcome(), first.sessionMode(), first.requiredHostTools(),
			dimensions));
	}

	/**
	 * Asserts a named edit has the independently specified additions, removals, and preserved graph fields.
	 *
	 * @param base original proposal
	 * @param edited edited proposal
	 * @param operation operation
	 * @param field field
	 */
	private static void assertExpectedEdit(ContractProposal base, ContractProposal edited, String operation, String field)
	{
		requireThat(edited.proposalId(), "proposalId").isNotEqualTo(base.proposalId());
		requireThat(edited.loadingEvidenceId(), "loadingEvidenceId").isEqualTo(base.loadingEvidenceId());
		requireThat(edited.promptSystem(), "promptSystem").isEqualTo(base.promptSystem());
		requireThat(edited.evaluator(), "evaluator").isEqualTo(base.evaluator());
		requireThat(edited.formalProcedure(), "formalProcedure").isEqualTo(base.formalProcedure());
		requireThat(edited.limitations(), "limitations").isEqualTo(base.limitations());
		List<?> before = switch (field)
		{
			case "requirement" -> base.requirements();
			case "category" -> base.categories();
			default -> base.tests();
		};
		List<?> after = switch (field)
		{
			case "requirement" -> edited.requirements();
			case "category" -> edited.categories();
			default -> edited.tests();
		};
		requireThat(after, operation + "-" + field).isNotEqualTo(before);
		boolean collectionField = field.equals("requirement") || field.equals("category") || field.equals("test");
		if (collectionField && operation.equals("add"))
			requireThat(after.size(), "addedSize").isGreaterThan(before.size());
		if (collectionField && operation.equals("remove"))
			requireThat(after.size(), "removedSize").isLessThan(before.size());
		if (field.equals("requirement") && operation.equals("add"))
			requireThat(edited.requirements().getLast().id(), "addedRequirement").isEqualTo("add-requirement");
		if (field.equals("requirement") && operation.equals("remove"))
			requireThat(edited.requirements().getFirst().id(), "remainingRequirement").isEqualTo("first-requirement");
		if (field.equals("category") && operation.equals("add"))
			requireThat(edited.categories().getLast().id(), "addedCategory").isEqualTo("add-category");
		if (field.equals("category") && operation.equals("remove"))
			requireThat(edited.categories().getFirst().id(), "remainingCategory").isEqualTo("first");
		if (field.equals("test") && operation.equals("add"))
			requireThat(edited.tests().getLast().id(), "addedTest").isEqualTo("add-test");
		if (field.equals("test") && operation.equals("remove"))
			requireThat(edited.tests().getFirst().id(), "remainingTest").isEqualTo("first-test");
		if (field.equals("test") && operation.equals("revise"))
		{
			requireThat(edited.tests().getFirst().expectedOutcome(), "revisedTestOutcome").isEqualTo("revise-test");
			requireThat(edited.tests().getFirst().expectedOutcome(), "oldTestOutcome").isNotEqualTo(
				base.tests().getFirst().expectedOutcome());
		}
		if (field.equals("applicability"))
			requireThat(edited.tests().getFirst().applicabilityPredicate(), "applicability").isEqualTo(
				"revise-applicability");
		if (field.equals("expected"))
			requireThat(edited.tests().getFirst().expectedOutcome(), "expectedOutcome").isNotEqualTo(
				base.tests().getFirst().expectedOutcome());
		if (field.equals("variation") || field.equals("probability"))
			requireThat(edited.tests().getFirst().variationDimensions(), "variations").isNotEqualTo(
				base.tests().getFirst().variationDimensions());
	}

	/**
	 * Returns a proposal containing only the first requirement/category/test.
	 *
	 * @param base valid proposal
	 * @return reduced proposal
	 */
	private static ContractProposal firstOnly(ContractProposal base)
	{
		return replace(base, List.of(base.requirements().getFirst()), List.of(base.categories().getFirst()),
			List.of(base.tests().getFirst()), readiness("first"));
	}

	/**
	 * Copies a proposal with replacement requirements.
	 *
	 * @param base source proposal
	 * @param requirements replacement requirements
	 * @return copied proposal
	 */
	private static ContractProposal withRequirements(ContractProposal base, List<Requirement> requirements)
	{
		return replace(base, requirements, base.categories(), base.tests(), base.developmentReadiness());
	}

	/**
	 * Copies a proposal with a replacement first test.
	 *
	 * @param base source proposal
	 * @param first replacement first test
	 * @return copied proposal
	 */
	private static ContractProposal withFirstTest(ContractProposal base, TestDefinition first)
	{
		return withTests(base, List.of(first, base.tests().get(1)));
	}

	/**
	 * Copies a proposal with replacement tests.
	 *
	 * @param base source proposal
	 * @param tests replacement tests
	 * @return copied proposal
	 */
	private static ContractProposal withTests(ContractProposal base, List<TestDefinition> tests)
	{
		return replace(base, base.requirements(), base.categories(), tests, base.developmentReadiness());
	}

	/**
	 * Copies a proposal with a replacement formal procedure.
	 *
	 * @param base source proposal
	 * @param procedure replacement procedure
	 * @return copied proposal
	 */
	private static ContractProposal withProcedure(ContractProposal base, FormalProcedure procedure)
	{
		return new ContractProposal(ContractProposal.SCHEMA_VERSION, "pending", base.loadingEvidenceId(),
			base.promptSystem(), base.requirements(), base.categories(), base.tests(),
			base.developmentAllocationPolicy(), base.developmentReadiness(), base.developmentRoundPolicy(), procedure,
			base.evaluator(), base.limitations());
	}

	/**
	 * Reassembles a pending proposal.
	 *
	 * @param base source proposal
	 * @param requirements requirements
	 * @param categories categories
	 * @param tests tests
	 * @param readiness readiness
	 * @return pending proposal
	 */
	private static ContractProposal replace(ContractProposal base, List<Requirement> requirements,
		List<Category> categories, List<TestDefinition> tests, DevelopmentReadiness readiness)
	{
		return new ContractProposal(ContractProposal.SCHEMA_VERSION, "pending", base.loadingEvidenceId(),
			base.promptSystem(), requirements, categories, tests, base.developmentAllocationPolicy(), readiness,
			base.developmentRoundPolicy(), base.formalProcedure(), base.evaluator(),
			base.limitations());
	}

	/**
	 * Creates a formal procedure with a duplicated dependence source.
	 *
	 * @return invalid formal procedure
	 */
	private static FormalProcedure duplicateDependenceProcedure()
	{
		List<DependenceCondition> conditions = new ArrayList<>(ContractTestFixtures.procedure().assumptions().
			dependenceConditions());
		conditions.add(conditions.getFirst());
		return new FormalProcedure(FormalMethod.ANYTIME_BERNOULLI, new BigDecimal("0.95"), new BigDecimal("0.99"),
			new BigDecimal("0.90"), "0.05/(i*(i+1))",
			FreshSessionPolicy.FRESH_PER_LOGICAL_TRIAL, new FormalAssumptions(
				OutcomeModel.IID_BERNOULLI_WITHIN_CATEGORY, StoppingRule.ANYTIME_FROZEN_CAP, conditions));
	}

	/**
	 * Appends one element to an immutable list.
	 *
	 * @param <T> element type
	 * @param values source values
	 * @param added value to append
	 * @return copied list
	 */
	private static <T> List<T> append(List<T> values, T added)
	{
		List<T> result = new ArrayList<>(values);
		result.add(added);
		return List.copyOf(result);
	}

	/**
	 * Verifies strict parsing rejects a JSON document.
	 *
	 * @param content invalid JSON
	 */
	private static void assertInvalidJson(String content)
	{
		try
		{
			new ContractProposalParser().parse(content.getBytes(StandardCharsets.UTF_8));
			throw new AssertionError("Invalid JSON must fail");
		}
		catch (IOException e)
		{
			requireThat(e.getMessage(), "message").contains("Invalid contract proposal");
		}
	}
}
