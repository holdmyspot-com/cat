package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.build.BuildAdmission;
import com.holdmyspot.cat.common.build.BuildObservations;
import java.time.Clock;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * Provides controlled construction and process-stop access for build admission tests.
 */
public interface BuildAdmissionAccess
{
	/**
	 * Creates an admission controller using the supplied forced-stop request.
	 *
	 * @param scope process services
	 * @param context shared host context
	 * @param observations resource observations
	 * @param clock wall clock
	 * @param inheritedEnvironment caller environment
	 * @param forceStop request to forcibly stop one process after identity validation
	 * @return the admission controller
	 */
	BuildAdmission create(JvmScope scope, BuildAdmission.HostContext context, BuildObservations observations,
		Clock clock, Map<String, String> inheritedEnvironment, LongConsumer forceStop);

	/**
	 * Returns the production forced-stop request.
	 *
	 * @return the process-stop request used by the admission controller
	 */
	LongConsumer systemForceStop();
}
