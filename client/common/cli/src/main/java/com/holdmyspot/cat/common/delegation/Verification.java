package com.holdmyspot.cat.common.delegation;

import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.build.BuildAdmission;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Constructs and runs the reviewed focused Maven gate with invocation-owned state.
 */
public final class Verification
{
	/** Identifies the reviewed startup, output coverage, and Maven transport contract. */
	public static final String PROFILE_ID = "project_maven_v3";

	/**
	 * Prevents construction of the Maven gate service.
	 */
	private Verification()
	{
	}

	/**
	 * Constructs the one supported Maven command from a typed work unit.
	 *
	 * @param work work unit with a reviewed gate
	 * @return complete argv list without shell parsing
	 */
	public static List<String> command(Plan.Work work) throws IOException
	{
		Plan.Verification verification = work.verification();
		return BuildAdmission.command(BuildAdmission.MavenGate.of(Path.of("."), verification.modules(),
			verification.tests(), verification.definitionPaths().stream().map(Path::of).toList(),
			verification.reviewId(), verification.reviewer()));
	}

	/**
	 * Constructs the immutable child environment from reviewed transport inputs and owned paths.
	 *
	 * @param invocation invocation-owned roots
	 * @param inherited caller environment
	 * @return complete immutable child environment
	 */
	public static Map<String, String> environment(Path invocation, Map<String, String> inherited)
	{
		Map<String, String> result = new HashMap<>();
		for (String key : List.of("PATH", "JAVA_HOME", "SYSTEMROOT", "LANG", "LC_ALL", "LC_CTYPE",
			"http_proxy", "https_proxy",
			"all_proxy", "no_proxy", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY"))
			copyIfPresent(inherited, result, key);
		copyIfPresent(inherited, result, "CAT_CALLER_LC_ALL", "LC_ALL");
		copyIfPresent(inherited, result, "CAT_CALLER_LC_CTYPE", "LC_CTYPE");
		Path home = invocation.resolve("home");
		Path temporary = invocation.resolve("temporary");
		result.put("HOME", home.toString());
		result.put("MAVEN_USER_HOME", home.toString());
		result.put("TMPDIR", temporary.toString());
		result.put("TMP", temporary.toString());
		result.put("TEMP", temporary.toString());
		result.put("MAVEN_OPTS", "-Djava.io.tmpdir=" + temporary);
		return Map.copyOf(result);
	}

	/**
	 * Copies an environment value only when it exists.
	 *
	 * @param source caller environment
	 * @param destination Maven child environment
	 * @param sourceKey inherited key
	 */
	private static void copyIfPresent(Map<String, String> source, Map<String, String> destination, String sourceKey)
	{
		String value = source.get(sourceKey);
		if (value != null)
			destination.put(sourceKey, value);
	}

	/**
	 * Restores one locale value saved by the launcher.
	 *
	 * @param source inherited process environment
	 * @param destination Maven child environment
	 * @param sourceKey launcher-saved caller locale variable
	 * @param targetKey locale variable used by the child
	 */
	private static void copyIfPresent(Map<String, String> source, Map<String, String> destination,
		String sourceKey, String targetKey)
	{
		String value = source.get(sourceKey);
		if (value != null)
			destination.put(targetKey, value);
	}

	/**
	 * Requires a regular Maven-created diagnostic log after process termination.
	 *
	 * @param regularLog whether Maven's configured log is a regular file without following links
	 * @param exitCode terminal wrapper process exit
	 * @param wrapperLog retained launcher output
	 * @throws IOException if Maven did not create its diagnostic log
	 */
	public static void requireMavenLog(boolean regularLog, int exitCode, Path wrapperLog) throws IOException
	{
		if (!regularLog)
			throw new IOException("Maven did not create its diagnostic log; terminal wrapper exit " + exitCode +
				"; retained wrapper log: " + wrapperLog);
	}

	/**
	 * Requires observed changes under every reviewed project output root.
	 *
	 * @param work reviewed work unit
	 * @param observedChanges paths observed in the disposable project
	 * @throws IOException if one or more project output roots were not exercised
	 */
	public static void requireOutputCoverage(Plan.Work work, List<String> observedChanges) throws IOException
	{
		List<String> missing = new ArrayList<>();
		for (String root : work.verification().projectOutputRoots())
		{
			StringBuilder directory = new StringBuilder(root);
			if (!root.endsWith("/"))
				directory.append('/');
			String prefix = directory.toString();
			if (observedChanges.stream().noneMatch(path -> path.equals(prefix) || path.startsWith(prefix)))
				missing.add(root);
		}
		if (!missing.isEmpty())
			throw new IOException("Maven did not exercise reviewed project output roots: " + missing);
	}

	/**
	 * Runs the selected Maven gate in a disposable project with isolated Maven and temporary directories.
	 *
	 * @param scope process service owner
	 * @param admission required build admission controller
	 * @param project disposable project root
	 * @param work work unit with a reviewed gate
	 * @param invocation invocation-owned roots directory
	 * @param inherited caller environment used only for the executable path and Java selection
	 * @param sourceManifestId immutable input source identity
	 * @param excludedRoots reviewed project exclusions
	 * @param outputRoots reviewed project output roots
	 * @return terminal process result
	 * @throws IOException if the process cannot start, output cannot be drained, or the wait is interrupted
	 */
	public static GateResult run(JvmScope scope, BuildAdmission admission, Path project, Plan.Work work,
		Path invocation, Map<String, String> inherited, String sourceManifestId, List<String> excludedRoots,
		List<String> outputRoots) throws IOException
	{
		Plan.Verification verification = work.verification();
		BuildAdmission.MavenGate gate = BuildAdmission.MavenGate.of(project, verification.modules(),
			verification.tests(), verification.definitionPaths().stream().map(Path::of).toList(),
			verification.reviewId(), verification.reviewer());
		BuildAdmission.Job job = new BuildAdmission.Job(work.workId(), work.assignmentId(), project, gate,
			invocation, sourceManifestId, excludedRoots, outputRoots);
		BuildAdmission.JobResult result = admission.run(job, invocation.resolve("evidence"));
		Path diagnostics = invocation.resolve("diagnostics");
		int exitCode = -1;
		if (result.exitCode() != null)
			exitCode = result.exitCode();
		return new GateResult(BuildAdmission.command(gate), exitCode,
			result.diagnostics(), List.of(invocation.resolve("home"), invocation.resolve("repository"),
				invocation.resolve("temporary"), diagnostics), result.admissionReceiptId(), result.terminalReceiptId());
	}

	/**
	 * One Maven invocation's terminal result.
	 *
	 * @param arguments executed argv
	 * @param exitCode terminal process exit
	 * @param diagnosticsPath retained output path
	 * @param ownedRoots invocation-owned writable roots
	 * @param admissionReceiptId ledger reservation identity
	 * @param terminalReceiptId durable terminal evidence identity
	 */
	public record GateResult(List<String> arguments, int exitCode, Path diagnosticsPath, List<Path> ownedRoots,
		String admissionReceiptId, String terminalReceiptId)
	{
		/**
		 * Copies command and root collections.
		 *
		 * @param arguments executed argv
		 * @param exitCode terminal process exit
		 * @param diagnosticsPath retained output path
		 * @param ownedRoots invocation-owned writable roots
		 * @param admissionReceiptId ledger reservation identity
		 * @param terminalReceiptId durable terminal evidence identity
		 */
		public GateResult
		{
			arguments = List.copyOf(arguments);
			ownedRoots = List.copyOf(ownedRoots);
		}
	}
}
