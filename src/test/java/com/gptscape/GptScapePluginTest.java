package com.gptscape;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class GptScapePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(GptScapePlugin.class);
		RuneLite.main(args);
	}
}
