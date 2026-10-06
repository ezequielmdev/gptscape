package com.gptscape;

import com.google.gson.Gson;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ChatHistoryStoreTest
{
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void salvaECarregaComAcentosEEmoji() throws Exception
	{
		File file = new File(folder.getRoot(), "chat/conversation.json");
		ChatHistoryStore store = new ChatHistoryStore(new Gson(), file);
		store.write(Arrays.asList(ChatMessage.user("Como faço o Theatre of Blood? ção 🙂"), ChatMessage.model("**Sim**")));

		List<ChatMessage> loaded = store.read();
		assertEquals(2, loaded.size());
		assertEquals("Como faço o Theatre of Blood? ção 🙂", loaded.get(0).getContent());
		assertEquals(ChatMessage.Role.MODEL, loaded.get(1).getRole());

		String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
		assertFalse(json.toLowerCase().contains("apikey"));
	}

	@Test
	public void arquivoCorrompidoNaoQuebra() throws Exception
	{
		File file = folder.newFile("conversation.json");
		Files.write(file.toPath(), "{not json".getBytes(StandardCharsets.UTF_8));
		assertTrue(new ChatHistoryStore(new Gson(), file).read().isEmpty());
	}

	@Test
	public void listaVaziaApagaArquivo() throws Exception
	{
		File file = folder.newFile("conversation.json");
		new ChatHistoryStore(new Gson(), file).write(java.util.Collections.emptyList());
		assertFalse(file.exists());
	}
}
