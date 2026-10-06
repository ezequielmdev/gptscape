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
	private static final String WIKI_UA = "GPTScape-RuneLite/1.0 (RuneLite plugin)";
	private static final String NEWS_URL = "https://secure.runescape.com/m=news/archive?oldschool=1";
	private static final String WIKI_API = "https://oldschool.runescape.wiki/api.php";
	private static final String WIKI_PAGE = "https://oldschool.runescape.wiki/w/";
	private static final String PRICES_API = "https://prices.runescape.wiki/api/v1/osrs/";
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

	/** Lista de itens do GE (id, nome, limite), baixada uma vez por sessão. */
	private volatile JsonArray itemMapping;

	@Inject
	WebTools(OkHttpClient httpClient, Gson gson)
	{
		this.httpClient = httpClient.newBuilder()
			.connectTimeout(10, TimeUnit.SECONDS)
			.readTimeout(20, TimeUnit.SECONDS)
			.followRedirects(true)
			.build();
		this.gson = gson;
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
		tools.add(tool("osrs_wiki",
			"Searches the official Old School RuneScape Wiki and returns the most relevant article. "
				+ "Use it for items, monsters, bosses, quests, skills, gear and strategies.",
			"query", "Item, monster, quest or topic name in English (e.g. 'Theatre of Blood/Strategies')."));
		tools.add(tool("ge_price",
			"Real-time Grand Exchange price of an OSRS item (Wiki data). Always use it for questions about "
				+ "an item's price, value or cost.",
			"item", "Item name in English (e.g. 'Twisted bow', 'Abyssal whip')."));
		tools.add(tool("open_page",
			"Opens a web page and returns its text. Use it to read a link found in a search or a full news post.",
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
			case "osrs_wiki":
				return "Checking the OSRS Wiki: " + arg(args, "query");
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
				case "osrs_wiki":
					return osrsWiki(arg(args, "query"));
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
		String html = get(url.toString(), BROWSER_UA);

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

	private String osrsWiki(String query) throws IOException
	{
		if (query.isEmpty())
		{
			return "Empty query.";
		}

		HttpUrl searchUrl = HttpUrl.parse(WIKI_API).newBuilder()
			.addQueryParameter("action", "query")
			.addQueryParameter("list", "search")
			.addQueryParameter("srsearch", query)
			.addQueryParameter("srlimit", "5")
			.addQueryParameter("format", "json")
			.build();
		JsonArray results = gson.fromJson(get(searchUrl.toString(), WIKI_UA), JsonObject.class)
			.getAsJsonObject("query").getAsJsonArray("search");
		if (results.size() == 0)
		{
			return "Nothing found on the OSRS Wiki for \"" + query + "\".";
		}

		String title = results.get(0).getAsJsonObject().get("title").getAsString();
		HttpUrl extractUrl = HttpUrl.parse(WIKI_API).newBuilder()
			.addQueryParameter("action", "query")
			.addQueryParameter("prop", "extracts")
			.addQueryParameter("explaintext", "1")
			.addQueryParameter("redirects", "1")
			.addQueryParameter("titles", title)
			.addQueryParameter("format", "json")
			.build();
		JsonObject pages = gson.fromJson(get(extractUrl.toString(), WIKI_UA), JsonObject.class)
			.getAsJsonObject("query").getAsJsonObject("pages");
		String extract = "";
		for (java.util.Map.Entry<String, JsonElement> e : pages.entrySet())
		{
			JsonElement ex = e.getValue().getAsJsonObject().get("extract");
			if (ex != null && !ex.isJsonNull())
			{
				extract = ex.getAsString();
			}
		}

		StringBuilder sb = new StringBuilder("OSRS Wiki - ").append(title).append('\n')
			.append(WIKI_PAGE).append(title.replace(' ', '_')).append("\n\n")
			.append(truncate(extract.replaceAll("\n{3,}", "\n\n"), MAX_PAGE_CHARS));

		if (results.size() > 1)
		{
			sb.append("\n\nRelated articles: ");
			for (int i = 1; i < results.size(); i++)
			{
				sb.append(i > 1 ? ", " : "").append(results.get(i).getAsJsonObject().get("title").getAsString());
			}
		}
		return sb.toString();
	}

	private String gePrice(String item) throws IOException
	{
		if (item.isEmpty())
		{
			return "Empty item name.";
		}
		if (itemMapping == null)
		{
			itemMapping = gson.fromJson(get(PRICES_API + "mapping", WIKI_UA), JsonArray.class);
		}

		String wanted = item.toLowerCase().trim();
		List<JsonObject> matches = new ArrayList<>();
		for (JsonElement el : itemMapping)
		{
			JsonObject o = el.getAsJsonObject();
			String name = o.get("name").getAsString().toLowerCase();
			if (name.equals(wanted))
			{
				matches.add(0, o);
			}
			else if (name.contains(wanted) && matches.size() < 6)
			{
				matches.add(o);
			}
		}
		if (matches.isEmpty())
		{
			return "Item \"" + item + "\" not found on the Grand Exchange. Check the English item name.";
		}
		if (matches.size() > 6)
		{
			matches = matches.subList(0, 6);
		}

		// A API aceita só um id por consulta
		JsonObject latest = new JsonObject();
		for (JsonObject o : matches)
		{
			String id = o.get("id").getAsString();
			JsonObject data = gson.fromJson(get(PRICES_API + "latest?id=" + id, WIKI_UA), JsonObject.class)
				.getAsJsonObject("data");
			if (data != null && data.has(id))
			{
				latest.add(id, data.get(id));
			}
		}

		StringBuilder sb = new StringBuilder("Real-time Grand Exchange prices (prices.runescape.wiki):\n\n");
		long now = System.currentTimeMillis() / 1000;
		for (JsonObject o : matches)
		{
			String id = o.get("id").getAsString();
			sb.append("- ").append(o.get("name").getAsString()).append(" (id ").append(id).append(")\n");
			JsonObject p = latest != null ? latest.getAsJsonObject(id) : null;
			if (p == null)
			{
				sb.append("  No recent trades.\n");
				continue;
			}
			appendPrice(sb, "Instant buy (high)", p, "high", "highTime", now);
			appendPrice(sb, "Instant sell (low)", p, "low", "lowTime", now);
			if (o.has("limit"))
			{
				sb.append("  Buy limit: ").append(o.get("limit").getAsInt()).append(" per 4h\n");
			}
			sb.append("  https://prices.runescape.wiki/osrs/item/").append(id).append('\n');
		}
		return sb.toString();
	}

	private static void appendPrice(StringBuilder sb, String label, JsonObject p, String key, String timeKey, long now)
	{
		JsonElement v = p.get(key);
		if (v == null || v.isJsonNull())
		{
			return;
		}
		sb.append("  ").append(label).append(": ").append(String.format(Locale.US, "%,d", v.getAsLong()))
			.append(" gp");
		JsonElement t = p.get(timeKey);
		if (t != null && !t.isJsonNull())
		{
			long minutes = Math.max(0, (now - t.getAsLong()) / 60);
			sb.append(" (").append(minutes < 60 ? minutes + " min" : (minutes / 60) + " h").append(" ago)");
		}
		sb.append('\n');
	}

	private String openPage(String rawUrl) throws IOException
	{
		HttpUrl url = HttpUrl.parse(rawUrl);
		if (url == null)
		{
			return "Invalid address: " + rawUrl;
		}
		if (isPrivateHost(url.host()))
		{
			return "Local addresses are not allowed.";
		}

		String body = get(url.toString(), url.host().endsWith("runescape.wiki") ? WIKI_UA : BROWSER_UA);
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
