package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.build.BuildAdmission;
import com.holdmyspot.cat.common.delegation.DelegationCli;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

/**
 * Exposes {@link DelegationCli}'s admission composition to its tests.
 */
@FunctionalInterface
public interface DelegationCliAccess
{
	/**
	 * Executes a delegation request with the supplied admission factory.
	 *
	 * @param scope            process services
	 * @param project          project directory
	 * @param environment      complete environment supplied to child processes
	 * @param admissionFactory creates the admission controller after the host context is read
	 * @param arguments        command line
	 * @return structured result
	 */
	DelegationCli.Result execute(JvmScope scope, Path project, Map<String, String> environment,
		Function<BuildAdmission.HostContext, BuildAdmission> admissionFactory, String[] arguments);
}
