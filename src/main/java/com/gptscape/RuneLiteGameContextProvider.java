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
		if (!config.shareGameStats() || client.getGameState() != GameState.LOGGED_IN)
		{
			return "";
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
			return snapshot.get(2, TimeUnit.SECONDS);
		}
		catch (Exception e)
		{
			log.debug("Could not read game stats for Gemini context", e);
			return "";
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
