package com.holdmyspot.cat.common.runner.launcher;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.SessionLauncherChannel;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel.Request;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import com.holdmyspot.cat.common.runner.policy.PolicyBoundFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Translates a session-level request into the options of the child's runner. The request's choices become the
 * runner's existing options. The launcher alone decides the project copy, the run root, the policy bound and the
 * output files, which the request cannot name.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class RunnerArguments
{
	/**
	 * The runner option that tells the runner that the requester holds the child's capacity slot itself, so that the
	 * child takes none.
	 */
	public static final String CAPACITY_HELD_OPTION = "--capacity-held";
	/**
	 * The runner option that names the Git author of the copied project.
	 */
	public static final String GIT_NAME_OPTION = "--git-name";
	/**
	 * The runner option that names the Git author email of the copied project.
	 */
	public static final String GIT_EMAIL_OPTION = "--git-email";
	/**
	 * The name, in a request's directory, of the child's retained-evidence receipt.
	 */
	public static final String RECEIPT_FILE = "receipt.json";

	/**
	 * Prevents construction.
	 */
	private RunnerArguments()
	{
	}

	/**
	 * Returns the runner options of a request.
	 *
	 * @param request     the request, whose files lie among the launcher's private copies
	 * @param directory   the request's directory, which receives the transcript and the receipt, and holds the file of
	 *                    the request's extra permissions, if it has any, under {@link ExtraPermissions#FILE_NAME}
	 * @param runRoot     the folder that receives the child's run
	 * @param policyBound the child's {@link PolicyBoundFile}
	 * @return the options, in the order that the runner documents them
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static List<String> of(Request request, Path directory, Path runRoot, Path policyBound)
	{
		requireThat(request, "request").isNotNull();
		requireThat(directory, "directory").isNotNull();
		requireThat(runRoot, "runRoot").isNotNull();
		requireThat(policyBound, "policyBound").isNotNull();
		List<String> arguments = new ArrayList<>(List.of("--project", request.project().toString(), "--run-root",
			runRoot.toString(), PolicyBoundFile.OPTION, policyBound.toString(), "--output",
			directory.resolve(SessionLauncherChannel.TRANSCRIPT_FILE).toString()));
		if (request.keepRun())
			arguments.addAll(List.of("--keep-run", "--receipt", directory.resolve(RECEIPT_FILE).toString()));
		if (request.sessionTranscripts())
			arguments.add("--session-transcripts");
		request.model().ifPresent(model -> arguments.addAll(List.of("--model", model)));
		request.effort().ifPresent(effort -> arguments.addAll(List.of("--effort", effort)));
		request.agent().ifPresent(agent -> arguments.addAll(List.of("--agent", agent)));
		for (Path plugin : request.plugins())
			arguments.addAll(List.of("--plugin", plugin.toString()));
		request.workspace().ifPresent(workspace -> arguments.addAll(List.of("--workspace", workspace.toString())));
		for (Path evidence : request.readOnlyEvidence())
			arguments.addAll(List.of("--read-only-evidence", evidence.toString()));
		if (request.installCat())
			arguments.add("--install-cat");
		if (request.sessionRequests() > 0)
			arguments.addAll(List.of("--session-requests", String.valueOf(request.sessionRequests())));
		if (request.requestDepth() > 0)
			arguments.addAll(List.of("--request-depth", String.valueOf(request.requestDepth())));
		request.capacityWait().ifPresent(wait ->
			arguments.addAll(List.of("--capacity-wait", String.valueOf(wait.toSeconds()))));
		request.maximumConcurrency().ifPresent(maximum ->
			arguments.addAll(List.of("--max-concurrency", String.valueOf(maximum))));
		if (request.capacityHeld())
			arguments.add(CAPACITY_HELD_OPTION);
		request.instructionFiles().ifPresent(files -> arguments.addAll(List.of("--instruction-files", files)));
		request.toolOutputTokenLimit().ifPresent(limit ->
			arguments.addAll(List.of("--tool-output-token-limit", String.valueOf(limit))));
		for (Path denied : request.deniedReads())
			arguments.addAll(List.of("--deny-read", denied.toString()));
		if (!request.extraPermissions().isEmpty())
			arguments.addAll(List.of(ExtraPermissions.OPTION, directory.resolve(ExtraPermissions.FILE_NAME).toString()));
		request.gitName().ifPresent(name -> arguments.addAll(List.of(GIT_NAME_OPTION, name)));
		request.gitEmail().ifPresent(email -> arguments.addAll(List.of(GIT_EMAIL_OPTION, email)));
		return arguments;
	}
}
