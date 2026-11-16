package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.build.BuildAdmission;
import com.holdmyspot.cat.common.build.BuildAdmissionCli;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

/**
 * Exposes the CLI's admission composition and completed-batch result mapping to its tests.
 */
public interface BuildAdmissionCliAccess
{
	/**
	 * Executes a CLI request with the supplied admission factory.
	 *
	 * @param scope process services
	 * @param project reviewed project root
	 * @param environment inherited process environment
	 * @param admissionFactory creates the admission controller after the host context is read
	 * @param args CLI arguments
	 * @return CLI exit status and structured response
	 */
	BuildAdmissionCli.Result execute(JvmScope scope, Path project, Map<String, String> environment,
		Function<BuildAdmission.HostContext, BuildAdmission> admissionFactory, String[] args);

	/**
	 * Maps a completed batch to the CLI's exit status and response.
	 *
	 * @param batch completed admission results
	 * @param evidence retained evidence directory
	 * @return CLI exit status and structured response
	 */
	BuildAdmissionCli.Result batchResult(BuildAdmission.BatchResult batch, Path evidence);
}
