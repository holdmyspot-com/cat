package com.holdmyspot.cat.common.installer.activation;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Records ownership of calculated permission additions before configuration replacement. A failed record prevents
 * the replacement; a successful record may describe an entry whose replacement has not yet completed.
 */
@FunctionalInterface
public interface PermissionRecorder
{
	/**
	 * Durably records entries that the next configuration replacement adds or changes. Existing unowned entries
	 * must not be supplied.
	 *
	 * @param entries the additions, keyed by configuration path and setting
	 * @throws IOException if ownership cannot be recorded
	 */
	void record(Map<String, List<String>> entries) throws IOException;
}
