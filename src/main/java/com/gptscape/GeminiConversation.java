package com.gptscape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Histórico da conversa atual. Usado somente na EDT; o cliente recebe cópias imutáveis.
 * O histórico visual nunca é cortado; só a janela de contexto enviada à API é limitada.
 */
public class GeminiConversation
{
	/** Limite aproximado de caracteres enviados como contexto (bem abaixo do limite do modelo). */
	static final int MAX_CONTEXT_CHARS = 60_000;

	private final List<ChatMessage> messages = new ArrayList<>();

	public void add(ChatMessage message)
	{
		messages.add(message);
	}

	public List<ChatMessage> getMessages()
	{
		return Collections.unmodifiableList(new ArrayList<>(messages));
	}

	public void replaceAll(List<ChatMessage> loaded)
	{
		messages.clear();
		messages.addAll(loaded);
	}

	public boolean isEmpty()
	{
		return messages.isEmpty();
	}

	public void clear()
	{
		messages.clear();
	}

	public ChatMessage last()
	{
		return messages.isEmpty() ? null : messages.get(messages.size() - 1);
	}

	/** Remove a última mensagem se ela for do papel indicado. */
	public boolean removeLastIf(ChatMessage.Role role)
	{
		ChatMessage last = last();
		if (last != null && last.getRole() == role)
		{
			messages.remove(messages.size() - 1);
			return true;
		}
		return false;
	}

	/** Janela de contexto para a próxima requisição. */
	public List<ChatMessage> contextWindow(int maxMessages)
	{
		return contextWindow(messages, maxMessages, MAX_CONTEXT_CHARS);
	}

	/**
	 * Mantém as mensagens mais recentes que cabem nos limites (sempre inclui a última)
	 * e garante que a janela comece com uma mensagem do usuário, como a API espera.
	 */
	static List<ChatMessage> contextWindow(List<ChatMessage> all, int maxMessages, int maxChars)
	{
		List<ChatMessage> window = new ArrayList<>();
		int chars = 0;
		for (int i = all.size() - 1; i >= 0; i--)
		{
			ChatMessage m = all.get(i);
			int size = m.getContent().length();
			if (!window.isEmpty() && (window.size() >= maxMessages || chars + size > maxChars))
			{
				break;
			}
			window.add(0, m);
			chars += size;
		}
		while (!window.isEmpty() && window.get(0).getRole() != ChatMessage.Role.USER)
		{
			window.remove(0);
		}
		return Collections.unmodifiableList(window);
	}
}
