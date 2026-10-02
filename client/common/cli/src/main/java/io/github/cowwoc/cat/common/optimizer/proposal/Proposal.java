package io.github.cowwoc.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.Transitions;
import io.github.cowwoc.cat.common.optimizer.campaign.ContractIdentity;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.ProposalSchema;
import io.github.cowwoc.cat.common.optimizer.execution.CapCalibrator;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.port.HostLogin;
import io.github.cowwoc.cat.common.optimizer.statistics.CapCalibrationException;
import io.github.cowwoc.cat.common.optimizer.statistics.ErrorBudget;
import io.github.cowwoc.cat.common.optimizer.statistics.SignificanceLevel;
import io.github.cowwoc.cat.common.optimizer.workspace.LinkReport;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The contract proposal that the user confirms or answers with feedback.
 * <p>
 * It combines the deriver's proposal with the items that CAT computes: the statistical procedure, the
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
	 * The number of certification attempts whose caps the proposal shows.
	 */
	private static final int ATTEMPTS_SHOWN = 3;
	private final DerivedProposal derived;
	private final Contract contract;
	private final Sha256 contractHash;
	private final LinkReport links;
	private final HostLogin login;
	private final Campaign confirmed;
	private final List<String> caps;

	/**
	 * Creates a proposal.
	 *
	 * @param derived the deriver's proposal
	 * @param contract the contract that confirmation freezes
	 * @param contractHash the contract's identity
	 * @param links the symbolic links
	 * @param login how the host's harness account is billed
	 * @param confirmed the campaign as it would be after confirmation
	 * @param caps the level and cap of each of the first attempts, as text
	 */
	private Proposal(DerivedProposal derived, Contract contract, Sha256 contractHash, LinkReport links,
		HostLogin login, Campaign confirmed, List<String> caps)
	{
		this.derived = derived;
		this.contract = contract;
		this.contractHash = contractHash;
		this.links = links;
		this.login = login;
		this.confirmed = confirmed;
		this.caps = List.copyOf(caps);
	}

	/**
	 * Completes a deriver's proposal for presentation.
	 *
	 * @param derived the deriver's proposal
	 * @param campaign the campaign that the proposal is for, which has no confirmed contract or has a revision
	 *   requested
	 * @param links the symbolic links of the prompt system and the staged fixtures
	 * @param login how the host's harness account is billed
	 * @param calibrator calibrates the caps of the first certification attempts
	 * @param schema the proposal schema, which identifies the contract
	 * @return the proposal
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalStateException if the campaign cannot accept a confirmation
	 */
	public static Proposal of(DerivedProposal derived, Campaign campaign, LinkReport links, HostLogin login,
		CapCalibrator calibrator, ProposalSchema schema)
	{
		requireThat(derived, "derived").isNotNull();
		requireThat(campaign, "campaign").isNotNull();
		requireThat(links, "links").isNotNull();
		requireThat(login, "login").isNotNull();
		requireThat(calibrator, "calibrator").isNotNull();
		requireThat(schema, "schema").isNotNull();

		Contract proposed = derived.contract();
		List<String> limitations = new ArrayList<>(proposed.limitations());
		limitations.add(DERIVED_LOADING_LIMIT);
		if (!links.entries().isEmpty())
			limitations.add(LinkReport.STAGING_CONSEQUENCE);
		Contract contract = new Contract(proposed.requirements(), proposed.categories(), proposed.profiles(),
			proposed.judge(), proposed.fixtures(), limitations);
		Sha256 contractHash = ContractIdentity.of(schema, contract);
		// The seed changes only the draw streams, which neither the remaining work nor the caps depend on
		Campaign confirmed = Transitions.apply(campaign, new CampaignEvent.Confirm(contract, contractHash, 0));

		List<String> caps = new ArrayList<>();
		int first = confirmed.nextCertificationIndex();
		for (int index = first; index < first + ATTEMPTS_SHOWN; ++index)
		{
			SignificanceLevel level = ErrorBudget.certificationLevel(index);
			String cap;
			try
			{
				cap = String.valueOf(calibrator.calibrate(level).cap());
			}
			catch (CapCalibrationException e)
			{
				cap = "unavailable: " + e.getMessage();
			}
			caps.add("Attempt " + index + ": α = " + Markdown.level(level) + ", cap " + cap);
		}
		return new Proposal(derived, contract, contractHash, links, login, confirmed, caps);
	}

	/**
	 * Returns the contract that confirmation freezes: the deriver's contract with the limitations that CAT records.
	 *
	 * @return the contract
	 */
	public Contract contract()
	{
		return contract;
	}

	/**
	 * Indicates whether the user may confirm the proposal. A link cycle or a link to a missing target blocks
	 * confirmation until the user resolves it.
	 *
	 * @return {@code true} if no link blocks confirmation
	 */
	public boolean isConfirmable()
	{
		return links.blocking().isEmpty();
	}

	/**
	 * Returns the proposal as Markdown, with every item listed.
	 *
	 * @return the text
	 */
	public String render()
	{
		return new Markdown(derived, contract, links, login, confirmed, caps).render();
	}

	/**
	 * Returns the events that confirm the proposal: the confirmation, then one report for each condition that makes
	 * certification unavailable.
	 *
	 * @param seed the seed of the contract's draw streams
	 * @return the events, in the order to apply them
	 * @throws IllegalStateException if a link blocks confirmation
	 */
	public List<CampaignEvent> confirmation(long seed)
	{
		if (!isConfirmable())
		{
			List<String> blocking = links.blocking().stream().map(entry -> entry.link().toString()).toList();
			throw new IllegalStateException("These symbolic links block confirmation until they are resolved: " +
				String.join(", ", blocking));
		}
		List<CampaignEvent> events = new ArrayList<>();
		events.add(new CampaignEvent.Confirm(contract, contractHash, seed));
		Set<Outcome> outcomes = new LinkedHashSet<>();
		for (ScopeCondition condition : derived.scopeConditions())
			outcomes.add(condition.outcome());
		for (Outcome outcome : outcomes)
			events.add(new CampaignEvent.ReportUnavailable(outcome));
		return events;
	}
}
