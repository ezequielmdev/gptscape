package com.gptscape;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Proxy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class GameToolsTest
{
	private static GameTools tools(boolean account, boolean bank)
	{
		GptScapeConfig config = (GptScapeConfig) Proxy.newProxyInstance(GptScapeConfig.class.getClassLoader(),
			new Class<?>[]{GptScapeConfig.class}, (proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "shareAccountData":
						return account;
					case "shareBank":
						return bank;
					default:
						return null;
				}
			});
		return new GameTools(null, null, null, config);
	}

	@Test
	public void offersNothingUnlessTheUserSharesIt()
	{
		assertFalse(tools(false, false).isEnabled());
		assertEquals(0, tools(false, false).declarations().size());
	}

	@Test
	public void bankIsASeparateOptIn()
	{
		JsonArray account = tools(true, false).declarations();
		assertEquals(6, account.size());
		for (int i = 0; i < account.size(); i++)
		{
			String name = account.get(i).getAsJsonObject().get("name").getAsString();
			assertTrue(name, tools(true, false).handles(name));
			assertFalse(name, "account_bank".equals(name));
		}

		JsonArray bank = tools(false, true).declarations();
		assertEquals(1, bank.size());
		JsonObject bankTool = bank.get(0).getAsJsonObject();
		assertEquals("account_bank", bankTool.get("name").getAsString());
		// A busca é opcional: sem "required"
		assertFalse(bankTool.getAsJsonObject("parameters").has("required"));
	}

	@Test
	public void refusesToolsTheUserTurnedOff()
	{
		String refused = "The player has not allowed sharing this information.";
		assertEquals(refused, tools(true, false).execute("account_bank", new JsonObject()));
		assertEquals(refused, tools(false, true).execute("account_quests", new JsonObject()));
	}
}
