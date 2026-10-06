package com.runebot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class SseParserTest
{
	private final List<String> events = new ArrayList<>();
	private final SseParser parser = new SseParser(events::add);

	private void feed(String... lines)
	{
		for (String line : lines)
		{
			parser.feedLine(line);
		}
	}

	@Test
	public void entregaEventoNaLinhaEmBranco()
	{
		feed("data: {\"a\":1}", "", "data: {\"b\":2}", "");
		assertEquals(Arrays.asList("{\"a\":1}", "{\"b\":2}"), events);
	}

	@Test
	public void juntaVariasLinhasDeDados()
	{
		feed("data: {\"a\":", "data: 1}", "");
		assertEquals(Arrays.asList("{\"a\":\n1}"), events);
	}

	@Test
	public void ignoraComentariosCamposDesconhecidosELinhasVaziasExtras()
	{
		feed(": keep-alive", "", "", "event: message", "id: 7", "data:{\"x\":true}", "\r");
		assertEquals(Arrays.asList("{\"x\":true}"), events);
	}

	@Test
	public void entregaEventoFinalSemLinhaEmBrancoNoFimDoStream()
	{
		feed("data: {\"last\":1}");
		assertEquals(0, events.size());
		parser.finish();
		assertEquals(Arrays.asList("{\"last\":1}"), events);
	}

	@Test
	public void ignoraMarcadorDone()
	{
		feed("data: [DONE]", "");
		parser.finish();
		assertEquals(0, events.size());
	}
}
