package com.acme.wordlen;

/**
 * Prints the UTF-8 length of each word it is given, or of {@value #SAMPLE}.
 */
public final class Main
{
	private static final String SAMPLE = "{{sample_word}}";

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
			words = new String[]{SAMPLE};
		for (String word : words)
			System.out.println(word + ": " + NativeLength.of(word) + " bytes");
	}
}
