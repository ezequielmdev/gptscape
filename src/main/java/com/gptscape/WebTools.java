package com.gptscape;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;
import net.runelite.http.api.item.ItemPrice;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Ferramentas de internet gratuitas que o Gemini pode chamar (function calling).
 * A pesquisa do próprio Google não é liberada no plano gratuito, então o plugin faz as buscas.
 */
@Slf4j
@Singleton
public class WebTools
{
	private static final String BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
		+ "(KHTML, like Gecko) Chrome/130.0 Safari/537.36";
	private static final String NEWS_URL = "https://secure.runescape.com/m=news/archive?oldschool=1";
	/**
	 * A política de IA generativa da RuneScape Wiki não permite usar o conteúdo dela com IA
	 * (https://meta.runescape.wiki/w/Meta:Generative_AI_policy): o plugin nunca lê nada desse domínio.
	 */
	private static final String WIKI_DOMAIN = "runescape.wiki";
	static final String WIKI_REFUSAL = "The RuneScape Wiki does not allow its content to be used with generative AI, "
		+ "so its pages cannot be read here. You may give the player the link to open themselves.";
	private static final int MAX_PRICE_RESULTS = 6;
	private static final int MAX_PAGE_CHARS = 12000;

	private static final Pattern NEWS_ARTICLE = Pattern.compile(
		"news-list-article__title-link' href='([^']+)'>([^<]+)</a>.*?"
			+ "news-list-article__category'>([^<]*)</span>.*?"
			+ "datetime='([^']+)'.*?"
			+ "news-list-article__summary'>(.*?)<a", Pattern.DOTALL);
	private static final Pattern DDG_RESULT = Pattern.compile(
		"<a rel=\"nofollow\" href=\"([^\"]+)\" class='result-link'>(.*?)</a>.*?"
			+ "class='result-snippet'>(.*?)</td>", Pattern.DOTALL);

	private final OkHttpClient httpClient;
	private final Gson gson;

	/** Preços do próprio RuneLite; null fora do cliente (testes e ferramentas de desenvolvimento). */
	private final ItemManager itemManager;

	WebTools(OkHttpClient httpClient, Gson gson)
	{
		this(httpClient, gson, null);
	}

	@Inject
	WebTools(OkHttpClient httpClient, Gson gson, ItemManager itemManager)
	{
		this.httpClient = httpClient.newBuilder()
			.connectTimeout(10, TimeUnit.SECONDS)
			.readTimeout(20, TimeUnit.SECONDS)
			.followRedirects(true)
			// Vale para toda requisição, inclusive redirecionamentos: nada é enviado à wiki
			.addNetworkInterceptor(chain ->
			{
				if (isWiki(chain.request().url().toString()))
				{
					throw new IOException(WIKI_REFUSAL);
				}
				return chain.proceed(chain.request());
			})
			.build();
		this.gson = gson;
		this.itemManager = itemManager;
	}

	/** Ferramentas no formato nativo da API do Gemini: {"functionDeclarations": [...]}. */
	JsonObject toolDeclarations()
	{
		JsonArray tools = new JsonArray();
		tools.add(tool("web_search",
			"Searches the internet (DuckDuckGo) and returns titles, links and snippets. Use it for any current "
				+ "information, news, prices, dates, events or anything you are not sure about.",
			"query", "Search terms. Prefer English for game-related topics."));
		tools.add(tool("osrs_news",
			"Lists the latest official Old School RuneScape news and updates (Jagex website), with date, "
				+ "category, summary and link. Use it for questions about updates, patch notes and events.",
			null, null));
		tools.add(tool("ge_price",
			"Current Grand Exchange price of an OSRS item, from RuneLite's own price data. Always use it for "
				+ "questions about an item's price, value or cost.",
			"item", "Item name in English (e.g. 'Twisted bow', 'Abyssal whip')."));
		tools.add(tool("open_page",
			"Opens a web page and returns its text. Use it to read a link found in a search or a full news post. "
				+ "It cannot open RuneScape Wiki pages.",
			"url", "Full address starting with https://"));

		JsonObject declarations = new JsonObject();
		declarations.add("functionDeclarations", tools);
		return declarations;
	}

	private static JsonObject tool(String name, String description, String param, String paramDescription)
	{
		JsonObject function = new JsonObject();
		function.addProperty("name", name);
		function.addProperty("description", description);

		if (param != null)
		{
			JsonObject p = new JsonObject();
			p.addProperty("type", "STRING");
			p.addProperty("description", paramDescription);
			JsonObject properties = new JsonObject();
			properties.add(param, p);
			JsonArray required = new JsonArray();
			required.add(param);

			JsonObject parameters = new JsonObject();
			parameters.addProperty("type", "OBJECT");
			parameters.add("properties", properties);
			parameters.add("required", required);
			function.add("parameters", parameters);
		}
		return function;
	}

	/** Texto curto para mostrar ao usuário enquanto a ferramenta roda. */
	static String describe(String name, JsonObject args)
	{
		switch (name)
		{
			case "web_search":
				return "Searching: " + arg(args, "query");
			case "osrs_news":
				return "Reading OSRS news";
			case "ge_price":
				return "Checking price: " + arg(args, "item");
			case "open_page":
				HttpUrl url = HttpUrl.parse(arg(args, "url"));
				return "Reading " + (url != null ? url.host() : "page");
			default:
				return "Searching";
		}
	}

	/** Executa a ferramenta (bloqueante) e devolve o resultado em texto para o modelo. */
	String execute(String name, JsonObject args)
	{
		try
		{
			switch (name)
			{
				case "web_search":
					return webSearch(arg(args, "query"));
				case "osrs_news":
					return osrsNews();
				case "ge_price":
					return gePrice(arg(args, "item"));
				case "open_page":
					return openPage(arg(args, "url"));
				default:
					return "Unknown tool: " + name;
			}
		}
		catch (Exception e)
		{
			log.debug("Falha na ferramenta {}", name, e);
			return "Error running " + name + ": " + e.getMessage();
		}
	}

	private String webSearch(String query) throws IOException
	{
		if (query.isEmpty())
		{
			return "Empty query.";
		}

		HttpUrl url = HttpUrl.parse("https://lite.duckduckgo.com/lite/").newBuilder()
			.addQueryParameter("q", query)
			.build();
		return searchResults(query, get(url.toString(), BROWSER_UA));
	}

	/** Extrai os resultados da página de busca, sem os anúncios e sem nada da wiki. */
	static String searchResults(String query, String html)
	{
		StringBuilder sb = new StringBuilder("Search results for \"" + query + "\":\n\n");
		Matcher m = DDG_RESULT.matcher(html);
		int count = 0;
		while (m.find() && count < 8)
		{
			String link = decodeDdgLink(m.group(1));
			if (link.contains("duckduckgo.com/y.js"))
			{
				continue; // anúncio
			}
			if (isWiki(link))
			{
				continue; // o trecho do resultado é conteúdo da wiki
			}
			count++;
			sb.append(count).append(". ").append(htmlToText(m.group(2))).append('\n')
				.append("   ").append(link).append('\n')
				.append("   ").append(htmlToText(m.group(3))).append("\n\n");
		}

		return count == 0 ? "No results found for \"" + query + "\"." : sb.toString();
	}

	private static String decodeDdgLink(String href)
	{
		String h = href.replace("&amp;", "&");
		int i = h.indexOf("uddg=");
		if (i >= 0)
		{
			String enc = h.substring(i + 5);
			int amp = enc.indexOf('&');
			if (amp >= 0)
			{
				enc = enc.substring(0, amp);
			}
			try
			{
				return URLDecoder.decode(enc, StandardCharsets.UTF_8.name());
			}
			catch (Exception ignored)
			{
			}
		}
		return h.startsWith("//") ? "https:" + h : h;
	}

	private String osrsNews() throws IOException
	{
		String html = get(NEWS_URL, BROWSER_UA);
		StringBuilder sb = new StringBuilder("Latest official Old School RuneScape news:\n\n");
		Matcher m = NEWS_ARTICLE.matcher(html);
		int count = 0;
		while (m.find() && count < 12)
		{
			count++;
			sb.append("- [").append(m.group(4)).append("] ").append(htmlToText(m.group(2)))
				.append(" (").append(htmlToText(m.group(3))).append(")\n")
				.append("  ").append(htmlToText(m.group(5))).append('\n')
				.append("  ").append(m.group(1)).append("\n\n");
		}

		if (count == 0)
		{
			return "Could not read the news. Page: " + NEWS_URL;
		}
		return sb.append("Use open_page with the link to read the full post.").toString();
	}

	/** Preço pelo ItemManager do RuneLite: não faz nenhuma requisição. */
	private String gePrice(String item)
	{
		if (item.isEmpty())
		{
			return "Empty item name.";
		}
		if (itemManager == null)
		{
			return "Item prices are only available inside RuneLite.";
		}

		String wanted = item.toLowerCase(Locale.ROOT);
		List<ItemPrice> matches = new ArrayList<>();
		for (ItemPrice price : itemManager.search(item))
		{
			if (price.getName().toLowerCase(Locale.ROOT).equals(wanted))
			{
				matches.add(0, price);
			}
			else
			{
				matches.add(price);
			}
		}
		if (matches.isEmpty())
		{
			return "Item \"" + item + "\" not found on the Grand Exchange. Check the English item name.";
		}

		StringBuilder sb = new StringBuilder("Grand Exchange prices (RuneLite price data):\n\n");
		for (ItemPrice price : matches.subList(0, Math.min(matches.size(), MAX_PRICE_RESULTS)))
		{
			sb.append("- ").append(price.getName()).append(": ")
				.append(String.format(Locale.US, "%,d", itemManager.getItemPrice(price.getId()))).append(" gp\n");
		}
		return sb.toString();
	}

	static boolean isWiki(String address)
	{
		HttpUrl url = HttpUrl.parse(address);
		if (url == null)
		{
			// Endereço que não dá para interpretar: na dúvida, trata como wiki se mencionar o domínio
			return address.toLowerCase(Locale.ROOT).contains(WIKI_DOMAIN);
		}
		return url.host().equals(WIKI_DOMAIN) || url.host().endsWith("." + WIKI_DOMAIN);
	}

	private String openPage(String rawUrl) throws IOException
	{
		HttpUrl url = HttpUrl.parse(rawUrl);
		if (url == null)
		{
			return "Invalid address: " + rawUrl;
		}
		if (isWiki(url.toString()))
		{
			return WIKI_REFUSAL;
		}
		if (isPrivateHost(url.host()))
		{
			return "Local addresses are not allowed.";
		}

		String body = get(url.toString(), BROWSER_UA);
		String text = body.trim().startsWith("<") ? htmlToText(body) : body;
		return "Content of " + url + ":\n\n" + truncate(text, MAX_PAGE_CHARS);
	}

	private static boolean isPrivateHost(String host)
	{
		try
		{
			InetAddress addr = InetAddress.getByName(host);
			return addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()
				|| addr.isAnyLocalAddress();
		}
		catch (Exception e)
		{
			return true;
		}
	}

	private String get(String url, String userAgent) throws IOException
	{
		Request request = new Request.Builder()
			.url(url)
			.header("User-Agent", userAgent)
			.header("Accept-Language", "en-US,en;q=0.9")
			.build();

		try (Response response = httpClient.newCall(request).execute())
		{
			ResponseBody body = response.body();
			if (!response.isSuccessful() || body == null)
			{
				throw new IOException("HTTP " + response.code());
			}
			return body.string();
		}
	}

	static String htmlToText(String html)
	{
		String text = html
			.replaceAll("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>", " ")
			.replaceAll("(?is)<!--.*?-->", " ")
			.replaceAll("(?i)<br\\s*/?>|</(p|div|li|h[1-6]|tr|article|section)>", "\n")
			.replaceAll("<[^>]+>", " ")
			.replace("&nbsp;", " ")
			.replace("&amp;", "&")
			.replace("&quot;", "\"")
			.replace("&#39;", "'")
			.replace("&#x27;", "'")
			.replace("&lt;", "<")
			.replace("&gt;", ">")
			.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
			.replaceAll(" *\n *", "\n")
			.replaceAll("\n{3,}", "\n\n");
		return text.trim();
	}

	private static String truncate(String s, int max)
	{
		return s.length() <= max ? s : s.substring(0, max) + "\n[... content truncated]";
	}

	private static String arg(JsonObject args, String key)
	{
		JsonElement e = args != null ? args.get(key) : null;
		return e == null || e.isJsonNull() ? "" : e.getAsString().trim();
	}
}
