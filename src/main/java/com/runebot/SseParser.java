package com.runebot;

import java.util.function.Consumer;

/**
 * Parser de Server-Sent Events (text/event-stream) linha a linha.
 * Recebe linhas já separadas (o leitor cuida de dados divididos entre leituras TCP),
 * junta várias linhas "data:" de um mesmo evento e entrega o evento na linha em branco
 * ou no fim do stream.
 */
final class SseParser
{
	private final Consumer<String> onEvent;
	private final StringBuilder data = new StringBuilder();
	private boolean hasData;

	SseParser(Consumer<String> onEvent)
	{
		this.onEvent = onEvent;
	}

	void feedLine(String rawLine)
	{
		String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
		if (line.isEmpty())
		{
			dispatch();
			return;
		}
		if (line.startsWith(":"))
		{
			return; // comentário / keep-alive
		}

		int colon = line.indexOf(':');
		String field = colon < 0 ? line : line.substring(0, colon);
		String value = colon < 0 ? "" : line.substring(colon + 1);
		if (value.startsWith(" "))
		{
			value = value.substring(1);
		}

		if ("data".equals(field))
		{
			if (hasData)
			{
				data.append('\n');
			}
			data.append(value);
			hasData = true;
		}
		// Campos "event", "id" e "retry" não são usados pela API do Gemini
	}

	/** Fim do stream: entrega um evento final que não terminou com linha em branco. */
	void finish()
	{
		dispatch();
	}

	private void dispatch()
	{
		if (!hasData)
		{
			return;
		}
		String event = data.toString();
		data.setLength(0);
		hasData = false;
		if (!event.isEmpty() && !"[DONE]".equals(event))
		{
			onEvent.accept(event);
		}
	}
}
