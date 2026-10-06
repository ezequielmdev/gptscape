package com.gptscape;

/**
 * Fornece informações do jogo que o usuário escolheu compartilhar com o Gemini.
 * Implementações devem ser somente leitura e nunca devem interagir com o jogo.
 */
public interface GameContextProvider
{
	/**
	 * Texto curto descrevendo o estado do jogador (ex.: níveis), ou string vazia se não houver nada disponível.
	 * Pode bloquear brevemente; nunca é chamado na Event Dispatch Thread.
	 */
	String describe();
}
