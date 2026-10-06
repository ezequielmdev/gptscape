package com.runebot;

import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class GeminiConversationTest
{
	@Test
	public void mantemContextoDaConversa()
	{
		GeminiConversation c = new GeminiConversation();
		c.add(ChatMessage.user("My Slayer level is 85."));
		c.add(ChatMessage.model("Nice!"));
		c.add(ChatMessage.user("Which Slayer monster do you recommend for me?"));

		List<ChatMessage> window = c.contextWindow(30);
		assertEquals(3, window.size());
		assertEquals("My Slayer level is 85.", window.get(0).getContent());
		assertEquals(ChatMessage.Role.USER, window.get(2).getRole());
	}

	@Test
	public void limitaQuantidadeEComecaComUsuario()
	{
		List<ChatMessage> all = Arrays.asList(
			ChatMessage.user("u1"), ChatMessage.model("m1"),
			ChatMessage.user("u2"), ChatMessage.model("m2"),
			ChatMessage.user("u3"));

		List<ChatMessage> window = GeminiConversation.contextWindow(all, 4, 10_000);
		// As 4 mais recentes seriam m1,u2,m2,u3; a janela não pode começar com "model"
		assertEquals(3, window.size());
		assertEquals("u2", window.get(0).getContent());
	}

	@Test
	public void limitaTamanhoMasSempreIncluiAUltima()
	{
		String huge = new String(new char[500]).replace('\0', 'x');
		List<ChatMessage> all = Arrays.asList(ChatMessage.user(huge), ChatMessage.model(huge), ChatMessage.user(huge));
		List<ChatMessage> window = GeminiConversation.contextWindow(all, 30, 100);
		assertEquals(1, window.size());
	}

	@Test
	public void removeUltimaRespostaParaRegenerar()
	{
		GeminiConversation c = new GeminiConversation();
		c.add(ChatMessage.user("q"));
		c.add(ChatMessage.model("a"));
		assertTrue(c.removeLastIf(ChatMessage.Role.MODEL));
		assertFalse(c.removeLastIf(ChatMessage.Role.MODEL));
		assertEquals(ChatMessage.Role.USER, c.last().getRole());
	}
}
