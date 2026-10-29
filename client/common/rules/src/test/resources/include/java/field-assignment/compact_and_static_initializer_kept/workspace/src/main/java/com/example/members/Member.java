package com.example.members;

public record Member(String {{component}}, int age)
{
	public Member
	{
		{{component}} = {{component}}.strip();
	}
}
