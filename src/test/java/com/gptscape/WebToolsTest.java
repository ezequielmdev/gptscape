package com.gptscape;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import org.junit.Test;

/**
 * Testes de integração das ferramentas de internet. Só rodam com WEB_TOOLS_TEST=1 (acessam sites reais).
 */
public class WebToolsTest
{
	private final WebTools tools = new WebTools(new OkHttpClient(), new Gson());

	private String run(String name, String param, String value)
	{
		assumeTrue("WEB_TOOLS_TEST não definida", "1".equals(System.getenv("WEB_TOOLS_TEST")));
		JsonObject args = new JsonObject();
		if (param != null)
		{
			args.addProperty(param, value);
		}
		String result = tools.execute(name, args);
		System.out.println("===== " + name + " " + (value != null ? value : "") + "\n"
			+ result.substring(0, Math.min(result.length(), 800)));
		return result;
	}

	@Test
	public void precoGe()
	{
		assertTrue(run("ge_price", "item", "Twisted bow").contains(" gp"));
	}

	@Test
	public void noticias()
	{
		assertTrue(run("osrs_news", null, null).contains("https://secure.runescape.com/m=news/"));
	}

	@Test
	public void wiki()
	{
		assertTrue(run("osrs_wiki", "query", "Theatre of Blood").contains("Ver Sinhaza"));
	}

	@Test
	public void pesquisa()
	{
		assertTrue(run("web_search", "query", "old school runescape").contains("http"));
	}

	@Test
	public void abrirPagina()
	{
		assertTrue(run("open_page", "url", "https://oldschool.runescape.wiki/w/Abyssal_whip").contains("whip"));
	}
}
