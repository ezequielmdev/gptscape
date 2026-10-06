package com.gptscape;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * Toda a comunicação com a API oficial do Google Gemini (generativelanguage.googleapis.com).
 * <ul>
 *   <li>Streaming via {@code streamGenerateContent?alt=sse}, com parser SSE próprio.</li>
 *   <li>Executa as ferramentas de internet ({@link WebTools}) quando o modelo pede (function calling).</li>
 *   <li>Se um modelo falhar antes de enviar qualquer texto (sobrecarga, cota, modelo aposentado),
 *       tenta o próximo modelo gratuito, uma única vez cada.</li>
 *   <li>Rede sempre fora da EDT, em um executor pequeno criado/encerrado com o plugin.</li>
 * </ul>
 */
@Slf4j
@Singleton
public class GeminiClient
{
	/**
	 * Único lugar com nomes de modelos (a configuração usa {@link #DEFAULT_MODEL} como padrão).
	 * O Flash-Lite é o padrão porque tem a maior cota diária no plano gratuito.
	 */
	public static final String DEFAULT_MODEL = "gemini-3.5-flash-lite";
	static final List<String> FALLBACK_MODELS = Collections.unmodifiableList(Arrays.asList(
		DEFAULT_MODEL,
		"gemini-3.5-flash",
		"gemini-flash-latest",
		"gemini-3.1-flash-lite"
	));

	private static final String API_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final int MAX_TOOL_ROUNDS = 6;

	static final String STATUS_THINKING = "Thinking";
	static final String STATUS_RETRYING = "Retrying";

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final WebTools webTools;
	private ExecutorService executor;

	@Inject
	GeminiClient(OkHttpClient httpClient, Gson gson, WebTools webTools)
	{
		// Timeouts próprios: conexão curta, leitura tolerante ao "thinking" do modelo e às pausas do stream
		this.httpClient = httpClient.newBuilder()
			.connectTimeout(15, TimeUnit.SECONDS)
			.writeTimeout(30, TimeUnit.SECONDS)
			.readTimeout(60, TimeUnit.SECONDS)
			.build();
		this.gson = gson;
		this.webTools = webTools;
	}

	/** Recebe o progresso de uma geração. Chamado fora da EDT. */
	public interface StreamListener
	{
		/** Status curto (ex.: "Searching: zulrah guide"), mostrado enquanto não há texto novo. */
		void onStatus(String status);

		/** Novo trecho de texto da resposta. */
		void onText(String delta);

		void onComplete(String fullText, String model);

		void onError(GeminiException error);
	}

	/** Geração em andamento. {@link #cancel()} pode ser chamado de qualquer thread. */
	public static final class Generation
	{
		private volatile boolean cancelled;
		private volatile Call call;
		private volatile Future<?> future;

		public void cancel()
		{
			cancelled = true;
			Call c = call;
			if (c != null)
			{
				c.cancel();
			}
			Future<?> f = future;
			if (f != null)
			{
				f.cancel(true);
			}
		}

		public boolean isCancelled()
		{
			return cancelled;
		}
	}

	/** Resultado final de uma geração. */
	public static final class Result
	{
		public final String text;
		public final String model;

		Result(String text, String model)
		{
			this.text = text;
			this.model = model;
		}
	}

	public synchronized void start()
	{
		executor();
	}

	/** Cancela tudo e encerra a thread do cliente (plugin desativado). */
	public synchronized void stop()
	{
		if (executor != null)
		{
			executor.shutdownNow();
			executor = null;
		}
	}

	private synchronized ExecutorService executor()
	{
		if (executor == null || executor.isShutdown())
		{
			// Duas threads: uma geração cancelada que ainda termina uma ferramenta não atrasa a próxima
			executor = Executors.newFixedThreadPool(2, r ->
			{
				Thread t = new Thread(r, "gemini-chat");
				t.setDaemon(true);
				return t;
			});
		}
		return executor;
	}

	/** Inicia uma geração com streaming em segundo plano. */
	public Generation sendStreamingMessage(GeminiRequest request, StreamListener listener)
	{
		Generation generation = new Generation();
		StreamListener guarded = new GuardedListener(generation, listener);
		generation.future = executor().submit(() -> run(generation, request, guarded));
		return generation;
	}

	/** Versão bloqueante, sem streaming. Nunca chame na EDT. */
	public Result sendMessage(GeminiRequest request) throws GeminiException
	{
		if (SwingUtilities.isEventDispatchThread())
		{
			throw new IllegalStateException("Gemini requests must not run on the Swing event dispatch thread");
		}
		Generation generation = new Generation();
		return generate(generation, request, new GuardedListener(generation, null));
	}

	private void run(Generation generation, GeminiRequest request, StreamListener listener)
	{
		try
		{
			Result result = generate(generation, request, listener);
			listener.onComplete(result.text, result.model);
		}
		catch (GeminiException e)
		{
			if (generation.isCancelled() || e.getKind() == GeminiException.Kind.CANCELLED)
			{
				log.warn("Gemini request cancelled");
				return;
			}
			log.error("Gemini request failed ({})", e.getKind(), e);
			listener.onError(e);
		}
		catch (RuntimeException e)
		{
			if (generation.isCancelled())
			{
				log.warn("Gemini request cancelled");
				return;
			}
			log.error("Gemini request failed unexpectedly", e);
			listener.onError(new GeminiException(GeminiException.Kind.BAD_RESPONSE, 0, e.toString(), e));
		}
		finally
		{
			generation.call = null;
		}
	}

	private Result generate(Generation generation, GeminiRequest request, StreamListener listener) throws GeminiException
	{
		listener.onStatus(STATUS_THINKING);
		String system = request.getSystemInstruction().get();
		List<JsonObject> history = toContents(request.getHistory());
		if (history.isEmpty())
		{
			throw new GeminiException(GeminiException.Kind.BAD_REQUEST, "Empty conversation");
		}

		List<String> models = modelsToTry(request.getModel());
		GeminiException last = null;
		GeminiException dailyQuota = null;
		for (int i = 0; i < models.size(); i++)
		{
			String model = models.get(i);
			StringBuilder text = new StringBuilder();
			try
			{
				return new Result(generateWithModel(generation, request, model, system, history, listener, text), model);
			}
			catch (GeminiException e)
			{
				checkCancelled(generation);
				last = e;
				if (dailyQuota == null && e.getKind() == GeminiException.Kind.FREE_QUOTA_EXHAUSTED)
				{
					dailyQuota = e;
				}
				boolean canFallBack = text.length() == 0 && e.shouldTryOtherModel() && i + 1 < models.size();
				if (!canFallBack)
				{
					// Nenhum modelo gratuito respondeu e algum estava sem cota: o motivo mais útil é a cota diária
					if (dailyQuota != null && text.length() == 0 && e.shouldTryOtherModel())
					{
						throw dailyQuota;
					}
					throw e;
				}
				log.warn("Gemini model {} failed ({}), trying {}", model, e.getMessage(), models.get(i + 1));
				listener.onStatus(STATUS_RETRYING);
			}
		}
		throw last != null ? last : new GeminiException(GeminiException.Kind.EMPTY, "No model available");
	}

	static List<String> modelsToTry(String configured)
	{
		List<String> models = new ArrayList<>();
		String first = configured == null || configured.trim().isEmpty() ? DEFAULT_MODEL : configured.trim();
		models.add(first);
		for (String m : FALLBACK_MODELS)
		{
			if (!models.contains(m))
			{
				models.add(m);
			}
		}
		return models;
	}

	/** Conversa com um modelo, executando ferramentas quando pedido, até a resposta final. */
	private String generateWithModel(Generation generation, GeminiRequest request, String model, String system,
		List<JsonObject> history, StreamListener listener, StringBuilder text) throws GeminiException
	{
		List<JsonObject> contents = new ArrayList<>(history);
		boolean lowThinking = true;
		int round = 0;
		while (true)
		{
			checkCancelled(generation);
			boolean offerTools = request.isWebAccess() && round < MAX_TOOL_ROUNDS;
			JsonObject body = buildRequest(system, contents, offerTools ? webTools.toolDeclarations() : null, lowThinking);

			Turn turn;
			try
			{
				turn = streamTurn(generation, request.getApiKey(), model, body, listener, text);
			}
			catch (GeminiException e)
			{
				// Modelos que não aceitam thinkingLevel: repete sem a configuração de raciocínio
				if (lowThinking && text.length() == 0 && e.getKind() == GeminiException.Kind.BAD_REQUEST
					&& String.valueOf(e.getMessage()).toLowerCase().contains("thinking"))
				{
					lowThinking = false;
					continue;
				}
				throw e;
			}
			round++;

			if (!turn.functionCalls.isEmpty() && offerTools)
			{
				contents.add(turn.modelContent());
				contents.add(runTools(generation, turn.functionCalls, listener));
				listener.onStatus(STATUS_THINKING);
				continue;
			}

			String result = text.toString().trim();
			if (result.isEmpty())
			{
				throw new GeminiException(turn.blocked ? GeminiException.Kind.BLOCKED : GeminiException.Kind.EMPTY,
					"No text in response (finishReason=" + turn.finishReason + ")");
			}
			return result;
		}
	}

	private JsonObject runTools(Generation generation, List<JsonObject> calls, StreamListener listener)
		throws GeminiException
	{
		JsonArray responses = new JsonArray();
		for (JsonObject call : calls)
		{
			checkCancelled(generation);
			String name = call.has("name") ? call.get("name").getAsString() : "";
			JsonObject args = call.has("args") && call.get("args").isJsonObject()
				? call.getAsJsonObject("args") : new JsonObject();

			listener.onStatus(WebTools.describe(name, args));
			String result;
			try
			{
				result = webTools.execute(name, args);
			}
			catch (RuntimeException e)
			{
				log.debug("Tool {} failed", name, e);
				result = "Error running " + name + ": " + e.getMessage();
			}

			JsonObject response = new JsonObject();
			response.addProperty("result", result);
			JsonObject functionResponse = new JsonObject();
			functionResponse.addProperty("name", name);
			if (call.has("id"))
			{
				functionResponse.add("id", call.get("id"));
			}
			functionResponse.add("response", response);
			JsonObject part = new JsonObject();
			part.add("functionResponse", functionResponse);
			responses.add(part);
		}
		JsonObject content = new JsonObject();
		content.addProperty("role", "user");
		content.add("parts", responses);
		return content;
	}

	/** Uma chamada de streaming: lê o SSE, repassa o texto e coleta chamadas de ferramenta. */
	private Turn streamTurn(Generation generation, String apiKey, String model, JsonObject body,
		StreamListener listener, StringBuilder text) throws GeminiException
	{
		Request request = new Request.Builder()
			.url(API_BASE + model + ":streamGenerateContent?alt=sse")
			.header("x-goog-api-key", apiKey)
			.header("Accept", "text/event-stream")
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();

		Call call = httpClient.newCall(request);
		generation.call = call;
		checkCancelled(generation);
		log.debug("Starting Gemini request (model={})", model);

		Turn turn = new Turn(text.length() > 0);
		try (Response response = call.execute())
		{
			ResponseBody responseBody = response.body();
			if (!response.isSuccessful())
			{
				throw parseError(response.code(), responseBody != null ? responseBody.string() : "");
			}
			if (responseBody == null)
			{
				throw new GeminiException(GeminiException.Kind.BAD_RESPONSE, "Empty response body");
			}

			SseParser parser = new SseParser(data -> handleEvent(data, turn, listener, text));
			BufferedSource source = responseBody.source();
			String line;
			while ((line = source.readUtf8Line()) != null)
			{
				checkCancelled(generation);
				parser.feedLine(line);
			}
			parser.finish();
			log.debug("Gemini stream completed (model={})", model);
			return turn;
		}
		catch (EventException e)
		{
			throw e.error;
		}
		catch (SocketTimeoutException e)
		{
			checkCancelled(generation);
			throw new GeminiException(GeminiException.Kind.TIMEOUT, 0, e.toString(), e);
		}
		catch (InterruptedIOException e)
		{
			checkCancelled(generation);
			throw new GeminiException(GeminiException.Kind.TIMEOUT, 0, e.toString(), e);
		}
		catch (UnknownHostException | ConnectException e)
		{
			checkCancelled(generation);
			throw new GeminiException(GeminiException.Kind.NETWORK, 0, e.toString(), e);
		}
		catch (IOException e)
		{
			checkCancelled(generation);
			throw new GeminiException(GeminiException.Kind.NETWORK, 0, e.toString(), e);
		}
	}

	private void handleEvent(String data, Turn turn, StreamListener listener, StringBuilder text)
	{
		JsonObject chunk;
		try
		{
			chunk = gson.fromJson(data, JsonObject.class);
		}
		catch (JsonParseException e)
		{
			throw new EventException(new GeminiException(GeminiException.Kind.BAD_RESPONSE, 0,
				"Invalid JSON in stream event", e));
		}
		if (chunk == null)
		{
			return;
		}
		if (chunk.has("error"))
		{
			throw new EventException(errorFromJson(500, chunk));
		}

		Chunk parsed = parseChunk(chunk);
		turn.parts.addAll(parsed.parts);
		turn.functionCalls.addAll(parsed.functionCalls);
		turn.blocked |= parsed.blocked;
		if (parsed.finishReason != null)
		{
			turn.finishReason = parsed.finishReason;
		}

		for (String delta : parsed.texts)
		{
			if (delta.isEmpty())
			{
				continue;
			}
			// Texto depois de uma rodada de ferramentas: separa do texto anterior
			if (turn.continuesText && !turn.emittedText && text.length() > 0 && !text.toString().endsWith("\n"))
			{
				text.append("\n\n");
				listener.onText("\n\n");
			}
			turn.emittedText = true;
			text.append(delta);
			listener.onText(delta);
		}
	}

	// ---------------------------------------------------------------- montagem e leitura de JSON (testáveis)

	/** Converte o histórico em "contents" (user/model), juntando mensagens seguidas do mesmo papel. */
	static List<JsonObject> toContents(List<ChatMessage> history)
	{
		List<JsonObject> contents = new ArrayList<>();
		ChatMessage.Role lastRole = null;
		JsonArray lastParts = null;
		for (ChatMessage m : history)
		{
			if (m.getContent() == null || m.getContent().trim().isEmpty())
			{
				continue;
			}
			JsonObject part = new JsonObject();
			part.addProperty("text", m.getContent());
			if (m.getRole() == lastRole && lastParts != null)
			{
				lastParts.add(part);
				continue;
			}
			JsonObject content = new JsonObject();
			content.addProperty("role", m.getRole() == ChatMessage.Role.USER ? "user" : "model");
			lastParts = new JsonArray();
			lastParts.add(part);
			content.add("parts", lastParts);
			contents.add(content);
			lastRole = m.getRole();
		}
		return contents;
	}

	static JsonObject buildRequest(String systemInstruction, List<JsonObject> contents, JsonObject tools,
		boolean lowThinking)
	{
		JsonObject body = new JsonObject();
		if (systemInstruction != null && !systemInstruction.isEmpty())
		{
			JsonObject part = new JsonObject();
			part.addProperty("text", systemInstruction);
			JsonArray parts = new JsonArray();
			parts.add(part);
			JsonObject system = new JsonObject();
			system.add("parts", parts);
			body.add("systemInstruction", system);
		}

		JsonArray contentArray = new JsonArray();
		for (JsonObject c : contents)
		{
			contentArray.add(c);
		}
		body.add("contents", contentArray);

		if (tools != null)
		{
			JsonArray toolArray = new JsonArray();
			toolArray.add(tools);
			body.add("tools", toolArray);
		}

		if (lowThinking)
		{
			// Menos "thinking" = primeira palavra chega bem mais rápido
			JsonObject thinking = new JsonObject();
			thinking.addProperty("thinkingLevel", "low");
			JsonObject generationConfig = new JsonObject();
			generationConfig.add("thinkingConfig", thinking);
			body.add("generationConfig", generationConfig);
		}
		return body;
	}

	/** Conteúdo de um evento do stream. */
	static final class Chunk
	{
		final List<String> texts = new ArrayList<>();
		final List<JsonObject> functionCalls = new ArrayList<>();
		final List<JsonObject> parts = new ArrayList<>();
		String finishReason;
		boolean blocked;
	}

	static Chunk parseChunk(JsonObject chunk)
	{
		Chunk result = new Chunk();
		if (chunk.has("promptFeedback") && chunk.get("promptFeedback").isJsonObject()
			&& chunk.getAsJsonObject("promptFeedback").has("blockReason"))
		{
			result.blocked = true;
		}

		JsonArray candidates = chunk.has("candidates") && chunk.get("candidates").isJsonArray()
			? chunk.getAsJsonArray("candidates") : null;
		if (candidates == null || candidates.size() == 0 || !candidates.get(0).isJsonObject())
		{
			return result;
		}

		JsonObject candidate = candidates.get(0).getAsJsonObject();
		if (candidate.has("finishReason"))
		{
			result.finishReason = candidate.get("finishReason").getAsString();
			switch (result.finishReason)
			{
				case "SAFETY":
				case "PROHIBITED_CONTENT":
				case "BLOCKLIST":
				case "SPII":
				case "RECITATION":
					result.blocked = true;
					break;
				default:
					break;
			}
		}

		JsonObject content = candidate.has("content") && candidate.get("content").isJsonObject()
			? candidate.getAsJsonObject("content") : null;
		if (content == null || !content.has("parts") || !content.get("parts").isJsonArray())
		{
			return result;
		}

		for (JsonElement el : content.getAsJsonArray("parts"))
		{
			if (!el.isJsonObject())
			{
				continue;
			}
			JsonObject part = el.getAsJsonObject();
			boolean thought = part.has("thought") && part.get("thought").getAsBoolean();
			if (part.has("functionCall") && part.get("functionCall").isJsonObject())
			{
				result.functionCalls.add(part.getAsJsonObject("functionCall"));
				result.parts.add(part);
			}
			else if (part.has("text") && !thought)
			{
				result.texts.add(part.get("text").getAsString());
				result.parts.add(part);
			}
			else if (part.has("thoughtSignature"))
			{
				result.parts.add(part);
			}
		}
		return result;
	}

	private GeminiException parseError(int code, String body)
	{
		JsonObject json = null;
		try
		{
			JsonElement el = gson.fromJson(body, JsonElement.class);
			// Alguns erros vêm dentro de um array: [{"error": {...}}]
			if (el != null && el.isJsonArray() && el.getAsJsonArray().size() > 0)
			{
				el = el.getAsJsonArray().get(0);
			}
			if (el != null && el.isJsonObject())
			{
				json = el.getAsJsonObject();
			}
		}
		catch (JsonParseException e)
		{
			log.debug("Gemini error body is not JSON");
		}
		return json != null ? errorFromJson(code, json) : GeminiException.fromHttp(code, null, null);
	}

	static GeminiException errorFromJson(int fallbackCode, JsonObject json)
	{
		JsonObject error = json.has("error") && json.get("error").isJsonObject() ? json.getAsJsonObject("error") : null;
		if (error == null)
		{
			return GeminiException.fromHttp(fallbackCode, null, null);
		}
		int code = error.has("code") ? error.get("code").getAsInt() : fallbackCode;
		String status = error.has("status") ? error.get("status").getAsString() : null;
		String message = error.has("message") ? error.get("message").getAsString() : null;

		// Detalhes padrão do Google (google.rpc): QuotaFailure diz qual cota acabou; RetryInfo, quando volta
		String quotaId = null;
		long retryAfter = -1;
		JsonArray details = error.has("details") && error.get("details").isJsonArray() ? error.getAsJsonArray("details") : null;
		if (details != null)
		{
			for (JsonElement el : details)
			{
				if (!el.isJsonObject())
				{
					continue;
				}
				JsonObject d = el.getAsJsonObject();
				String type = d.has("@type") ? d.get("@type").getAsString() : "";
				if (type.endsWith("google.rpc.QuotaFailure") && d.has("violations") && d.get("violations").isJsonArray())
				{
					for (JsonElement v : d.getAsJsonArray("violations"))
					{
						if (v.isJsonObject() && v.getAsJsonObject().has("quotaId"))
						{
							String id = v.getAsJsonObject().get("quotaId").getAsString();
							// Prefere a cota diária, que é a que importa para o usuário
							if (quotaId == null || id.contains("PerDay"))
							{
								quotaId = id;
							}
						}
					}
				}
				else if (type.endsWith("google.rpc.RetryInfo") && d.has("retryDelay"))
				{
					retryAfter = parseDelaySeconds(d.get("retryDelay").getAsString());
				}
			}
		}
		return GeminiException.fromHttp(code, status, message, quotaId, retryAfter);
	}

	/** Converte durações do Google como "36254s" ou "1.5s" em segundos (arredonda para cima). */
	static long parseDelaySeconds(String delay)
	{
		if (delay == null || !delay.endsWith("s"))
		{
			return -1;
		}
		try
		{
			return (long) Math.ceil(Double.parseDouble(delay.substring(0, delay.length() - 1)));
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	private static void checkCancelled(Generation generation) throws GeminiException
	{
		if (generation.isCancelled() || Thread.currentThread().isInterrupted())
		{
			throw new GeminiException(GeminiException.Kind.CANCELLED, "Cancelled");
		}
	}

	/** Estado de uma chamada de streaming. */
	private static final class Turn
	{
		final List<JsonObject> parts = new ArrayList<>();
		final List<JsonObject> functionCalls = new ArrayList<>();
		final boolean continuesText;
		boolean emittedText;
		boolean blocked;
		String finishReason;

		Turn(boolean continuesText)
		{
			this.continuesText = continuesText;
		}

		/** Resposta do modelo como recebida (inclui thoughtSignature, exigida pelo Gemini 3). */
		JsonObject modelContent()
		{
			JsonArray array = new JsonArray();
			for (JsonObject part : parts)
			{
				boolean emptyText = part.has("text") && part.get("text").getAsString().isEmpty()
					&& !part.has("thoughtSignature");
				if (!emptyText)
				{
					array.add(part);
				}
			}
			JsonObject content = new JsonObject();
			content.addProperty("role", "model");
			content.add("parts", array);
			return content;
		}
	}

	/** Transporta um erro de dentro do callback do parser SSE. */
	private static final class EventException extends RuntimeException
	{
		final GeminiException error;

		EventException(GeminiException error)
		{
			super(error.getMessage(), null, false, false);
			this.error = error;
		}
	}

	/** Ignora callbacks depois do cancelamento, para não atualizar uma resposta já finalizada. */
	private static final class GuardedListener implements StreamListener
	{
		private final Generation generation;
		private final StreamListener delegate;

		GuardedListener(Generation generation, StreamListener delegate)
		{
			this.generation = generation;
			this.delegate = delegate;
		}

		@Override
		public void onStatus(String status)
		{
			if (delegate != null && !generation.isCancelled())
			{
				delegate.onStatus(status);
			}
		}

		@Override
		public void onText(String delta)
		{
			if (delegate != null && !generation.isCancelled())
			{
				delegate.onText(delta);
			}
		}

		@Override
		public void onComplete(String fullText, String model)
		{
			if (delegate != null && !generation.isCancelled())
			{
				delegate.onComplete(fullText, model);
			}
		}

		@Override
		public void onError(GeminiException error)
		{
			if (delegate != null && !generation.isCancelled())
			{
				delegate.onError(error);
			}
		}
	}
}
