package com.holdmyspot.cat.common.installer.engine;

import java.io.IOException;

/**
 * Decides whether the user allows a change that install can make only with their permission.
 */
@FunctionalInterface
public interface Consent
{
	/**
	 * Returns whether the user allows a change.
	 *
	 * @param request the question that explains the change, as the harness integration words it
	 * @return {@code true} if the user allows it
	 * @throws NullPointerException if {@code request} is null
	 * @throws IOException          if the user's answer cannot be read
	 */
	boolean grants(String request) throws IOException;
}
