package com.gptscape;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(GptScapeConfig.GROUP)
public interface GptScapeConfig extends Config
{
	String GROUP = "chatgptsidebar";

	@ConfigSection(
		name = "Conversation",
		description = "Chat behavior",
		position = 10
	)
	String conversationSection = "conversation";

	@ConfigSection(
		name = "Advanced Settings",
		description = "Options most people won't need to change",
		position = 20,
		closedByDefault = true
	)
	String advancedSection = "advanced";

	// As chaves (keyName) são as mesmas das versões anteriores para manter as configurações salvas

	@ConfigItem(
		keyName = "apiKey",
		name = "Gemini API Key",
		description = "API key used to connect to the Google Gemini API. Get a free key at aistudio.google.com/apikey.",
		secret = true,
		position = 1
	)
	default String apiKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "internet",
		name = "Web Access",
		description = "Lets GPTScape look things up: web search, official OSRS news and current GE prices.",
		section = conversationSection,
		position = 11
	)
	default boolean internet()
	{
		return true;
	}

	@ConfigItem(
		keyName = "saveHistory",
		name = "Save Chat History",
		description = "Keeps your current chat on this computer so it's still there after restarting RuneLite.",
		section = conversationSection,
		position = 12
	)
	default boolean saveHistory()
	{
		return true;
	}

	@ConfigItem(
		keyName = "shareGameStats",
		name = "Share Game Stats",
		description = "Sends your combat and skill levels with each message so answers fit your account. "
			+ "Your character name is never shared.",
		section = conversationSection,
		position = 13
	)
	default boolean shareGameStats()
	{
		return false;
	}

	@ConfigItem(
		keyName = "shareAccountData",
		name = "Share Account Details",
		description = "Lets GPTScape read your account type, quests, diaries, worn gear, inventory, Slayer task "
			+ "and GE offers when a question needs them. Your character name is never shared.",
		section = conversationSection,
		position = 14
	)
	default boolean shareAccountData()
	{
		return false;
	}

	@ConfigItem(
		keyName = "shareBank",
		name = "Share Bank",
		description = "Lets GPTScape read the items in your bank when a question needs them. "
			+ "Open your bank once after starting RuneLite so the plugin can see it.",
		section = conversationSection,
		position = 15
	)
	default boolean shareBank()
	{
		return false;
	}

	@ConfigItem(
		keyName = "model",
		name = "Gemini Model",
		description = "Google Gemini model GPTScape uses. If it's unavailable, the plugin automatically falls back to another free model.",
		section = advancedSection,
		position = 21
	)
	default String model()
	{
		return GeminiClient.DEFAULT_MODEL;
	}

	@ConfigItem(
		keyName = "systemPrompt",
		name = "System Prompt",
		description = "Optional extra instructions for GPTScape, for example \"Keep answers short.\"",
		section = advancedSection,
		position = 22
	)
	default String systemPrompt()
	{
		return "";
	}

	@Range(min = 2, max = 100)
	@ConfigItem(
		keyName = "maxHistory",
		name = "Conversation History",
		description = "How many recent messages GPTScape remembers. Older messages stay visible in the chat.",
		section = advancedSection,
		position = 23
	)
	default int maxHistory()
	{
		return 30;
	}

	@ConfigItem(
		keyName = "chatGptUrl",
		name = "Website Link",
		description = "Website opened by \"Open website\" in the chat menu.",
		section = advancedSection,
		position = 24
	)
	default String websiteUrl()
	{
		return "https://gemini.google.com/";
	}
}
