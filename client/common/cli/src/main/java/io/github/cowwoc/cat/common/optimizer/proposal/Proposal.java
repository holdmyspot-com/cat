package io.github.cowwoc.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.Transitions;
import io.github.cowwoc.cat.common.optimizer.campaign.ContractIdentity;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.contract.AddedTestCases;
import io.github.cowwoc.cat.common.optimizer.contract.CertificationScope;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.Part;
import io.github.cowwoc.cat.common.optimizer.contract.ProposalSchema;
import io.github.cowwoc.cat.common.optimizer.contract.Split;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTest;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfiles;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveredTest;
import io.github.cowwoc.cat.common.optimizer.execution.CapCalibrator;
import io.github.cowwoc.cat.common.optimizer.execution.PilotResult;
import io.github.cowwoc.cat.common.optimizer.port.HostLogin;
import io.github.cowwoc.cat.common.optimizer.report.CertificationStatus;
import io.github.cowwoc.cat.common.optimizer.statistics.CapCalibrationException;
import io.github.cowwoc.cat.common.optimizer.statistics.ErrorBudget;
import io.github.cowwoc.cat.common.optimizer.statistics.SignificanceLevel;
import io.github.cowwoc.cat.common.optimizer.workspace.LinkReport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.StringJoiner;
import java.util.TreeSet;

/**
 * The contract proposal that the user confirms or answers with feedback.
 * <p>
 * It combines the contract author's proposal with the items that CAT computes: the statistical procedure, the
 * symbolic-link report, the authentication and volume warning, and the minimum session count.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class Proposal
{
	/**
	 * The limitation that every confirmed contract records: no harness names the instruction files it loaded, so
	 * their loading is derived from documented rules rather than observed.
	 */
	public static final String DERIVED_LOADING_LIMIT = "Prompt files that no harness event reports loading were " +
		"identified from documented loading rules rather than observed, so trials can load a different set of " +
		"prompt files than production if the harness does not follow those rules.";
	/**
	 * The limitation that every confirmed contract records: an emptied prompt file stays where it was, so a file that
	 * references it still does.
	 */
	public static final String CONTROL_SOURCE_LIMIT = "Control trials empty every target prompt file but leave it " +
		"in place. A file that references an emptied file still references it, so a control trial may still read " +
		"it and find nothing.";
	/**
	 * The number of certification attempts whose caps the proposal shows.
	 */
	private static final int ATTEMPTS_SHOWN = 3;
	/**
	 * The fewest distinct expected outcomes that each part of a test needs, so that development and held-out trials
	 * each vary the test's content.
	 */
	static final int MINIMUM_OUTCOMES = 2;
	private final DerivedProposal derived;
	private final Contract contract;
	private final ProposalSchema schema;
	private final LinkReport links;
	private final SortedSet<Path> controlFiles;
	private final HostLogin login;
	private final int ceiling;
	private final Map<CertificationScope, Campaign> previews;
	private final List<String> caps;
	private final CertificationStatus incumbent;
	private final Map<String, PilotResult> pilots;
	private final Map<String, DeliveredTest> delivered;

	/**
	 * Creates a proposal.
	 *
	 * @param derived the contract author's proposal
	 * @param contract the contract that confirmation freezes once the user chooses its certification scope
	 * @param schema the proposal schema, which identifies the contract
	 * @param links the symbolic links
	 * @param controlFiles the target prompt files that the control source empties, in path order
	 * @param login how the host's harness account is billed
	 * @param ceiling the campaign's concurrency ceiling
	 * @param previews the campaign as it would be after confirmation with each certification scope that the user
	 *   may choose, in choice order; empty while an inventory model is unranked
	 * @param caps the level and cap of each of the first attempts, as text
	 * @param incumbent the delivered certification of the target, or {@code null} if the target has none
	 * @param pilots the guard pilot of each prompt test that has one, by prompt test
	 * @param delivered each prompt test as delivery would write it, by prompt test
	 */
	private Proposal(DerivedProposal derived, Contract contract, ProposalSchema schema, LinkReport links,
		SortedSet<Path> controlFiles, HostLogin login, int ceiling, Map<CertificationScope, Campaign> previews,
		List<String> caps, CertificationStatus incumbent, Map<String, PilotResult> pilots,
		Map<String, DeliveredTest> delivered)
	{
		this.derived = derived;
		this.contract = contract;
		this.schema = schema;
		this.links = links;
		this.controlFiles = controlFiles;
		this.login = login;
		this.ceiling = ceiling;
		this.previews = previews;
		this.caps = List.copyOf(caps);
		this.incumbent = incumbent;
		this.pilots = Collections.unmodifiableMap(new LinkedHashMap<>(pilots));
		this.delivered = Collections.unmodifiableMap(new LinkedHashMap<>(delivered));
	}

	/**
	 * Completes a contract author's proposal for presentation.
	 *
	 * @param derived the contract author's proposal
	 * @param campaign the campaign that the proposal is for, which has no confirmed contract or has a revision
	 *   requested
	 * @param links the symbolic links of the prompt system and the staged fixtures
	 * @param controlFiles the target prompt files that the control source empties, relative to the project directory
	 * @param login how the host's harness account is billed
	 * @param calibrator calibrates the caps of the first certification attempts
	 * @param schema the proposal schema, which identifies the contract
	 * @return the proposal
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalStateException if the campaign cannot accept a confirmation
	 */
	public static Proposal of(DerivedProposal derived, Campaign campaign, LinkReport links, Set<Path> controlFiles,
		HostLogin login, CapCalibrator calibrator, ProposalSchema schema)
	{
		return of(derived, campaign, links, controlFiles, login, calibrator, schema, Optional.empty());
	}

	/**
	 * Completes a contract author's proposal for presentation, showing the target's delivered certification.
	 *
	 * @param derived the contract author's proposal
	 * @param campaign the campaign that the proposal is for, which has no confirmed contract or has a revision
	 *   requested
	 * @param links the symbolic links of the prompt system and the staged fixtures
	 * @param controlFiles the target prompt files that the control source empties, relative to the project directory
	 * @param login how the host's harness account is billed
	 * @param calibrator calibrates the caps of the first certification attempts
	 * @param schema the proposal schema, which identifies the contract
	 * @param incumbent the target's delivered certification and whether it applies to what CAT knows when it
	 *   proposes, or empty if the target has none
	 * @return the proposal
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalStateException if the campaign cannot accept a confirmation
	 */
	public static Proposal of(DerivedProposal derived, Campaign campaign, LinkReport links, Set<Path> controlFiles,
		HostLogin login, CapCalibrator calibrator, ProposalSchema schema, Optional<CertificationStatus> incumbent)
	{
		return of(derived, campaign, links, controlFiles, login, calibrator, schema, incumbent, Map.of());
	}

	/**
	 * Completes a contract author's proposal for presentation, showing the target's delivered certification and the
	 * guard pilot of each prompt test.
	 *
	 * @param derived the contract author's proposal
	 * @param campaign the campaign that the proposal is for, which has no confirmed contract or has a revision
	 *   requested
	 * @param links the symbolic links of the prompt system and the staged fixtures
	 * @param controlFiles the target prompt files that the control source empties, relative to the project directory
	 * @param login how the host's harness account is billed
	 * @param calibrator calibrates the caps of the first certification attempts
	 * @param schema the proposal schema, which identifies the contract
	 * @param incumbent the target's delivered certification and whether it applies to what CAT knows when it
	 *   proposes, or empty if the target has none
	 * @param pilots the guard pilot of each prompt test that has one, by prompt test
	 * @return the proposal
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalStateException if the campaign cannot accept a confirmation
	 */
	public static Proposal of(DerivedProposal derived, Campaign campaign, LinkReport links, Set<Path> controlFiles,
		HostLogin login, CapCalibrator calibrator, ProposalSchema schema, Optional<CertificationStatus> incumbent,
		Map<String, PilotResult> pilots)
	{
		requireThat(incumbent, "incumbent").isNotNull();
		requireThat(pilots, "pilots").isNotNull();
		requireThat(derived, "derived").isNotNull();
		requireThat(campaign, "campaign").isNotNull();
		requireThat(links, "links").isNotNull();
		requireThat(controlFiles, "controlFiles").isNotNull();
		requireThat(login, "login").isNotNull();
		requireThat(calibrator, "calibrator").isNotNull();
		requireThat(schema, "schema").isNotNull();

		Contract proposed = derived.contract();
		List<String> limitations = new ArrayList<>(proposed.limitations());
		limitations.add(DERIVED_LOADING_LIMIT);
		limitations.add(CONTROL_SOURCE_LIMIT);
		if (!links.entries().isEmpty())
			limitations.add(LinkReport.STAGING_CONSEQUENCE);
		Contract contract = new Contract(proposed.requirements(), proposed.tests(), proposed.profiles(),
			proposed.judge(), proposed.fixtures(), limitations, proposed.minimumPassRate(), proposed.salt());
		Map<CertificationScope, Campaign> previews = new LinkedHashMap<>();
		if (contract.profiles().unranked().isEmpty())
		{
			for (CertificationScope scope : CertificationScope.values())
			{
				if (scope == CertificationScope.DESCRIBED && contract.profiles().described().isEmpty())
					continue;
				// The seed changes only the draw streams, which the remaining work does not depend on
				previews.put(scope, Transitions.apply(campaign, confirm(contract, scope, schema, 0)));
			}
		}

		List<String> caps = new ArrayList<>();
		int first = campaign.nextCertificationIndex();
		for (int index = first; index < first + ATTEMPTS_SHOWN; ++index)
		{
			SignificanceLevel level = ErrorBudget.certificationLevel(index);
			String cap;
			try
			{
				cap = String.valueOf(calibrator.calibrate(level, contract.minimumPassRate()).cap());
			}
			catch (CapCalibrationException e)
			{
				cap = "unavailable: " + e.getMessage();
			}
			caps.add("Attempt " + index + ": α = " + Markdown.level(level) + ", cap " + cap);
		}
		SortedSet<Path> sortedControlFiles = Collections.unmodifiableSortedSet(new TreeSet<>(controlFiles));
		Path target = campaign.header().root().relativize(campaign.header().target());
		Map<String, DeliveredTest> delivered = new LinkedHashMap<>();
		for (DeliveredTest test : DeliveredTest.of(contract, AddedTestCases.NONE, target))
			delivered.put(test.id(), test);
		return new Proposal(derived, contract, schema, links, sortedControlFiles, login, campaign.ceiling(), previews,
			caps, incumbent.orElse(null), pilots, delivered);
	}

	/**
	 * Returns the event that confirms a contract with a certification scope.
	 *
	 * @param contract the contract, without a certification scope
	 * @param scope the certification scope
	 * @param schema the proposal schema, which identifies the contract
	 * @param seed the seed of the contract's draw streams
	 * @return the event
	 */
	private static CampaignEvent.Confirm confirm(Contract contract, CertificationScope scope, ProposalSchema schema,
		long seed)
	{
		Contract scoped = contract.withScope(scope);
		return new CampaignEvent.Confirm(scoped, ContractIdentity.of(schema, scoped), seed);
	}

	/**
	 * Returns the contract that confirmation freezes once the user chooses its certification scope: the contract
	 * author's contract with the limitations that CAT records.
	 *
	 * @return the contract, without a certification scope
	 */
	public Contract contract()
	{
		return contract;
	}

	/**
	 * Indicates whether the user may confirm the proposal with a certification scope. A link cycle or a link to a
	 * missing target blocks confirmation until the user resolves it. Conflicting requirements, a test with fewer than
	 * {@value #MINIMUM_OUTCOMES} distinct expected outcomes in either part, an inventory model whose family the
	 * capability ranking does not list, and a described scope without described cells also block confirmation.
	 *
	 * @param scope the certification scope that the user chose
	 * @return {@code true} if nothing blocks confirmation
	 * @throws NullPointerException if {@code scope} is null
	 */
	public boolean isConfirmable(CertificationScope scope)
	{
		return confirmationRefusal(scope).isEmpty();
	}

	/**
	 * Returns the reason confirmation with a certification scope is refused, listing blocking links, conflicting
	 * requirements, tests with too few distinct expected outcomes in a part, unranked models and a described scope
	 * without described cells, in that order.
	 *
	 * @param scope the certification scope that the user chose
	 * @return the refusal message, or an empty string if confirmation is allowed
	 * @throws NullPointerException if {@code scope} is null
	 */
	public String confirmationRefusal(CertificationScope scope)
	{
		requireThat(scope, "scope").isNotNull();
		List<String> reasons = new ArrayList<>();
		String links = linksAndConflictsRefusal();
		if (!links.isEmpty())
			reasons.add(links);
		reasons.addAll(splitRefusals());
		TrialProfiles profiles = contract.profiles();
		List<String> unranked = profiles.unranked();
		if (!unranked.isEmpty())
		{
			reasons.add("The capability ranking (" + profiles.ranking().source() + ") does not rank the family of " +
				"the inventory models " + String.join(", ", unranked) + ". Run revise with feedback that ranks " +
				"them.");
		}
		if (scope == CertificationScope.DESCRIBED && profiles.described().isEmpty())
		{
			reasons.add("The described certification scope needs the cells that you described in revision " +
				"feedback, and none are described. Run revise with feedback that describes them, or choose " +
				CertificationScope.WEAKEST_CELL.id() + " or " + CertificationScope.LATEST_VERSIONS.id() + ".");
		}
		StringJoiner refusal = new StringJoiner(" ");
		for (int i = 0; i < reasons.size(); ++i)
		{
			String reason = reasons.get(i);
			if (i + 1 < reasons.size() && !reason.endsWith("."))
				refusal.add(reason + ".");
			else
				refusal.add(reason);
		}
		return refusal.toString();
	}

	/**
	 * Returns one reason for each test whose distinct expected outcomes number fewer than {@value #MINIMUM_OUTCOMES}
	 * in either part.
	 *
	 * @return the reasons, in contract order
	 */
	private List<String> splitRefusals()
	{
		List<String> reasons = new ArrayList<>();
		Split split = contract.split();
		for (PromptTest test : contract.tests())
		{
			int development = split.outcomeCount(test, Part.DEVELOPMENT);
			int heldOut = split.outcomeCount(test, Part.HELD_OUT);
			if (development < MINIMUM_OUTCOMES || heldOut < MINIMUM_OUTCOMES)
			{
				reasons.add("The test `" + test.id() + "` has " + development + " distinct expected outcomes in the " +
					"development part and " + heldOut + " in the held-out part, but each part needs at least " +
					MINIMUM_OUTCOMES + ". Run revise with feedback that gives it test cases with more expected " +
					"outcomes.");
			}
		}
		return reasons;
	}

	/**
	 * Returns the reason that links or conflicting requirements block confirmation, listing blocking links before
	 * conflicting requirements.
	 *
	 * @return the message, or an empty string if neither blocks confirmation
	 */
	private String linksAndConflictsRefusal()
	{
		String linksMessage = "";
		List<String> blocking = links.blocking().stream().map(entry -> entry.link().toString()).toList();
		if (!blocking.isEmpty())
		{
			linksMessage = "These symbolic links block confirmation until they are resolved: " +
				String.join(", ", blocking);
		}
		if (derived.conflicts().isEmpty())
			return linksMessage;
		StringJoiner groups = new StringJoiner("; ");
		for (Conflict conflict : derived.conflicts())
		{
			List<String> ids = conflict.requirementIds().stream().map(id -> "`" + id + "`").toList();
			groups.add(String.join(", ", ids.subList(0, ids.size() - 1)) + " and " + ids.getLast());
		}
		String conflictsMessage = "Confirmation is refused because the proposal lists conflicting requirements: " +
			groups + ". Run revise with feedback that resolves each conflict.";
		if (linksMessage.isEmpty())
			return conflictsMessage;
		return linksMessage + ". " + conflictsMessage;
	}

	/**
	 * Returns the proposal as Markdown, with every item listed.
	 *
	 * @return the text
	 */
	public String render()
	{
		return new Markdown(derived, contract, links, controlFiles, login, ceiling, previews, caps, incumbent, pilots,
			delivered).render();
	}

	/**
	 * Returns the events that confirm the proposal with a certification scope: the confirmation, then one report for
	 * each condition that makes certification unavailable.
	 *
	 * @param seed the seed of the contract's draw streams
	 * @param scope the certification scope that the user chose
	 * @return the events, in the order to apply them
	 * @throws NullPointerException if {@code scope} is null
	 * @throws IllegalStateException if anything blocks confirmation with {@code scope}
	 */
	public List<CampaignEvent> confirmation(long seed, CertificationScope scope)
	{
		if (!isConfirmable(scope))
			throw new IllegalStateException(confirmationRefusal(scope));
		List<CampaignEvent> events = new ArrayList<>();
		events.add(confirm(contract, scope, schema, seed));
		Set<Outcome> outcomes = new LinkedHashSet<>();
		for (ScopeCondition condition : derived.scopeConditions())
			outcomes.add(condition.outcome());
		for (Outcome outcome : outcomes)
			events.add(new CampaignEvent.ReportUnavailable(outcome));
		return events;
	}
}
