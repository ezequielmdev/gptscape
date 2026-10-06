package com.runebot;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Falha ao falar com o Gemini, com uma mensagem amigável para mostrar no painel. */
public class GeminiException extends Exception
{
	/** A cota gratuita diária renova à meia-noite do horário do Pacífico. */
	private static final ZoneId QUOTA_RESET_ZONE = ZoneId.of("America/Los_Angeles");

	public enum Kind
	{
		INVALID_KEY("Invalid Gemini API key. Check your key in the plugin settings.", false),
		FORBIDDEN("Access to the Gemini API was denied for this key. Make sure the Generative Language API "
			+ "is enabled for your Google project.", false),
		RATE_LIMIT("Too many requests right now. Wait a moment and try again.", true),
		FREE_QUOTA_EXHAUSTED("You've used today's free Gemini messages.", true),
		MODEL_NOT_FOUND("The selected Gemini model isn't available. Check the model name in the plugin settings.", true),
		SERVER("Gemini is temporarily unavailable. Please try again in a moment.", true),
		TIMEOUT("RuneBot took too long to respond. Please try again.", true),
		NETWORK("Unable to reach Gemini. Check your internet connection and try again.", false),
		BAD_RESPONSE("RuneBot got a response it couldn't read. Please try again.", true),
		EMPTY("RuneBot didn't get a response. Try rephrasing your message.", true),
		BLOCKED("RuneBot can't answer this message. Try rephrasing it.", false),
		BAD_REQUEST("RuneBot couldn't process this request. Try starting a new chat.", false),
		CANCELLED("Response stopped.", false);

		private final String userMessage;
		private final boolean tryOtherModel;

		Kind(String userMessage, boolean tryOtherModel)
		{
			this.userMessage = userMessage;
			this.tryOtherModel = tryOtherModel;
		}
	}

	private final Kind kind;
	private final int httpCode;
	/** Segundos até poder tentar de novo, informados pela API; -1 se desconhecido. */
	private final long retryAfterSeconds;

	public GeminiException(Kind kind, String technicalDetail)
	{
		this(kind, 0, technicalDetail, null);
	}

	public GeminiException(Kind kind, int httpCode, String technicalDetail, Throwable cause)
	{
		this(kind, httpCode, technicalDetail, cause, -1);
	}

	GeminiException(Kind kind, int httpCode, String technicalDetail, Throwable cause, long retryAfterSeconds)
	{
		super(technicalDetail, cause);
		this.kind = kind;
		this.httpCode = httpCode;
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public Kind getKind()
	{
		return kind;
	}

	public int getHttpCode()
	{
		return httpCode;
	}

	public long getRetryAfterSeconds()
	{
		return retryAfterSeconds;
	}

	/** Texto mostrado ao usuário (sem detalhes técnicos). */
	public String getUserMessage()
	{
		return kind.userMessage;
	}

	/** Se vale tentar outro modelo gratuito antes de mostrar o erro. */
	boolean shouldTryOtherModel()
	{
		return kind.tryOtherModel;
	}

	/** Converte uma resposta HTTP de erro da API em uma exceção classificada. */
	static GeminiException fromHttp(int code, String apiStatus, String apiMessage)
	{
		return fromHttp(code, apiStatus, apiMessage, null, -1);
	}

	/**
	 * @param quotaId           id da cota violada (QuotaFailure), ex.: "GenerateRequestsPerDayPerProjectPerModel-FreeTier"
	 * @param retryAfterSeconds atraso sugerido pela API (RetryInfo), ou -1
	 */
	static GeminiException fromHttp(int code, String apiStatus, String apiMessage, String quotaId, long retryAfterSeconds)
	{
		String detail = "HTTP " + code + (apiStatus != null ? " " + apiStatus : "")
			+ (quotaId != null ? " [" + quotaId + "]" : "")
			+ (apiMessage != null ? ": " + apiMessage : "");
		String msg = apiMessage == null ? "" : apiMessage.toLowerCase();

		if (msg.contains("api key") || "API_KEY_INVALID".equals(apiStatus) || code == 401)
		{
			return new GeminiException(Kind.INVALID_KEY, code, detail, null);
		}
		if (code == 403 || "PERMISSION_DENIED".equals(apiStatus))
		{
			return new GeminiException(Kind.FORBIDDEN, code, detail, null);
		}
		if (code == 429 || "RESOURCE_EXHAUSTED".equals(apiStatus))
		{
			boolean daily = (quotaId != null && quotaId.contains("PerDay"))
				|| retryAfterSeconds > Duration.ofMinutes(10).getSeconds();
			return new GeminiException(daily ? Kind.FREE_QUOTA_EXHAUSTED : Kind.RATE_LIMIT, code, detail, null,
				retryAfterSeconds);
		}
		if (code == 404 || "NOT_FOUND".equals(apiStatus))
		{
			return new GeminiException(Kind.MODEL_NOT_FOUND, code, detail, null);
		}
		if (code == 408 || code == 504)
		{
			return new GeminiException(Kind.TIMEOUT, code, detail, null);
		}
		if (code >= 500)
		{
			return new GeminiException(Kind.SERVER, code, detail, null);
		}
		return new GeminiException(Kind.BAD_REQUEST, code, detail, null);
	}

	/**
	 * Quando a cota gratuita volta, no fuso do usuário. Ex.: "Free messages reset at 4:00 AM (in 10h 4m)."
	 * Usa o atraso informado pela API; sem ele, a próxima meia-noite do horário do Pacífico.
	 */
	static String resetDescription(long retryAfterSeconds, ZonedDateTime now)
	{
		ZonedDateTime resetAt;
		if (retryAfterSeconds > 0)
		{
			resetAt = now.plusSeconds(retryAfterSeconds);
		}
		else
		{
			LocalDate tomorrowPacific = now.withZoneSameInstant(QUOTA_RESET_ZONE).toLocalDate().plusDays(1);
			resetAt = tomorrowPacific.atStartOfDay(QUOTA_RESET_ZONE).withZoneSameInstant(now.getZone());
		}

		Duration wait = Duration.between(now, resetAt);
		long hours = wait.toHours();
		long minutes = Math.max(1, wait.minusHours(hours).toMinutes());
		String in = hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
		String at = resetAt.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US));
		return "Free messages reset at " + at + " (in " + in + ").";
	}
}
