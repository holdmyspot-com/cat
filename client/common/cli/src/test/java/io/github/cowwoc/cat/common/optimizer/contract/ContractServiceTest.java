package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.cat.common.optimizer.contract.ContractTestFixtures.bytes;
import static io.github.cowwoc.cat.common.optimizer.contract.ContractTestFixtures.identified;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.state.Campaign;
import io.github.cowwoc.cat.common.optimizer.candidate.EditableRoot;
import io.github.cowwoc.cat.common.optimizer.candidate.CandidateEvidenceRepository;
import io.github.cowwoc.cat.common.optimizer.candidate.ConfirmedEditableRoots;
import io.github.cowwoc.cat.common.optimizer.candidate.ConfirmedSourceBaseline;
import io.github.cowwoc.cat.common.optimizer.candidate.ConfirmedSourceBaselineRepository;
import io.github.cowwoc.cat.common.optimizer.candidate.ConfirmedSourceAuthorityService;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.state.CampaignLocator;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStatus;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import io.github.cowwoc.cat.common.optimizer.state.ProspectiveCampaignState;
import io.github.cowwoc.cat.common.optimizer.state.TransitionKind;
import io.github.cowwoc.cat.common.optimizer.state.TransitionRequest;
import io.github.cowwoc.cat.common.optimizer.state.UnavailableTransitionException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests evidence-only proposal operations and retired lifecycle entry points.
 */
public final class ContractServiceTest
{
	/**
	 * Creates the test fixture.
	 */
	public ContractServiceTest()
	{
	}

	/**
	 * A recorded proposal remains evidence-only across service restart.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test
	public void recordedProposalGrantsNoAuthority() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("contract-proposal-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.recorded(directory);
			StoredProposal restarted = new ContractService(CampaignStore.open(directory)).loadProposal(
				fixture.proposal().proposalId());
			requireThat(restarted, "proposal").isEqualTo(fixture.stored());
			Campaign campaign = fixture.store().load();
			requireThat(campaign.status(), "status").isEqualTo(CampaignStatus.AWAITING_CONTRACT_CONFIRMATION);
			requireThat(campaign.transitionCounter(), "counter").isEqualTo(0L);
			requireThat(campaign.lineages().size(), "lineages").isEqualTo(0);
			requireThat(Files.exists(directory.resolve("contracts")), "contractsCollection").isFalse();
		}
	}

	/**
	 * Proposal ingress accepts the supported evaluator schema and rejects another value.
	 *
	 * @throws IOException if fixture persistence or parsing fails unexpectedly
	 */
	@Test
	public void rejectsUnknownResultSchema() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("contract-evaluator-schema-"));
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.recorded(directory);
			byte[] accepted = bytes(fixture.proposal());
			ContractProposalParser parser = new ContractProposalParser();
			requireThat(parser.parse(accepted).evaluator().resultSchema(), "acceptedEvaluatorSchema").
				isEqualTo(EvaluatorResultSchema.LABEL_AND_REASON_JSON_V1);
			String acceptedText = new String(accepted, StandardCharsets.UTF_8);
			String excludedText = acceptedText.replace("\"resultSchema\":\"LABEL_AND_REASON_JSON_V1\"",
				"\"resultSchema\":\"UNSUPPORTED\"");
			requireThat(excludedText.equals(acceptedText), "schemaWasReplaced").isFalse();
			try
			{
				parser.parse(excludedText.getBytes(StandardCharsets.UTF_8));
				throw new AssertionError("Unsupported evaluator result schema was accepted");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "unsupportedEvaluatorSchema").contains("proposal");
			}
		}
	}

	/**
	 * An exact reviewed proposal yields restartable contract evidence without activating the campaign.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test
	public void preparedConfirmationRetainsExactReview() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("contract-preparation-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.recorded(directory);
			byte[] reviewed = bytes(fixture.proposal());
			ConfirmedContractActivation first = fixture.service().prepareConfirmation(
				fixture.proposal().proposalId(), reviewed);
			ContractService restarted = new ContractService(CampaignStore.open(directory));
			ConfirmedContract restored = restarted.loadConfirmedContract(first.contractId());
			requireThat(restored.sourceProposalId(), "sourceProposalId").isEqualTo(
				fixture.proposal().proposalId());
			StoredProposal sourceRevision = restarted.loadProposal(restored.sourceProposalId());
			requireThat(sourceRevision.loadingEvidenceId(), "confirmedSourceEvidenceRevision").isEqualTo(
				fixture.loading().loadingEvidenceId());
			requireThat(restored.developmentAllocationPolicy(), "confirmedAllocationPolicy").isEqualTo(
				DevelopmentAllocationPolicy.BALANCED_COVERAGE_ADAPTIVE);
			requireThat(restored.developmentAllocationPolicy().batchSize(), "confirmedBatchSize").isEqualTo(50);
			requireThat(restored.developmentRoundPolicy(), "confirmedDevelopmentRoundLimits").isEqualTo(
				fixture.proposal().developmentRoundPolicy());
			requireThat(restored.tests(), "reviewedTests").isEqualTo(fixture.proposal().tests());
			requireThat(restarted.prepareConfirmation(fixture.proposal().proposalId(), reviewed).contractId(),
				"repeatedContractId").isEqualTo(first.contractId());
			requireThat(fixture.store().load().status(), "campaignStatus").isEqualTo(
				CampaignStatus.AWAITING_CONTRACT_CONFIRMATION);
			requireThat(fixture.store().load().lineages().size(), "lineageCount").isEqualTo(0);
		}
	}

	/**
	 * A prepared contract activates once through the registered campaign and survives restart.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test
	public void confirmedClaimActivatesOnce() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("contract-activation-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			CampaignLocator locator = ProspectiveCampaignState.register(fixture.store());
			byte[] reviewed = bytes(fixture.proposal());
			fixture.service().prepareConfirmation(fixture.proposal().proposalId(), reviewed);
			ContractService restarted = new ContractService(CampaignStore.open(fixture.store().directory()));
			LineageActivation first = restarted.confirm(0, fixture.proposal().proposalId(), reviewed);
			Campaign active = fixture.store().load();
			requireThat(active.status(), "status").isEqualTo(CampaignStatus.DEVELOPING);
			requireThat(active.transitionCounter(), "counter").isEqualTo(1L);
			requireThat(active.activeLineageId(), "lineage").isEqualTo(first.lineageId());
			requireThat(active.measurementState() != null, "measurementEpoch").isTrue();
			requireThat(active.lineages().size(), "lineages").isEqualTo(1);
			requireThat(locator.readLatestConfirmedClaim(fixture.store().binding()).contractId(),
				"latestContract").isEqualTo(first.contract().contractId());
			ConfirmedEditableRoots roots = new CandidateEvidenceRepository(fixture.store().directory(),
				fixture.store().binding()).read(first.sourceAuthorityRef(), "confirmed-editable-roots",
				ConfirmedEditableRoots.class);
			requireThat(roots.confirmedContractRef().evidenceId(), "rootsContract").isEqualTo(
				first.contract().contractId());
			requireThat(roots.roots(), "editableRoots").isEqualTo(fixture.proposal().promptSystem().editableRoots());
			ConfirmedSourceBaseline baseline = new ConfirmedSourceBaselineRepository(fixture.store().directory()).load(
				roots.confirmedSourceBaselineRef());
			requireThat(baseline.orderedKnownSourceStates().size(), "knownSourceCount").isEqualTo(2);
			requireThat(baseline.orderedKnownSourceStates().get(1).path(), "readOnlySource").isEqualTo(
				Path.of("rules.md"));
			Files.writeString(roots.projectRoot().resolve("prompt.md"), "later candidate");
			requireThat(new ContractService(CampaignStore.open(fixture.store().directory())).confirm(0,
				fixture.proposal().proposalId(), reviewed), "repeatedClaim").isEqualTo(first);
			requireThat(fixture.store().load().transitionCounter(), "repeatedCounter").isEqualTo(1L);
		}
	}

	/**
	 * A restart after claim publication completes the missing source-authority index on equal retry.
	 *
	 * @throws IOException if fixture persistence or recovery fails
	 */
	@Test
	public void recoversAuthorityAfterCommittedClaim() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("contract-authority-recovery-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			byte[] reviewed = bytes(fixture.proposal());
			String contractId = fixture.service().prepareConfirmation(fixture.proposal().proposalId(), reviewed).
				contractId();
			locator.confirm(new TransitionRequest(store.binding(), 0, TransitionKind.CONFIRMED_CONTRACT,
				new EvidenceRef("contracts", contractId)));
			try
			{
				new ConfirmedSourceAuthorityService(locator, store).load(contractId);
				throw new AssertionError("A missing roots index must not authorize a candidate");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "missingIndex").contains("index is missing");
			}
			LineageActivation resumed = new ContractService(CampaignStore.open(store.directory())).confirm(0,
				fixture.proposal().proposalId(), reviewed);
			requireThat(new ConfirmedSourceAuthorityService(locator, CampaignStore.open(store.directory())).load(
				contractId), "recoveredAuthority").isEqualTo(resumed.sourceAuthorityRef());
			requireThat(store.load().transitionCounter(), "singleConfirmation").isEqualTo(1L);
		}
	}

	/**
	 * A changed selected fork source cannot advance confirmation before the original source is restored.
	 *
	 * @throws IOException if fixture persistence or source checking fails
	 */
	@Test
	public void rejectsChangedReadOnlySource() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("contract-stale-source-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			CampaignStore store = fixture.store();
			Path rule = new CampaignLocator(root.resolve("workflow")).optimizationRoot(store.binding()).
				resolve("rules.md");
			Files.writeString(rule, "changed rule");
			byte[] reviewed = bytes(fixture.proposal());
			try
			{
				fixture.service().confirm(0, fixture.proposal().proposalId(), reviewed);
				throw new AssertionError("Changed source must not activate a claim");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "changedSource").contains(
					"Campaign worktree conflicts with its retained clean fork");
			}
			requireThat(store.load().status(), "unconfirmedStatus").isEqualTo(
				CampaignStatus.AWAITING_CONTRACT_CONFIRMATION);
			requireThat(store.load().transitionCounter(), "unconfirmedCounter").isEqualTo(0L);
			Files.writeString(rule, "loaded rule");
			requireThat(fixture.service().confirm(0, fixture.proposal().proposalId(), reviewed).
				sourceAuthorityRef().collection(), "recoveredAuthority").isEqualTo("confirmed-editable-roots");
		}
	}

	/**
	 * Editing the original base after the clean fork leaves the selected confirmation source unchanged.
	 *
	 * @throws IOException if fixture persistence or source checking fails
	 */
	@Test
	public void acceptsBaseEditAfterCleanFork() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("contract-base-edit-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			Files.writeString(root.resolve("project/rules.md"), "later base edit");
			byte[] reviewed = bytes(fixture.proposal());
			fixture.service().confirm(0, fixture.proposal().proposalId(), reviewed);
			requireThat(fixture.store().load().status(), "confirmedStatus").isEqualTo(
				CampaignStatus.DEVELOPING);
			Path selectedRule = new CampaignLocator(root.resolve("workflow")).optimizationRoot(
				fixture.store().binding()).resolve("rules.md");
			requireThat(Files.readString(selectedRule), "selectedForkRule").isEqualTo("loaded rule");
		}
	}

	/**
	 * A different reviewed revision cannot be mistaken for the stored proposal.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test
	public void rejectsDifferentReviewedRevision() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("contract-review-mismatch-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.recorded(directory);
			ContractProposal different = identified(ContractTestFixtures.proposal("pending",
				fixture.loading(), "Different expected result"));
			try
			{
				fixture.service().prepareConfirmation(fixture.proposal().proposalId(), bytes(different));
				throw new AssertionError("Different reviewed revision must be rejected");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("reviewed revision");
			}
			requireThat(Files.exists(directory.resolve("contracts")), "contractsCollection").isFalse();
		}
	}

	/**
	 * The unavailable revision call rejects valid and malformed input before durable mutation.
	 *
	 * @param input valid, malformed, or absent reviewed bytes
	 * @throws IOException if fixture persistence fails
	 */
	@Test(dataProvider = "retiredInputs")
	public void revisionCallRejectsWithoutMutation(String input) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("contract-retired-")).toRealPath();
			Path directory = root.resolve("campaign");
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.recorded(directory);
			ProspectiveCampaignState.confirmedForRevision(fixture.store(), "d".repeat(64));
			Campaign before = fixture.store().load();
			Map<String, String> filesBefore = inventory(root);
			byte[] reviewed = switch (input)
			{
				case "valid" -> bytes(fixture.proposal());
				case "malformed" -> "{".getBytes(StandardCharsets.UTF_8);
				case "absent" -> null;
				default -> throw new IllegalArgumentException(input);
			};
			String proposalId = fixture.proposal().proposalId();
			if (input.equals("absent"))
				proposalId = null;
			try
			{
				fixture.service().revise(before.transitionCounter(), proposalId, reviewed);
				throw new AssertionError("Retired lifecycle call must be unavailable");
			}
			catch (UnavailableTransitionException e)
			{
					requireThat(e.getMessage(), "message").isEqualTo(
						new UnavailableTransitionException(TransitionKind.REVISED_CONTRACT, "C4").getMessage());
			}
			requireThat(fixture.store().load(), "campaign").isEqualTo(before);
			requireThat(inventory(root), "evidenceAndRegistry").isEqualTo(filesBefore);
		}
	}

	/**
	 * Supplies every input class for the unavailable revision boundary.
	 *
	 * @return retired call cases
	 */
	@DataProvider
	public Object[][] retiredInputs()
	{
		return new Object[][] {{"valid"}, {"malformed"}, {"absent"}};
	}

	/**
	 * Loading a recorded proposal rehashes its retained prompt source after restart.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test
	public void loadRejectsCorruptSourceBlob() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("contract-corrupt-source-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.recorded(directory);
			String sourceId = fixture.loading().receipts().getFirst().contentEvidenceId();
			Files.writeString(directory.resolve("prompt-source-content").resolve(sourceId + ".blob"), "corrupt");
			try
			{
				new ContractService(fixture.store()).loadProposal(fixture.proposal().proposalId());
				throw new AssertionError("Corrupt source content must fail loading");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("content address");
			}
		}
	}

	/**
	 * A changed prompt-source graph requires new loading evidence before proposal recording.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test
	public void sourceEditRequiresNewLoading() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("contract-source-edit-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.recorded(directory);
			PromptSystem changedSystem = new PromptSystem(Path.of("prompt.md"), java.util.List.of(
				new PromptSource(Path.of("prompt.md"), "f".repeat(64)),
				fixture.proposal().promptSystem().sources().get(1)), java.util.List.of(
					new EditableRoot(Path.of("prompt.md"), EditableRoot.Kind.FILE)));
			ContractProposal changed = identified(new ContractProposal(ContractProposal.SCHEMA_VERSION, "pending",
				fixture.loading().loadingEvidenceId(), changedSystem, fixture.proposal().requirements(),
				fixture.proposal().categories(), fixture.proposal().tests(),
				fixture.proposal().developmentAllocationPolicy(), fixture.proposal().developmentReadiness(),
				fixture.proposal().developmentRoundPolicy(), fixture.proposal().formalProcedure(),
				fixture.proposal().evaluator(), fixture.proposal().limitations()));
			try
			{
				fixture.service().recordProposal(bytes(changed), fixture.loading());
				throw new AssertionError("Changed source graph must require new loading evidence");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("do not exactly match");
			}
			requireThat(fixture.store().load().transitionCounter(), "counter").isEqualTo(0L);
		}
	}

	/**
	 * Captures every file and directory beneath the test workflow root before a rejected call.
	 *
	 * @param root owned workflow root
	 * @return byte-exact inventory
	 * @throws IOException if inventory cannot be read
	 */
	private static Map<String, String> inventory(Path root) throws IOException
	{
		Map<String, String> result = new TreeMap<>();
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path path : paths.toList())
			{
				String key = root.relativize(path).toString();
				String value;
				if (Files.isDirectory(path))
					value = "directory";
				else
					value = Base64.getEncoder().encodeToString(Files.readAllBytes(path));
				result.put(key, value);
			}
		}
		return result;
	}
}
