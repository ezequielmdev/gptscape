package com.gptscape;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** Montagem da requisição e leitura das respostas/erros da API, sem rede. */
public class GeminiRequestFormatTest
{
	private final Gson gson = new Gson();

	@Test
	public void historicoViraContentsUserModel()
	{
		List<JsonObject> contents = GeminiClient.toContents(Arrays.asList(
			ChatMessage.user("a"), ChatMessage.model("b"), ChatMessage.user("c"), ChatMessage.user("d")));
		assertEquals(3, contents.size());
		assertEquals("user", contents.get(0).get("role").getAsString());
		assertEquals("model", contents.get(1).get("role").getAsString());
		// Mensagens seguidas do mesmo papel viram partes do mesmo content
		assertEquals(2, contents.get(2).getAsJsonArray("parts").size());
	}

	@Test
	public void requisicaoTemSystemInstructionFerramentasEThinking()
	{
		JsonObject tools = new JsonObject();
		JsonObject body = GeminiClient.buildRequest("sys",
			GeminiClient.toContents(Collections.singletonList(ChatMessage.user("hi"))), tools, true);
		assertEquals("sys", body.getAsJsonObject("systemInstruction").getAsJsonArray("parts")
			.get(0).getAsJsonObject().get("text").getAsString());
		assertEquals(1, body.getAsJsonArray("contents").size());
		assertEquals(1, body.getAsJsonArray("tools").size());
		assertEquals("low", body.getAsJsonObject("generationConfig").getAsJsonObject("thinkingConfig")
			.get("thinkingLevel").getAsString());

		JsonObject plain = GeminiClient.buildRequest("sys",
			GeminiClient.toContents(Collections.singletonList(ChatMessage.user("hi"))), null, false);
		assertFalse(plain.has("tools"));
		assertFalse(plain.has("generationConfig"));
	}

	@Test
	public void leTextoDoChunk()
	{
		JsonObject chunk = gson.fromJson("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"O Theatre\"}],"
			+ "\"role\":\"model\"},\"index\":0}]}", JsonObject.class);
		GeminiClient.Chunk parsed = GeminiClient.parseChunk(chunk);
		assertEquals(Collections.singletonList("O Theatre"), parsed.texts);
		assertTrue(parsed.functionCalls.isEmpty());
	}

	@Test
	public void leChamadaDeFerramentaComAssinatura()
	{
		JsonObject chunk = gson.fromJson("{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":"
			+ "{\"name\":\"ge_price\",\"args\":{\"item\":\"Twisted bow\"},\"id\":\"call_1\"},"
			+ "\"thoughtSignature\":\"abc\"}],\"role\":\"model\"}}]}", JsonObject.class);
		GeminiClient.Chunk parsed = GeminiClient.parseChunk(chunk);
		assertEquals(1, parsed.functionCalls.size());
		assertEquals("ge_price", parsed.functionCalls.get(0).get("name").getAsString());
		assertTrue(parsed.parts.get(0).has("thoughtSignature"));
	}

	@Test
	public void ignoraPensamentosEDetectaBloqueio()
	{
		JsonObject chunk = gson.fromJson("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"secret\","
			+ "\"thought\":true}]},\"finishReason\":\"SAFETY\"}]}", JsonObject.class);
		GeminiClient.Chunk parsed = GeminiClient.parseChunk(chunk);
		assertTrue(parsed.texts.isEmpty());
		assertTrue(parsed.blocked);
	}

	@Test
	public void classificaErrosDaApi()
	{
		assertEquals(GeminiException.Kind.INVALID_KEY, GeminiClient.errorFromJson(400, gson.fromJson(
			"{\"error\":{\"code\":400,\"message\":\"API key not valid.\",\"status\":\"INVALID_ARGUMENT\"}}",
			JsonObject.class)).getKind());
		assertEquals(GeminiException.Kind.RATE_LIMIT, GeminiException.fromHttp(429, "RESOURCE_EXHAUSTED", "quota").getKind());
		assertEquals(GeminiException.Kind.FORBIDDEN, GeminiException.fromHttp(403, "PERMISSION_DENIED", "denied").getKind());
		assertEquals(GeminiException.Kind.MODEL_NOT_FOUND, GeminiException.fromHttp(404, "NOT_FOUND", "no model").getKind());
		assertEquals(GeminiException.Kind.SERVER, GeminiException.fromHttp(503, "UNAVAILABLE", "busy").getKind());
		assertEquals(GeminiException.Kind.TIMEOUT, GeminiException.fromHttp(504, null, null).getKind());
		assertEquals(GeminiException.Kind.BAD_REQUEST, GeminiException.fromHttp(400, "INVALID_ARGUMENT", "bad").getKind());
	}

	@Test
	public void cotaDiariaGratuitaEsgotadaComTempoParaRenovar()
	{
		// Corpo real devolvido pela API quando a cota gratuita do dia acaba
		JsonObject body = gson.fromJson("{\"error\":{\"code\":429,\"message\":\"You exceeded your current quota\","
			+ "\"status\":\"RESOURCE_EXHAUSTED\",\"details\":["
			+ "{\"@type\":\"type.googleapis.com/google.rpc.QuotaFailure\",\"violations\":[{\"quotaMetric\":"
			+ "\"generativelanguage.googleapis.com/generate_content_free_tier_requests\",\"quotaId\":"
			+ "\"GenerateRequestsPerDayPerProjectPerModel-FreeTier\",\"quotaValue\":\"20\"}]},"
			+ "{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\":\"36254s\"}]}}", JsonObject.class);
		GeminiException e = GeminiClient.errorFromJson(429, body);
		assertEquals(GeminiException.Kind.FREE_QUOTA_EXHAUSTED, e.getKind());
		assertEquals(36254, e.getRetryAfterSeconds());
		assertTrue(e.getUserMessage().startsWith("You've used today's free Gemini messages"));
	}

	@Test
	public void limitePorMinutoNaoEhCotaDiaria()
	{
		JsonObject body = gson.fromJson("{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\",\"details\":["
			+ "{\"@type\":\"type.googleapis.com/google.rpc.QuotaFailure\",\"violations\":[{\"quotaId\":"
			+ "\"GenerateRequestsPerMinutePerProjectPerModel-FreeTier\"}]},"
			+ "{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\":\"21.5s\"}]}}", JsonObject.class);
		GeminiException e = GeminiClient.errorFromJson(429, body);
		assertEquals(GeminiException.Kind.RATE_LIMIT, e.getKind());
		assertEquals(22, e.getRetryAfterSeconds());
	}

	@Test
	public void descreveQuandoACotaVolta()
	{
		java.time.ZonedDateTime now = java.time.ZonedDateTime.of(2026, 10, 6, 17, 56, 0, 0,
			java.time.ZoneId.of("America/Sao_Paulo"));
		assertEquals("Free messages reset at 4:00 AM (in 10h 4m).", GeminiException.resetDescription(36240, now));
		// Sem RetryInfo: próxima meia-noite do Pacífico (PDT = UTC-7 → 04:00 em São Paulo)
		assertEquals("Free messages reset at 4:00 AM (in 10h 4m).", GeminiException.resetDescription(-1, now));
	}

	@Test
	public void modeloConfiguradoVemPrimeiroSemRepetir()
	{
		List<String> models = GeminiClient.modelsToTry("gemini-3.1-flash-lite");
		assertEquals("gemini-3.1-flash-lite", models.get(0));
		assertEquals(models.size(), models.stream().distinct().count());
		assertEquals(GeminiClient.DEFAULT_MODEL, GeminiClient.modelsToTry(" ").get(0));
	}

	@Test
	public void promptDoSistemaTemRegraDeIdiomaEContextoOpcional()
	{
		String prompt = SystemPrompt.build(java.time.LocalDate.of(2026, 10, 6), true, "Keep it short", "Combat level: 100");
		assertTrue(prompt.contains("same language as the user's latest message"));
		assertTrue(prompt.contains("Theatre of Blood"));
		assertTrue(prompt.contains("Tuesday, October 6, 2026"));
		assertTrue(prompt.contains("Combat level: 100"));
		assertTrue(prompt.contains("Keep it short"));
		assertFalse(SystemPrompt.build(java.time.LocalDate.now(), false, "", "").contains("Player context"));
	}
}
