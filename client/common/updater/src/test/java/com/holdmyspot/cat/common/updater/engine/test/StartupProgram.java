package com.holdmyspot.cat.common.updater.engine.test;

import java.io.IOException;

/**
 * The session-start program of the fixture plugin whose startup cache real Java records and writes: it reads its
 * input and prints a line.
 */
public final class StartupProgram
{
	/**
	 * Prevents instantiation.
	 */
	private StartupProgram()
	{
	}

	/**
	 * Runs the program.
	 *
	 * @param arguments the program's arguments, which it ignores
	 * @throws IOException if the input cannot be read
	 */
	public static void main(String[] arguments) throws IOException
	{
		System.in.readAllBytes();
		System.out.println("started");
	}
}
