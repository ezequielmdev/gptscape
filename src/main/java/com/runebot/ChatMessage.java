package com.runebot;

import lombok.Value;

/** Uma mensagem da conversa, no formato de papéis usado pela API do Gemini (user/model). */
@Value
public class ChatMessage
{
	public enum Role
	{
		USER,
		MODEL
	}

	Role role;
	String content;
	long timestamp;

	public static ChatMessage user(String content)
	{
		return new ChatMessage(Role.USER, content, System.currentTimeMillis());
	}

	public static ChatMessage model(String content)
	{
		return new ChatMessage(Role.MODEL, content, System.currentTimeMillis());
	}
}
