package com.example.members;

public final class Limits
{
	private static final int MAXIMUM_UPLOAD;

	static
	{
		MAXIMUM_UPLOAD = Integer.getInteger("upload.maximum", 10);
	}

	private Limits()
	{
	}
}
