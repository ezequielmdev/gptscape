package com.gptscape;

import com.google.gson.Gson;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import okhttp3.OkHttpClient;

/**
 * Ferramenta de desenvolvimento: monta o painel real fora da tela, conversa com a API e salva capturas PNG.
 * Uso: GEMINI_API_KEY=... PREVIEW_DIR=pasta  (roda via gradle task "preview").
 */
public final class PanelPreview
{
	private static GptScapePanel panel;
	private static JFrame frame;

	public static void main(String[] args) throws Exception
	{
		String key = System.getenv().getOrDefault("GEMINI_API_KEY", "");
		File out = new File(System.getenv().getOrDefault("PREVIEW_DIR", "build/preview"));
		out.mkdirs();

		GptScapeConfig config = (GptScapeConfig) Proxy.newProxyInstance(GptScapeConfig.class.getClassLoader(),
			new Class<?>[]{GptScapeConfig.class}, (proxy, method, a) ->
			{
				switch (method.getName())
				{
					case "apiKey":
						return key;
					case "model":
						return System.getenv().getOrDefault("GEMINI_MODEL", GeminiClient.DEFAULT_MODEL);
					case "internet":
						return true;
					case "saveHistory":
					case "shareGameStats":
					case "shareAccountData":
					case "shareBank":
						return false;
					case "maxHistory":
						return 30;
					case "systemPrompt":
						return "";
					case "websiteUrl":
						return "https://gemini.google.com/";
					default:
						return null;
				}
			});

		OkHttpClient http = new OkHttpClient();
		Gson gson = new Gson();
		GeminiClient client = new GeminiClient(http, gson, new WebTools(http, gson));
		client.start();
		ChatHistoryStore store = new ChatHistoryStore(gson, new File(out, "history.json"));

		SwingUtilities.invokeAndWait(() ->
		{
			net.runelite.client.ui.laf.RuneLiteLAF.setup();
			panel = new GptScapePanel(config, null, client, store, new RuneLiteGameContextProvider(null, null, config));
			frame = new JFrame("preview");
			frame.setUndecorated(true);
			frame.setContentPane(panel);
			frame.setSize(242, 720);
			frame.setLocation(-4000, 0);
			frame.setVisible(true);
		});
		snap(out, "1-empty");
		SwingUtilities.invokeAndWait(() -> panel.requestFocusInWindow());

		SwingUtilities.invokeAndWait(() -> panel.restore(Arrays.asList(
			ChatMessage.user("Como faço o Theatre of Blood?"),
			ChatMessage.model("## Theatre of Blood\n\nO **Theatre of Blood** é uma raid em *Ver Sinhaza*. Veja a "
				+ "[Wiki](https://oldschool.runescape.wiki/w/Theatre_of_Blood).\n\n1. Maiden\n2. Bloat\n3. Nylocas\n\n"
				+ "- Use `Protect from Magic`\n  - sub item\n\n| Boss | HP |\n|---|---|\n| Verzik | 2000 |\n\n"
				+ "```java\npublic void exemplo()\n{\n    System.out.println(\"RuneLite\");\n}\n```\n\nBoa sorte!"))));
		snap(out, "2-markdown");

		// Como fica a mensagem quando a cota gratuita do dia acaba
		SwingUtilities.invokeAndWait(() ->
		{
			MessageListPanel list = new MessageListPanel();
			list.add(new UserMessageView("Quanto custa um Twisted bow?"));
			ModelMessageView view = new ModelMessageView(new ModelMessageView.Listener()
			{
				@Override
				public void onRegenerate(ModelMessageView v)
				{
				}

				@Override
				public void onRetry(ModelMessageView v)
				{
				}
			});
			view.showError(new GeminiException(GeminiException.Kind.FREE_QUOTA_EXHAUSTED, "").getUserMessage(), true,
				GeminiException.resetDescription(36254, java.time.ZonedDateTime.now())
					+ "\n\nWant unlimited messages? Turn on billing for your API key in Google AI Studio "
					+ "(API keys → Set up billing). Your key stays the same, and most messages cost less than a cent.",
				"Get unlimited messages", () -> { });
			list.add(view);
			JFrame quota = new JFrame("quota");
			quota.setUndecorated(true);
			quota.setContentPane(list);
			quota.setSize(242, 330);
			quota.setLocation(-4000, 800);
			quota.setVisible(true);
			quota.validate();
			BufferedImage img = new BufferedImage(242, 330, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = img.createGraphics();
			list.printAll(g);
			g.dispose();
			try
			{
				ImageIO.write(img, "png", new File(out, "6-quota.png"));
			}
			catch (java.io.IOException e)
			{
				throw new IllegalStateException(e);
			}
			quota.dispose();
		});
		if (System.getenv("PREVIEW_QUOTA_ONLY") != null)
		{
			System.exit(0);
		}

		if (key.isEmpty())
		{
			System.exit(0);
		}

		SwingUtilities.invokeAndWait(() ->
		{
			panel.setInputText("Explique em 4 parágrafos curtos como funciona o Slayer no OSRS.");
			panel.sendMessage();
		});
		snap(out, "3-thinking");
		Thread.sleep(2500);
		snap(out, "4-streaming");

		long deadline = System.currentTimeMillis() + 120_000;
		while (System.currentTimeMillis() < deadline && panel.getState() == GptScapePanel.ChatState.GENERATING)
		{
			Thread.sleep(500);
		}
		Thread.sleep(300);
		snap(out, "5-done");
		client.stop();
		System.exit(0);
	}

	private static void snap(File dir, String name) throws Exception
	{
		Thread.sleep(150);
		BufferedImage[] image = new BufferedImage[1];
		SwingUtilities.invokeAndWait(() ->
		{
			frame.validate();
			image[0] = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
			Graphics2D g = image[0].createGraphics();
			frame.getContentPane().printAll(g);
			g.dispose();
		});
		ImageIO.write(image[0], "png", new File(dir, name + ".png"));
		System.out.println("saved " + name + " state=" + panel.getState());
	}
}
