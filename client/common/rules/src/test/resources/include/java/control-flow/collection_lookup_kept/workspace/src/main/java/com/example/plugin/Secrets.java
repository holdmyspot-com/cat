package com.example.plugin;

final class Secrets
{
	static boolean isSecret(String key)
	{
		return key.equals("token") || key.equals("password") || key.equals("{{setting}}_key");
	}
}
