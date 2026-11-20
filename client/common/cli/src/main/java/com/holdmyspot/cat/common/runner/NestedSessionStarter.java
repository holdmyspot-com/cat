package com.holdmyspot.cat.common.runner;

import java.io.IOException;


/**
 * Starts an isolated harness execution for the line-delimited JSON request bridge.
 */
@FunctionalInterface
public interface NestedSessionStarter
{
	/**
	 * Starts one live isolated nested session.
	 *
	 * @return the live nested session
	 *
	 * @throws IOException if the nested session cannot be started
	 */
	LiveNestedSession start() throws IOException;
}
