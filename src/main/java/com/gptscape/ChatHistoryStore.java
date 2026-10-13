package com.gptscape;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * Salva a conversa atual em .runelite/plugin-data/gptscape/conversation.json (somente papel, texto e
 * horário; nunca a API key). Toda leitura/escrita acontece numa thread de I/O própria.
 */
@Slf4j
@Singleton
public class ChatHistoryStore
{
	private static final int MAX_SAVED_MESSAGES = 200;
	private static final Type LIST_TYPE = new TypeToken<List<ChatMessage>>()
	{
	}.getType();

	private final Gson gson;
	/** Definido em {@link #start(Filepath)}; null enquanto o diretório do plugin não está disponível. */
	private volatile Filepath file;
	private ExecutorService io;

	@Inject
	ChatHistoryStore(Gson gson)
	{
		this.gson = gson;
	}

	ChatHistoryStore(Gson gson, Filepath file)
	{
		this.gson = gson;
		this.file = file;
	}

	/** @param file arquivo da conversa, dentro do diretório do plugin; null desativa o histórico salvo */
	public synchronized void start(Filepath file)
	{
		this.file = file;
		start();
	}

	public synchronized void start()
	{
		if (io == null || io.isShutdown())
		{
			io = Executors.newSingleThreadExecutor(r ->
			{
				Thread t = new Thread(r, "gemini-chat-history");
				t.setDaemon(true);
				return t;
			});
		}
	}

	/** Termina as gravações pendentes e encerra a thread. */
	public synchronized void stop()
	{
		if (io == null)
		{
			return;
		}
		io.shutdown();
		try
		{
			if (!io.awaitTermination(2, TimeUnit.SECONDS))
			{
				log.debug("Gemini chat history writes did not finish in time");
			}
		}
		catch (InterruptedException e)
		{
			log.debug("Interrupted while waiting for Gemini chat history writes", e);
		}
		io = null;
	}

	public synchronized CompletableFuture<List<ChatMessage>> load()
	{
		if (io == null)
		{
			return CompletableFuture.completedFuture(Collections.emptyList());
		}
		return CompletableFuture.supplyAsync(this::read, io);
	}

	public synchronized void save(List<ChatMessage> messages)
	{
		if (io == null)
		{
			return;
		}
		int from = Math.max(0, messages.size() - MAX_SAVED_MESSAGES);
		List<ChatMessage> copy = new ArrayList<>(messages.subList(from, messages.size()));
		io.execute(() -> write(copy));
	}

	public synchronized void clear()
	{
		if (io == null)
		{
			deleteFile();
			return;
		}
		io.execute(this::deleteFile);
	}

	List<ChatMessage> read()
	{
		Filepath file = this.file;
		if (file == null || !file.isFile())
		{
			return Collections.emptyList();
		}
		try (Reader reader = file.openBufferedReader())
		{
			List<ChatMessage> loaded = gson.fromJson(reader, LIST_TYPE);
			if (loaded == null)
			{
				return Collections.emptyList();
			}
			List<ChatMessage> valid = new ArrayList<>();
			for (ChatMessage m : loaded)
			{
				if (m != null && m.getRole() != null && m.getContent() != null && !m.getContent().trim().isEmpty())
				{
					valid.add(m);
				}
			}
			return valid;
		}
		catch (IOException | JsonParseException e)
		{
			log.warn("Could not read saved Gemini chat history", e);
			return Collections.emptyList();
		}
	}

	void write(List<ChatMessage> messages)
	{
		Filepath file = this.file;
		if (file == null)
		{
			return;
		}
		if (messages.isEmpty())
		{
			deleteFile();
			return;
		}
		try
		{
			file.getParent().createDirectories();
			Filepath tmp = file.getParent().joinSegment(file.getFileName() + ".tmp");
			try (Writer writer = tmp.openBufferedWriter())
			{
				gson.toJson(messages, LIST_TYPE, writer);
			}
			try
			{
				tmp.moveTo(file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (AtomicMoveNotSupportedException e)
			{
				tmp.moveTo(file, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (IOException e)
		{
			log.warn("Could not save Gemini chat history", e);
		}
	}

	private void deleteFile()
	{
		Filepath file = this.file;
		if (file == null)
		{
			return;
		}
		try
		{
			file.deleteIfExists();
		}
		catch (IOException e)
		{
			log.warn("Could not delete saved Gemini chat history", e);
		}
	}
}
