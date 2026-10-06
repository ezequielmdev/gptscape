package com.gptscape;

import java.util.List;
import java.util.function.Supplier;
import lombok.Value;

/** Dados de uma requisição ao Gemini. A chave só é usada no cabeçalho de autenticação. */
@Value
public class GeminiRequest
{
	String apiKey;
	String model;
	/** Janela de contexto já recortada, terminando na mensagem do usuário a ser respondida. */
	List<ChatMessage> history;
	boolean webAccess;
	/** Avaliado fora da EDT (pode ler o estado do jogo). */
	Supplier<String> systemInstruction;
}
