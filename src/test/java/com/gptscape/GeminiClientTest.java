package com.gptscape;

import com.google.gson.Gson;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Testes de integração com a API real do Gemini. Só rodam com a variável de ambiente GEMINI_API_KEY.
 */
public class GeminiClientTest
{
	private String key;
	private GeminiClient client;

	@Before
	public void setUp()
	{
		key = System.getenv("GEMINI_API_KEY");
		assumeTrue("GEMINI_API_KEY not set", key != null && !key.isEmpty());
		OkHttpClient http = new OkHttpClient();
		Gson gson = new Gson();
		client = new GeminiClient(http, gson, new WebTools(http, gson));
		client.start();
	}

	@After
	public void tearDown()
	{
		if (client != null)
		{
			client.stop();
		}
	}

	private static final class Collector implements GeminiClient.StreamListener
	{
		final List<String> deltas = Collections.synchronizedList(new ArrayList<>());
		final List<String> statuses = Collections.synchronizedList(new ArrayList<>());
		final AtomicReference<String> text = new AtomicReference<>();
		final AtomicReference<GeminiException> error = new AtomicReference<>();
		final CountDownLatch done = new CountDownLatch(1);

		@Override
		public void onStatus(String status)
		{
			statuses.add(status);
			System.out.println("STATUS " + status);
		}

		@Override
		public void onText(String delta)
		{
			deltas.add(delta);
		}

		@Override
		public void onComplete(String fullText, String model)
		{
			System.out.println("[" + model + "] (" + deltas.size() + " chunks) " + fullText);
			text.set(fullText);
			done.countDown();
		}

		@Override
		public void onError(GeminiException e)
		{
			System.out.println("ERROR " + e.getKind() + ": " + e.getMessage());
			error.set(e);
			done.countDown();
		}
	}

	private GeminiRequest request(List<ChatMessage> history, boolean web)
	{
		return new GeminiRequest(key, System.getenv().getOrDefault("GEMINI_MODEL", GeminiClient.DEFAULT_MODEL),
			history, web, () -> SystemPrompt.build(LocalDate.now(), web, "", ""));
	}

	@Test
	public void streamingEmVariosPedacos() throws Exception
	{
		Collector c = new Collector();
		client.sendStreamingMessage(request(Collections.singletonList(
			ChatMessage.user("Conte de 1 a 30 por extenso, um número por linha.")), false), c);
		assertTrue(c.done.await(3, TimeUnit.MINUTES));
		assertNull(String.valueOf(c.error.get()), c.error.get());
		assertTrue("expected several chunks, got " + c.deltas.size(), c.deltas.size() > 1);
		assertEquals(c.text.get(), String.join("", c.deltas).trim());
	}

	@Test
	public void lembraDoContexto() throws Exception
	{
		Collector c = new Collector();
		client.sendStreamingMessage(request(Arrays.asList(
			ChatMessage.user("My Slayer level is 85. Just say OK."),
			ChatMessage.model("OK."),
			ChatMessage.user("What Slayer level did I tell you? Answer with only the number.")), false), c);
		assertTrue(c.done.await(3, TimeUnit.MINUTES));
		assertNull(String.valueOf(c.error.get()), c.error.get());
		assertTrue(c.text.get(), c.text.get().contains("85"));
	}

	@Test
	public void usaFerramentasDeInternet() throws Exception
	{
		Collector c = new Collector();
		client.sendStreamingMessage(request(Collections.singletonList(
			ChatMessage.user("Quanto custa um Twisted bow agora no GE?")), true), c);
		assertTrue(c.done.await(3, TimeUnit.MINUTES));
		assertNull(String.valueOf(c.error.get()), c.error.get());
		assertTrue(c.statuses.toString(), c.statuses.stream().anyMatch(s -> s.startsWith("Checking price")));
	}

	@Test
	public void cancelarInterrompeSemCallbacks() throws Exception
	{
		Collector c = new Collector();
		GeminiClient.Generation g = client.sendStreamingMessage(request(Collections.singletonList(
			ChatMessage.user("Write a 600-word story about a dragon.")), false), c);
		Thread.sleep(1500);
		g.cancel();
		assertTrue(g.isCancelled());
		// Depois do cancelamento nenhum onComplete/onError deve chegar
		assertFalse(c.done.await(5, TimeUnit.SECONDS));
	}

	@Test
	public void chaveInvalidaGeraErroAmigavel() throws Exception
	{
		Collector c = new Collector();
		client.sendStreamingMessage(new GeminiRequest("invalid-key", GeminiClient.DEFAULT_MODEL,
			Collections.singletonList(ChatMessage.user("hi")), false, () -> ""), c);
		assertTrue(c.done.await(1, TimeUnit.MINUTES));
		assertEquals(GeminiException.Kind.INVALID_KEY, c.error.get().getKind());
		assertEquals("Invalid Gemini API key. Check your key in the plugin settings.", c.error.get().getUserMessage());
	}
}
