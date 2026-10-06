package com.gptscape;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.client.callback.ClientThread;

/**
 * Lê níveis do jogador pela API pública do RuneLite (somente leitura), apenas quando o usuário ativa
 * "Share Game Stats". Não envia o nome do personagem.
 */
@Slf4j
@Singleton
public class RuneLiteGameContextProvider implements GameContextProvider
{
	private static final String ACCOUNT_TOOLS_HINT = "The player lets you read more about their own account with the "
		+ "account_* tools. Call them whenever an answer depends on the player's own account, instead of asking "
		+ "the player or guessing.";

	private final Client client;
	private final ClientThread clientThread;
	private final GptScapeConfig config;

	@Inject
	RuneLiteGameContextProvider(Client client, ClientThread clientThread, GptScapeConfig config)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
	}

	@Override
	public String describe()
	{
		boolean accountTools = config.shareAccountData() || config.shareBank();
		if ((!config.shareGameStats() && !accountTools) || client.getGameState() != GameState.LOGGED_IN)
		{
			return "";
		}

		String tools = accountTools ? ACCOUNT_TOOLS_HINT : "";
		if (!config.shareGameStats())
		{
			return tools;
		}

		// A API do cliente deve ser lida na thread do cliente
		CompletableFuture<String> snapshot = new CompletableFuture<>();
		clientThread.invoke(() ->
		{
			try
			{
				snapshot.complete(readStats());
			}
			catch (RuntimeException e)
			{
				snapshot.completeExceptionally(e);
			}
		});

		try
		{
			return snapshot.get(2, TimeUnit.SECONDS) + (tools.isEmpty() ? "" : "\n" + tools);
		}
		catch (Exception e)
		{
			log.debug("Could not read game stats for Gemini context", e);
			return tools;
		}
	}

	private String readStats()
	{
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return "";
		}

		StringBuilder sb = new StringBuilder()
			.append("Combat level: ").append(player.getCombatLevel()).append('\n')
			.append("Total level: ").append(client.getTotalLevel()).append('\n')
			.append("Skill levels: ");
		boolean first = true;
		for (Skill skill : Skill.values())
		{
			if ("OVERALL".equals(skill.name()))
			{
				continue;
			}
			sb.append(first ? "" : ", ").append(skill.getName()).append(' ').append(client.getRealSkillLevel(skill));
			first = false;
		}
		return sb.toString();
	}
}
