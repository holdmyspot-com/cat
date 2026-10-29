package com.acme.app;

import com.acme.digest.NativeLength;

/**
 * Prints the UTF-8 length of each word it is given, or of a sample sentence.
 */
public final class Main
{
	private Main()
	{
	}

	/**
	 * @param args the words to measure
	 */
	public static void main(String[] args)
	{
		String[] words = args;
		if (words.length == 0)
			words = new String[]{"naïve", "café", "plain"};
		for (String word : words)
			System.out.println(word + ": " + NativeLength.of(word) + " bytes");
	}
}
