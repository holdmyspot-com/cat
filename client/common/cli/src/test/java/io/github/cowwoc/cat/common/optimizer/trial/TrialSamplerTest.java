package io.github.cowwoc.cat.common.optimizer.trial;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.contract.ConfirmedContract;
import io.github.cowwoc.cat.common.optimizer.contract.ContractProposal;
import io.github.cowwoc.cat.common.optimizer.contract.ContractTestFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.SessionMode;
import io.github.cowwoc.cat.common.optimizer.contract.TestDefinition;
import io.github.cowwoc.cat.common.optimizer.contract.VariationDimension;
import io.github.cowwoc.cat.common.optimizer.contract.VariationValue;
import java.io.IOException;
import java.io.Serial;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import org.testng.annotations.Test;

/**
 * Checks draws from a validated frozen contract before any candidate is attached.
 */
public final class TrialSamplerTest
{
	/** Creates the test fixture. */
	public TrialSamplerTest()
	{
	}

	/**
	 * Two draws may choose the same definition; formal draws use their own stream and preserve expected outcomes.
	 *
	 * @throws IOException if the contract fixture cannot be recorded
	 */
	@Test
	public void samplesDefinitionsWithReplacement() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("trial-sampler-"));
			ConfirmedContract contract = contractWithTwoDefinitions(root);
			TrialSampler sampler = new TrialSampler(contract,
				new ChosenRandom(List.of(0, 0), 0), new ChosenRandom(List.of(1), 99));

			SampledTrialInstance first = sampler.sample(EvidencePurpose.DEVELOPMENT, "first");
			SampledTrialInstance second = sampler.sample(EvidencePurpose.DEVELOPMENT, "first");
			SampledTrialInstance formal = sampler.sample(EvidencePurpose.FORMAL, "first");

			requireThat(first.definition().id(), "firstDefinition").isEqualTo("first-test");
			requireThat(second.definition().id(), "repeatedDefinition").isEqualTo("first-test");
			requireThat(formal.definition().id(), "formalDefinition").isEqualTo("first-alternative");
			requireThat(first.turns(), "firstConcreteTask").isEqualTo(List.of("Task alpha$1"));
			requireThat(formal.turns(), "formalConcreteTask").isEqualTo(List.of("Alternative beta"));
			requireThat(first.definition().expectedOutcome(), "firstExpectedOutcome").isEqualTo("Expected first");
			requireThat(formal.definition().expectedOutcome(), "formalExpectedOutcome").isEqualTo(
				"Expected alternative");
			requireThat(first.variations().getFirst().probability(), "firstFrozenProbability").isEqualTo(
				new BigDecimal("0.25"));
			requireThat(formal.variations().getFirst().probability(), "formalFrozenProbability").isEqualTo(
				new BigDecimal("0.75"));
			requireThat(first.drawId().equals(second.drawId()), "distinctDrawIdentity").isFalse();
			requireThat(first.purpose(), "developmentPurpose").isEqualTo(EvidencePurpose.DEVELOPMENT);
			requireThat(formal.purpose(), "formalPurpose").isEqualTo(EvidencePurpose.FORMAL);
			requireThat(first.generatorId(), "generatorIdentity").isEqualTo("cat-contract-sampler-v1");
		}
	}

	/**
	 * A selected value containing another token remains literal after the original template tokens are filled.
	 *
	 * @throws IOException if the contract fixture cannot be recorded
	 */
	@Test
	public void doesNotRescanSelectedVariation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("trial-sampler-literal-"));
			ConfirmedContract base = contractWithTwoDefinitions(root);
			TestDefinition first = base.tests().getFirst();
			List<VariationDimension> dimensions = List.of(
				new VariationDimension("first", "{{first}}", List.of(new VariationValue(
					"alpha {{second}}", BigDecimal.ONE))),
				new VariationDimension("second", "{{second}}", List.of(new VariationValue(
					"gamma", BigDecimal.ONE))));
			TestDefinition revised = new TestDefinition(first.id(), first.categoryId(), first.requirementIds(),
				first.applicabilityPredicate(), List.of("Task {{first}} then {{second}}"), first.expectedOutcome(),
				first.sessionMode(), first.requiredHostTools(), dimensions);
			List<TestDefinition> tests = new ArrayList<>(base.tests());
			tests.set(0, revised);
			ConfirmedContract contract = new ConfirmedContract(base.schemaVersion(), base.contractId(),
				base.sourceProposalId(), base.promptSystem(), base.requirements(), base.categories(), tests,
				base.developmentAllocationPolicy(), base.developmentReadiness(), base.developmentRoundPolicy(),
				base.formalProcedure(),
				base.evaluator(), base.limitations());
			TrialSampler sampler = new TrialSampler(contract, new ChosenRandom(List.of(0), 0),
				new ChosenRandom(List.of(), 0));

			SampledTrialInstance instance = sampler.sample(EvidencePurpose.DEVELOPMENT, "first");

			requireThat(instance.turns(), "literalSelectedValue").isEqualTo(List.of(
				"Task alpha {{second}} then gamma"));
		}
	}

	/**
	 * Validation work and an unknown category have no development or formal draw.
	 *
	 * @throws IOException if the contract fixture cannot be recorded
	 */
	@Test
	public void rejectsInvalidPurposeAndCategory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("trial-sampler-invalid-"));
			ConfirmedContract contract = contractWithTwoDefinitions(root);
			Random shared = new ChosenRandom(List.of(), 0);
			try
			{
				new TrialSampler(contract, shared, shared);
				throw new AssertionError("Development and formal draws must not share one random stream");
			}
			catch (IllegalArgumentException expected)
			{
				requireThat(expected.getMessage(), "sharedStreams").contains("separate");
			}
			TrialSampler sampler = new TrialSampler(contract,
				new ChosenRandom(List.of(), 0), new ChosenRandom(List.of(), 0));
			try
			{
				sampler.sample(EvidencePurpose.VALIDATION, "first");
				throw new AssertionError("Validation cannot enter a development or formal draw stream");
			}
			catch (IllegalArgumentException expected)
			{
				requireThat(expected.getMessage(), "validationPurpose").contains("development or formal");
			}
			try
			{
				sampler.sample(EvidencePurpose.DEVELOPMENT, "absent");
				throw new AssertionError("Unknown category cannot draw a test definition");
			}
			catch (IllegalArgumentException expected)
			{
				requireThat(expected.getMessage(), "unknownCategory").contains("category");
			}
		}
	}

	/** A sampled instance must retain a stable task and a valid draw identity. */
	@Test
	public void keepsConcreteInstanceValidAndStable()
	{
		TestDefinition definition = new TestDefinition("definition", "category", List.of("requirement"),
			"always", List.of("Task"), "Expected", SessionMode.SINGLE_TURN, List.of(), List.of());
		List<String> turns = new ArrayList<>(List.of("Task"));
		SampledTrialInstance retained = new SampledTrialInstance("draw", EvidencePurpose.DEVELOPMENT,
			"generator", "contract", definition, List.of(), turns);
		turns.set(0, "changed");
		requireThat(retained.turns(), "stableConcreteTask").isEqualTo(List.of("Task"));
		try
		{
			new SampledTrialInstance("", EvidencePurpose.DEVELOPMENT, "generator", "contract",
				definition, List.of(), List.of("Task"));
			throw new AssertionError("An unnamed draw cannot be assigned to a trial");
		}
		catch (IllegalArgumentException expected)
		{
			requireThat(expected.getMessage(), "invalidDrawId").contains("draw");
		}
	}

	/**
	 * An approved retained draw validates, while changed concrete task text is rejected.
	 *
	 * @throws IOException if the contract fixture cannot be recorded
	 */
	@Test
	public void validatesRetainedSelection() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("retained-trial-selection-"));
			ConfirmedContract contract = contractWithTwoDefinitions(root);
			SampledTrialInstance selected = new TrialSampler(contract,
				new ChosenRandom(List.of(0), 0), new ChosenRandom(List.of(), 0)).
				sample(EvidencePurpose.DEVELOPMENT, "first");
			TrialSampler.verifySelection(contract, selected);
			SampledTrialInstance changed = new SampledTrialInstance(selected.drawId(), selected.purpose(),
				selected.generatorId(), selected.contractId(), selected.definition(), selected.variations(),
				List.of("Changed task"));
			try
			{
				TrialSampler.verifySelection(contract, changed);
				throw new AssertionError("A retained task cannot differ from its approved selection");
			}
			catch (IllegalArgumentException expected)
			{
				requireThat(expected.getMessage(), "changedRetainedTask").contains("selection");
			}
		}
	}

	/**
	 * A variation distribution that no longer sums to one cannot be sampled.
	 *
	 * @throws IOException if the contract fixture cannot be recorded
	 */
	@Test
	public void rejectsInvalidVariationDistribution() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("trial-sampler-probability-"));
			ConfirmedContract base = contractWithTwoDefinitions(root);
			TestDefinition first = base.tests().getFirst();
			VariationDimension invalid = new VariationDimension("first", "{{first}}", List.of(
				new VariationValue("alpha", new BigDecimal("0.25")),
				new VariationValue("beta", new BigDecimal("0.65"))));
			TestDefinition changed = new TestDefinition(first.id(), first.categoryId(), first.requirementIds(),
				first.applicabilityPredicate(), first.turnTemplates(), first.expectedOutcome(), first.sessionMode(),
				first.requiredHostTools(), List.of(invalid));
			List<TestDefinition> tests = new ArrayList<>(base.tests());
			tests.set(0, changed);
			ConfirmedContract contract = new ConfirmedContract(base.schemaVersion(), base.contractId(),
				base.sourceProposalId(), base.promptSystem(), base.requirements(), base.categories(), tests,
				base.developmentAllocationPolicy(), base.developmentReadiness(), base.developmentRoundPolicy(),
				base.formalProcedure(),
				base.evaluator(), base.limitations());
			try
			{
				new TrialSampler(contract, new ChosenRandom(List.of(), 0), new ChosenRandom(List.of(), 0));
				throw new AssertionError("Unnormalized variation probabilities cannot produce a valid draw");
			}
			catch (IllegalArgumentException expected)
			{
				requireThat(expected.getMessage(), "invalidDistribution").contains("sum to one");
			}
		}
	}

	/**
	 * Builds a valid contract graph with two differently expected definitions in one category.
	 *
	 * @param root test-owned evidence parent
	 * @return contract graph whose definition and variation distributions are fixed
	 * @throws IOException if the proposal fixture cannot be recorded
	 */
	private static ConfirmedContract contractWithTwoDefinitions(Path root) throws IOException
	{
		ContractProposal proposal = ContractTestFixtures.recorded(root.resolve("campaign")).proposal();
		TestDefinition original = proposal.tests().getFirst();
		VariationDimension variation = new VariationDimension("first", "{{first}}", List.of(
			new VariationValue("alpha$1", new BigDecimal("0.25")),
			new VariationValue("beta", new BigDecimal("0.75"))));
		TestDefinition first = new TestDefinition(original.id(), original.categoryId(),
			original.requirementIds(), original.applicabilityPredicate(), List.of("Task {{first}}"),
			original.expectedOutcome(), original.sessionMode(), original.requiredHostTools(), List.of(variation));
		TestDefinition alternative = new TestDefinition("first-alternative", original.categoryId(),
			original.requirementIds(), original.applicabilityPredicate(), List.of("Alternative {{first}}"),
			"Expected alternative", original.sessionMode(), original.requiredHostTools(), List.of(variation));
		List<TestDefinition> tests = new ArrayList<>(proposal.tests());
		tests.set(0, first);
		tests.add(alternative);
		return new ConfirmedContract(ConfirmedContract.SCHEMA_VERSION, "confirmed-contract",
			proposal.proposalId(), proposal.promptSystem(), proposal.requirements(), proposal.categories(), tests,
			proposal.developmentAllocationPolicy(), proposal.developmentReadiness(),
			proposal.developmentRoundPolicy(), proposal.formalProcedure(),
			proposal.evaluator(), proposal.limitations());
	}

	/**
	 * Represents separate development or formal random streams with controlled definition and variation draws.
	 */
	private static final class ChosenRandom extends Random
	{
		@Serial
		private static final long serialVersionUID = 1L;

		private final transient Queue<Integer> definitions;
		private final byte variationDraw;

		/**
		 * Selects each test definition in order and one variation quantile.
		 *
		 * @param definitions definition indexes supplied by this stream
		 * @param variationDraw unsigned variation quantile below 100
		 */
		private ChosenRandom(List<Integer> definitions, int variationDraw)
		{
			this.definitions = new ArrayDeque<>(definitions);
			this.variationDraw = (byte) variationDraw;
		}

		@Override
		public int nextInt(int bound)
		{
			int selected = definitions.remove();
			if (selected >= bound)
				throw new AssertionError("Fixture selected an unavailable definition");
			return selected;
		}

		@Override
		public void nextBytes(byte[] bytes)
		{
			Arrays.fill(bytes, variationDraw);
		}
	}
}
