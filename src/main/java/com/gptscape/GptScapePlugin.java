package com.gptscape;

import com.google.common.collect.ImmutableSet;
import com.google.inject.Provides;
import java.util.Set;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

@Slf4j
@PluginDescriptor(
	name = "GPTScape",
	description = "AI assistant for Old School RuneScape in your sidebar, powered by Google Gemini (free)",
	tags = {"gptscape", "ai", "assistant", "chat", "gemini", "google", "chatgpt"}
)
public class GptScapePlugin extends Plugin
{
	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private GptScapePanel panel;

	@Inject
	private ConfigManager configManager;

	@Inject
	private GeminiClient geminiClient;

	@Inject
	private ChatHistoryStore historyStore;

	/** Modelos que versões anteriores do plugin salvavam sozinhas e que hoje não funcionam bem. */
	private static final Set<String> OLD_DEFAULT_MODELS = ImmutableSet.of(
		"gpt-4o-mini", "gemini-2.5-flash", "gemini-flash-latest");

	/** Instruções padrão de versões anteriores; hoje o prompt do sistema já vem embutido. */
	private static final Set<String> OLD_DEFAULT_PROMPTS = ImmutableSet.of(
		"You are a helpful, concise assistant and an Old School RuneScape expert.");

	private NavigationButton navButton;

	@Override
	protected void startUp()
	{
		migrateOldSettings();
		geminiClient.start();
		historyStore.start();

		navButton = NavigationButton.builder()
			.tooltip("GPTScape")
			.icon(ChatIcons.navigationImage())
			.priority(10)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		onEdt(panel::onPluginStart);
	}

	@Override
	protected void shutDown()
	{
		// Antes de parar o cliente e o histórico: cancela a geração e salva o texto parcial
		onEdt(panel::onPluginStop);
		geminiClient.stop();
		historyStore.stop();
		clientToolbar.removeNavigation(navButton);
		navButton = null;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!GptScapeConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		switch (event.getKey())
		{
			case "apiKey":
				SwingUtilities.invokeLater(panel::refreshView);
				break;
			case "saveHistory":
				if (Boolean.parseBoolean(event.getNewValue()))
				{
					SwingUtilities.invokeLater(panel::saveHistoryNow);
				}
				else
				{
					historyStore.clear();
				}
				break;
			default:
				break;
		}
	}

	private static void onEdt(Runnable r)
	{
		if (SwingUtilities.isEventDispatchThread())
		{
			r.run();
		}
		else
		{
			SwingUtilities.invokeLater(r);
		}
	}

	private void migrateOldSettings()
	{
		String savedPrompt = configManager.getConfiguration(GptScapeConfig.GROUP, "systemPrompt");
		if (savedPrompt != null && (savedPrompt.contains("Responda em português")
			|| OLD_DEFAULT_PROMPTS.contains(savedPrompt.trim())))
		{
			configManager.unsetConfiguration(GptScapeConfig.GROUP, "systemPrompt");
		}

		String savedModel = configManager.getConfiguration(GptScapeConfig.GROUP, "model");
		if (savedModel != null && OLD_DEFAULT_MODELS.contains(savedModel.trim()))
		{
			configManager.unsetConfiguration(GptScapeConfig.GROUP, "model");
		}
	}

	@Provides
	GptScapeConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GptScapeConfig.class);
	}
}
