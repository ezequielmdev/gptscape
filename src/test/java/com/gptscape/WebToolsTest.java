package com.gptscape;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
	public void noticias()
	{
		assertTrue(run("osrs_news", null, null).contains("https://secure.runescape.com/m=news/"));
	}

	@Test
	public void pesquisa()
	{
		assertTrue(run("web_search", "query", "old school runescape").contains("http"));
	}

	@Test
	public void abrirPagina()
	{
		assertTrue(run("open_page", "url", "https://secure.runescape.com/m=news/archive?oldschool=1")
			.contains("Old School"));
	}

	/** A política de IA da RuneScape Wiki proíbe usar o conteúdo dela: nenhuma página desse domínio é lida. */
	@Test
	public void nuncaLeAWiki()
	{
		assertTrue(WebTools.isWiki("https://oldschool.runescape.wiki/w/Abyssal_whip"));
		assertTrue(WebTools.isWiki("https://prices.runescape.wiki/api/v1/osrs/latest"));
		assertTrue(WebTools.isWiki("https://runescape.wiki/w/Abyssal_whip"));
		assertFalse(WebTools.isWiki("https://secure.runescape.com/m=news/archive?oldschool=1"));
		assertFalse(WebTools.isWiki("https://example.com/runescape.wiki"));

		JsonObject args = new JsonObject();
		args.addProperty("url", "https://oldschool.runescape.wiki/w/Abyssal_whip");
		assertEquals(WebTools.WIKI_REFUSAL, tools.execute("open_page", args));

		String declared = tools.toolDeclarations().toString();
		assertFalse(declared.contains("osrs_wiki"));
	}
}
