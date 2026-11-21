package com.holdmyspot.cat.common.optimizer.cli;

import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.optimizer.adapter.RunnerSessions.HeldOutPaths;
import com.holdmyspot.cat.common.optimizer.cli.PeerTrialProtocol.Launch;
import com.holdmyspot.cat.common.optimizer.contract.Part;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence;
import com.holdmyspot.cat.common.optimizer.port.HookInvocation;
import com.holdmyspot.cat.common.optimizer.port.HookRegistration;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.optimizer.port.LoadedSources;
import com.holdmyspot.cat.common.optimizer.port.PluginBase;
import com.holdmyspot.cat.common.optimizer.port.SessionHandle;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.TurnObserver;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.RetainedNestedSessionClient;
import com.holdmyspot.cat.common.runner.RetainedTrialClaim;
import com.holdmyspot.cat.common.runner.RetainedTrialRequest;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse;
import com.holdmyspot.cat.common.runner.SessionRequestForwarder.Route;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The owner's trial port for a same-release installed evaluator.
 */
public final class PeerTrialSessions implements SessionPort
{
	private final JvmScope scope;
	private final Path artifact;
	private final EvaluatorMetadata metadata;
	private final Route route;
	private final Path requests;
	private final Path project;
	private final Path results;
	private final HeldOutPaths heldOut;
	private final HarnessCapacity capacity;
	private final RetainedNestedSessionClient retainedTrials;

	/**
	 * Creates a trial port from the installed evaluator and the owner's resolved broker channel.
	 *
	 * @param scope          the owner's process dependencies
	 * @param artifact       the selected installed evaluator
	 * @param metadata       the installed evaluator's measurement metadata
	 * @param route          the owner broker's resolved mode
	 * @param requests       the owner broker's request directory
	 * @param project        the owner's host project
	 * @param results        the owner's results directory
	 * @param heldOut        finds held-out paths to protect for each trial
	 * @param capacity       reads the selected evaluator's shared provider hold
	 * @param retainedTrials the owner's authenticated client for retained trials
	 */
	public PeerTrialSessions(JvmScope scope, Path artifact, EvaluatorMetadata metadata, Route route, Path requests,
		Path project, Path results, HeldOutPaths heldOut, HarnessCapacity capacity,
		RetainedNestedSessionClient retainedTrials)
	{
		this.scope = scope;
		this.artifact = artifact;
		this.metadata = metadata;
		this.route = route;
		this.requests = requests;
		this.project = project;
		this.results = results;
		this.heldOut = heldOut;
		this.capacity = capacity;
		this.retainedTrials = retainedTrials;
	}

	@Override
	public SessionHandle start(SessionRequest request) throws IOException
	{
		throw new IOException("An installed evaluator needs a tracked prompt test trial");
	}

	@Override
	public boolean recoversTrials()
	{
		return true;
	}

	@Override
	public SessionHandle startTrial(SessionRequest request, UUID requestId, Optional<RetainedTrialClaim> claim)
		throws IOException
	{
		TurnObserver.Progress progress = request.turnObserver().map(TurnObserver::progress).orElse(null);
		Launch launch = new Launch(metadata.harness(), route, requests, results, project, heldOut.list(), request,
			requestId, claim.orElse(null), progress);
		return PeerTrialProcess.start(scope, artifact, launch);
	}

	@Override
	public RetainedTrialResponse retainedTrial(RetainedTrialRequest request) throws IOException
	{
		return retainedTrials.request(request);
	}

	@Override
	public Optional<Instant> sharedHoldUntil(Instant now) throws IOException
	{
		Instant end = capacity.hold().endsAt();
		if (end.isAfter(now))
			return Optional.of(end);
		return Optional.empty();
	}

	@Override
	public int sessionEnvironmentVersion()
	{
		return metadata.runtime().environmentVersion();
	}

	@Override
	public String sessionEnvironmentRelease()
	{
		return metadata.runtime().environmentRelease();
	}

	@Override
	public int judgeVersion()
	{
		return metadata.runtime().judgeVersion();
	}

	@Override
	public SessionEvidence readEvidence(Path transcript) throws IOException
	{
		return PeerTrialEvidence.read(scope.json(), transcript);
	}

	@Override
	public boolean reportsExitStatus()
	{
		return metadata.runtime().reportsExitStatus();
	}

	@Override
	public HostLogin hostLogin()
	{
		return metadata.runtime().login();
	}

	@Override
	public List<String> unapprovedHooks(Path plugin)
	{
		throw unsupportedInspection();
	}

	@Override
	public Optional<String> findHeldOutExposure(Path project)
	{
		throw unsupportedInspection();
	}

	@Override
	public LoadedSources loadedSources(Path project, List<PluginBase> plugins)
	{
		throw unsupportedInspection();
	}

	@Override
	public List<HookRegistration> hookRegistrations(Path project, List<PluginBase> plugins)
	{
		throw unsupportedInspection();
	}

	@Override
	public List<HookInvocation> earlyHooks(Path project, List<PluginBase> plugins, Optional<Path> home, String prompt)
	{
		throw unsupportedInspection();
	}

	@Override
	public List<Path> installedPlugins(String name)
	{
		throw unsupportedInspection();
	}

	@Override
	public List<String> installedPluginNames()
	{
		throw unsupportedInspection();
	}

	@Override
	public List<Path> resultsFolders()
	{
		return List.of(results, resultsFor(results, Part.HELD_OUT));
	}

	/**
	 * Returns the same per-part results directory as the native session adapter.
	 *
	 * @param results the owner's development results directory
	 * @param part    the trial's part
	 * @return the owner directory used by that part
	 */
	static Path resultsFor(Path results, Part part)
	{
		if (part == Part.HELD_OUT)
			return results.resolveSibling(results.getFileName() + "-held_out");
		return results;
	}

	/**
	 * Refuses a general inspection that is outside the installed trial relay's interfaces.
	 *
	 * @return the reason this operation cannot run through this port
	 */
	private static UnsupportedOperationException unsupportedInspection()
	{
		return new UnsupportedOperationException("The installed evaluator port only starts and reads tracked trials");
	}
}
