package com.gptscape;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.geom.RoundRectangle2D;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import javax.inject.Inject;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

/**
 * Sidebar do chat: experiência do Gemini com o visual do RuneLite.
 * Toda alteração de componentes acontece na EDT; a rede fica no {@link GeminiClient}.
 */
@Slf4j
public class GptScapePanel extends PluginPanel
{
	enum ChatState
	{
		IDLE,
		GENERATING,
		ERROR
	}

	private static final String API_KEY_URL = "https://aistudio.google.com/apikey";
	private static final String CARD_SETUP = "setup";
	private static final String CARD_CHAT = "chat";
	private static final String CARD_EMPTY = "empty";
	private static final String CARD_MESSAGES = "messages";
	private static final int SETUP_TEXT_WIDTH = 170;
	private static final int STREAM_REFRESH_MS = 50;
	private static final int MAX_INPUT_LINES = 6;
	private static final String[] SUGGESTIONS = {
		"What's new in OSRS?",
		"Best money makers",
		"Price check a Twisted bow",
	};

	private final GptScapeConfig config;
	private final ConfigManager configManager;
	private final GeminiClient client;
	private final ChatHistoryStore historyStore;
	private final GameContextProvider gameContext;

	private final GeminiConversation conversation = new GeminiConversation();
	private final CardLayout rootCards = new CardLayout();
	private final CardLayout contentCards = new CardLayout();
	private final JPanel content = new JPanel(contentCards);
	private final MessageListPanel messageList = new MessageListPanel();
	private final JScrollPane messageScroll;
	private final PlaceholderTextArea input = new PlaceholderTextArea("Ask anything");
	private final JScrollPane inputScroll;
	private final ChatButton sendButton;
	private final JPasswordField keyField = new JPasswordField();
	private final JLabel setupStatus = new JLabel();
	private final JButton backToChat = new JButton("Back to chat");
	private final Timer streamTimer;

	private ChatState state = ChatState.IDLE;
	private ActiveGeneration active;
	private ModelMessageView lastModelView;

	/** Geração em andamento: o texto chega na thread do cliente e é desenhado em lotes na EDT. */
	private static final class ActiveGeneration
	{
		final ModelMessageView view;
		final StringBuilder buffer = new StringBuilder();
		boolean dirty;
		GeminiClient.Generation generation;

		ActiveGeneration(ModelMessageView view)
		{
			this.view = view;
		}

		synchronized void append(String delta)
		{
			buffer.append(delta);
			dirty = true;
		}

		/** Texto novo desde a última leitura, ou null se nada mudou. */
		synchronized String takeIfDirty()
		{
			if (!dirty)
			{
				return null;
			}
			dirty = false;
			return buffer.toString();
		}

		synchronized String text()
		{
			return buffer.toString();
		}
	}

	@Inject
	GptScapePanel(GptScapeConfig config, ConfigManager configManager, GeminiClient client,
		ChatHistoryStore historyStore, RuneLiteGameContextProvider gameContext)
	{
		super(false);
		this.config = config;
		this.configManager = configManager;
		this.client = client;
		this.historyStore = historyStore;
		this.gameContext = gameContext;

		setLayout(rootCards);
		setBorder(BorderFactory.createEmptyBorder());
		setBackground(ChatTheme.BACKGROUND);

		messageScroll = new JScrollPane(messageList, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		messageScroll.setBorder(BorderFactory.createEmptyBorder());
		messageScroll.getViewport().setBackground(ChatTheme.BACKGROUND);
		messageScroll.getVerticalScrollBar().setUnitIncrement(18);
		messageScroll.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));

		inputScroll = new JScrollPane(input, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)
		{
			@Override
			public Dimension getPreferredSize()
			{
				// Cresce com o texto até MAX_INPUT_LINES linhas
				int line = input.getFontMetrics(input.getFont()).getHeight();
				Insets in = input.getInsets();
				int text = input.getPreferredSize().height;
				int min = line + in.top + in.bottom;
				int max = line * MAX_INPUT_LINES + in.top + in.bottom;
				return new Dimension(super.getPreferredSize().width, Math.max(min, Math.min(max, text)));
			}
		};

		sendButton = new ChatButton(ChatIcons.arrowUp(14, ChatTheme.BACKGROUND), ChatIcons.arrowUp(14, ChatTheme.MUTED),
			"Send", ChatButton.Style.FILLED, 28);
		streamTimer = new Timer(STREAM_REFRESH_MS, e -> flushStream());

		add(buildSetupView(), CARD_SETUP);
		add(buildChatView(), CARD_CHAT);
		updateSendButton();
		refreshView();
	}

	// ================================================================== ciclo de vida

	/** Plugin ativado: restaura a conversa salva, se houver. */
	void onPluginStart()
	{
		refreshView();
		if (!config.saveHistory() || !conversation.isEmpty())
		{
			return;
		}
		historyStore.load().thenAccept(saved -> SwingUtilities.invokeLater(() ->
		{
			if (conversation.isEmpty() && state != ChatState.GENERATING && !saved.isEmpty())
			{
				restore(saved);
			}
		}));
	}

	/** Plugin desativado: cancela a geração atual e para timers. */
	void onPluginStop()
	{
		if (active != null)
		{
			stopGeneration();
		}
		streamTimer.stop();
	}

	ChatState getState()
	{
		return state;
	}

	void setInputText(String text)
	{
		input.setText(text);
	}

	void saveHistoryNow()
	{
		if (config.saveHistory())
		{
			historyStore.save(conversation.getMessages());
		}
	}

	/** Mostra a configuração da chave se ela estiver vazia, ou o chat. */
	void refreshView()
	{
		showSetup(config.apiKey().trim().isEmpty());
	}

	// ================================================================== construção da interface

	private JPanel buildChatView()
	{
		JPanel chat = new JPanel(new BorderLayout());
		chat.setBackground(ChatTheme.BACKGROUND);
		chat.add(buildHeader(), BorderLayout.NORTH);

		content.setBackground(ChatTheme.BACKGROUND);
		content.add(buildEmptyState(), CARD_EMPTY);
		content.add(messageScroll, CARD_MESSAGES);
		contentCards.show(content, CARD_EMPTY);
		chat.add(content, BorderLayout.CENTER);

		chat.add(buildComposer(), BorderLayout.SOUTH);
		return chat;
	}

	private JPanel buildHeader()
	{
		JLabel title = new JLabel("GPTScape", ChatIcons.assistant(22), JLabel.LEFT);
		title.setIconTextGap(7);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ChatTheme.TEXT);

		ChatButton newChat = new ChatButton(ChatIcons.newChat(16, ChatTheme.TEXT), null, "New chat",
			ChatButton.Style.GHOST, 28);
		newChat.addActionListener(e -> newChat());

		ChatButton more = new ChatButton(ChatIcons.more(16, ChatTheme.TEXT), null, "More options",
			ChatButton.Style.GHOST, 28);
		more.addActionListener(e -> buildMoreMenu().show(more, 0, more.getHeight()));

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		buttons.setOpaque(false);
		buttons.add(newChat);
		buttons.add(more);

		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ChatTheme.BACKGROUND);
		header.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ChatTheme.SURFACE),
			BorderFactory.createEmptyBorder(6, 10, 6, 4)));
		header.add(title, BorderLayout.WEST);
		header.add(buttons, BorderLayout.EAST);
		return header;
	}

	private JPopupMenu buildMoreMenu()
	{
		JPopupMenu menu = new JPopupMenu();

		JMenuItem newChat = new JMenuItem("New chat");
		newChat.addActionListener(e -> newChat());
		menu.add(newChat);

		JMenuItem clear = new JMenuItem("Clear chat");
		clear.setEnabled(!conversation.isEmpty() || active != null);
		clear.addActionListener(e -> confirmClearChat());
		menu.add(clear);

		menu.addSeparator();

		JMenuItem key = new JMenuItem("Change API key");
		key.addActionListener(e -> showSetup(true));
		menu.add(key);

		JMenuItem website = new JMenuItem("Open website");
		website.setToolTipText(config.websiteUrl());
		website.addActionListener(e -> LinkBrowser.browse(config.websiteUrl()));
		menu.add(website);
		return menu;
	}

	/** Menu do botão "+": ferramentas da conversa. */
	private JPopupMenu buildToolsMenu()
	{
		JPopupMenu menu = new JPopupMenu();

		JCheckBoxMenuItem web = new JCheckBoxMenuItem("Web access", config.internet());
		web.setToolTipText("Let GPTScape search the web, read OSRS news and check live GE prices");
		web.addActionListener(e -> configManager.setConfiguration(GptScapeConfig.GROUP, "internet", web.isSelected()));
		menu.add(web);

		JCheckBoxMenuItem stats = new JCheckBoxMenuItem("Share game stats", config.shareGameStats());
		stats.setToolTipText("Send your combat and skill levels with each message");
		stats.addActionListener(e -> configManager.setConfiguration(GptScapeConfig.GROUP, "shareGameStats", stats.isSelected()));
		menu.add(stats);
		return menu;
	}

	private JPanel buildEmptyState()
	{
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setBackground(ChatTheme.BACKGROUND);
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1;
		c.insets = new Insets(0, 14, 0, 14);

		JLabel icon = new JLabel(ChatIcons.assistant(64));
		icon.setHorizontalAlignment(JLabel.CENTER);
		c.gridy = 0;
		panel.add(icon, c);

		JLabel hello = new JLabel("Hello there");
		hello.setFont(FontManager.getRunescapeBoldFont().deriveFont(20f));
		hello.setForeground(ChatTheme.TEXT);
		hello.setHorizontalAlignment(JLabel.CENTER);
		c.gridy = 1;
		c.insets = new Insets(10, 14, 0, 14);
		panel.add(hello, c);

		JLabel subtitle = new JLabel("How can I help you today?");
		subtitle.setFont(ChatTheme.BODY_FONT);
		subtitle.setForeground(ChatTheme.MUTED);
		subtitle.setHorizontalAlignment(JLabel.CENTER);
		c.gridy = 2;
		c.insets = new Insets(4, 14, 16, 14);
		panel.add(subtitle, c);

		for (int i = 0; i < SUGGESTIONS.length; i++)
		{
			String suggestion = SUGGESTIONS[i];
			SuggestionChip chip = new SuggestionChip(suggestion);
			chip.addActionListener(e ->
			{
				input.setText(suggestion);
				sendMessage();
			});
			c.gridy = 3 + i;
			c.insets = new Insets(0, 12, 6, 12);
			panel.add(chip, c);
		}
		return panel;
	}

	private JPanel buildComposer()
	{
		input.setLineWrap(true);
		input.setWrapStyleWord(true);
		input.setRows(1);
		input.setOpaque(false);
		input.setFont(ChatTheme.BODY_FONT);
		input.setForeground(ChatTheme.TEXT);
		input.setCaretColor(ChatTheme.TEXT);
		input.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
		input.getAccessibleContext().setAccessibleName("Message");

		// Enter envia; Shift+Enter quebra linha
		input.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "gemini-send");
		input.getInputMap().put(KeyStroke.getKeyStroke("shift ENTER"), "insert-break");
		input.getActionMap().put("gemini-send", new AbstractAction()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				sendMessage();
			}
		});
		input.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				onInputChanged();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				onInputChanged();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				onInputChanged();
			}
		});

		inputScroll.setBorder(BorderFactory.createEmptyBorder());
		inputScroll.setOpaque(false);
		inputScroll.getViewport().setOpaque(false);
		inputScroll.getVerticalScrollBar().setPreferredSize(new Dimension(6, 0));

		ChatButton tools = new ChatButton(ChatIcons.plus(14, ChatTheme.TEXT), null, "Tools", ChatButton.Style.GHOST, 28);
		tools.addActionListener(e ->
		{
			JPopupMenu menu = buildToolsMenu();
			menu.show(tools, 0, -menu.getPreferredSize().height);
		});

		sendButton.addActionListener(e ->
		{
			if (state == ChatState.GENERATING)
			{
				stopGeneration();
			}
			else
			{
				sendMessage();
			}
		});

		JPanel buttons = new JPanel(new BorderLayout());
		buttons.setOpaque(false);
		buttons.add(tools, BorderLayout.WEST);
		buttons.add(sendButton, BorderLayout.EAST);

		RoundedPanel box = new RoundedPanel(new BorderLayout(0, 2));
		box.setBorder(BorderFactory.createEmptyBorder(8, 10, 4, 4));
		box.add(inputScroll, BorderLayout.CENTER);
		box.add(buttons, BorderLayout.SOUTH);

		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setBackground(ChatTheme.BACKGROUND);
		wrapper.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));
		wrapper.add(box, BorderLayout.CENTER);
		return wrapper;
	}

	// ================================================================== envio e streaming

	private void onInputChanged()
	{
		updateSendButton();
		inputScroll.revalidate();
	}

	private void updateSendButton()
	{
		if (state == ChatState.GENERATING)
		{
			sendButton.setAppearance(ChatIcons.stop(14, ChatTheme.BACKGROUND), null, "Stop");
			sendButton.setEnabled(true);
		}
		else
		{
			sendButton.setAppearance(ChatIcons.arrowUp(14, ChatTheme.BACKGROUND), ChatIcons.arrowUp(14, ChatTheme.MUTED), "Send");
			sendButton.setEnabled(!input.getText().trim().isEmpty());
		}
	}

	private void setState(ChatState newState)
	{
		state = newState;
		updateSendButton();
	}

	void sendMessage()
	{
		String text = input.getText().trim();
		if (text.isEmpty() || state == ChatState.GENERATING)
		{
			return;
		}
		if (config.apiKey().trim().isEmpty())
		{
			showSetup(true);
			return;
		}

		input.setText("");
		conversation.add(ChatMessage.user(text));
		showMessages();
		messageList.add(new UserMessageView(text));
		saveHistoryNow();
		startGeneration();
	}

	/** Cria a resposta do Gemini ("Thinking...") e inicia o streaming para a última mensagem do usuário. */
	private void startGeneration()
	{
		if (lastModelView != null)
		{
			lastModelView.setRegenerateVisible(false);
		}

		ModelMessageView view = new ModelMessageView(new ModelMessageView.Listener()
		{
			@Override
			public void onRegenerate(ModelMessageView v)
			{
				regenerate(v);
			}

			@Override
			public void onRetry(ModelMessageView v)
			{
				retry(v);
			}
		});
		view.showStatus("Thinking");
		messageList.add(view);
		afterContentChange(true);

		ActiveGeneration generation = new ActiveGeneration(view);
		active = generation;
		setState(ChatState.GENERATING);

		boolean web = config.internet();
		String custom = config.systemPrompt();
		GeminiRequest request = new GeminiRequest(
			config.apiKey().trim(),
			config.model(),
			conversation.contextWindow(config.maxHistory()),
			web,
			() -> SystemPrompt.build(LocalDate.now(), web, custom, gameContext.describe()));

		generation.generation = client.sendStreamingMessage(request, new GeminiClient.StreamListener()
		{
			@Override
			public void onStatus(String status)
			{
				SwingUtilities.invokeLater(() ->
				{
					if (active == generation)
					{
						generation.view.showStatus(status);
						afterContentChange(isNearBottom());
					}
				});
			}

			@Override
			public void onText(String delta)
			{
				generation.append(delta);
			}

			@Override
			public void onComplete(String fullText, String model)
			{
				SwingUtilities.invokeLater(() -> completeGeneration(generation, fullText));
			}

			@Override
			public void onError(GeminiException error)
			{
				SwingUtilities.invokeLater(() -> failGeneration(generation, error));
			}
		});
		streamTimer.start();
	}

	/** Desenha o texto acumulado (no máximo a cada STREAM_REFRESH_MS). */
	private void flushStream()
	{
		ActiveGeneration generation = active;
		if (generation == null)
		{
			streamTimer.stop();
			return;
		}
		String text = generation.takeIfDirty();
		if (text != null)
		{
			boolean stick = isNearBottom();
			generation.view.setMarkdown(text);
			afterContentChange(stick);
		}
	}

	private void completeGeneration(ActiveGeneration generation, String fullText)
	{
		if (active != generation)
		{
			return;
		}
		active = null;
		streamTimer.stop();
		boolean stick = isNearBottom();
		generation.view.setMarkdown(fullText);
		generation.view.finish(true, null);
		lastModelView = generation.view;
		conversation.add(ChatMessage.model(fullText));
		setState(ChatState.IDLE);
		saveHistoryNow();
		afterContentChange(stick);
	}

	private void failGeneration(ActiveGeneration generation, GeminiException error)
	{
		if (active != generation)
		{
			return;
		}
		active = null;
		streamTimer.stop();
		boolean stick = isNearBottom();
		String partial = generation.text();
		if (!partial.isEmpty())
		{
			generation.view.setMarkdown(partial);
		}
		// A resposta com erro não entra no histórico: "Try again" gera de novo a partir da pergunta
		boolean canRetry = error.getKind() != GeminiException.Kind.INVALID_KEY
			&& error.getKind() != GeminiException.Kind.FORBIDDEN;
		if (error.getKind() == GeminiException.Kind.FREE_QUOTA_EXHAUSTED)
		{
			// Padrão é o plano gratuito; quando ele acaba, mostramos quando volta e como liberar uso ilimitado
			String hint = GeminiException.resetDescription(error.getRetryAfterSeconds(), ZonedDateTime.now())
				+ "\n\nWant unlimited messages? Turn on billing for your API key in Google AI Studio "
				+ "(API keys → Set up billing). Your key stays the same, and most messages cost less than a cent.";
			generation.view.showError(error.getUserMessage(), true, hint, "Get unlimited messages",
				() -> LinkBrowser.browse(API_KEY_URL));
		}
		else
		{
			generation.view.showError(error.getUserMessage(), canRetry);
		}
		setState(ChatState.ERROR);
		afterContentChange(stick);
	}

	/** Botão "Stop": interrompe a geração mantendo o texto parcial, se houver. */
	private void stopGeneration()
	{
		ActiveGeneration generation = active;
		if (generation == null)
		{
			return;
		}
		active = null;
		streamTimer.stop();
		if (generation.generation != null)
		{
			generation.generation.cancel();
		}

		String partial = generation.text().trim();
		if (!partial.isEmpty())
		{
			generation.view.setMarkdown(partial);
			generation.view.finish(true, "Stopped");
			conversation.add(ChatMessage.model(partial));
			lastModelView = generation.view;
		}
		else
		{
			generation.view.showError("Response stopped.", true);
		}
		setState(ChatState.IDLE);
		saveHistoryNow();
		afterContentChange(isNearBottom());
	}

	/** "Try again" numa resposta com erro: gera de novo para a mesma pergunta, sem duplicá-la. */
	private void retry(ModelMessageView view)
	{
		if (state == ChatState.GENERATING || conversation.last() == null
			|| conversation.last().getRole() != ChatMessage.Role.USER)
		{
			return;
		}
		removeView(view);
		startGeneration();
	}

	/** "Regenerate": troca somente a última resposta do Gemini. */
	private void regenerate(ModelMessageView view)
	{
		if (state == ChatState.GENERATING || view != lastModelView
			|| !conversation.removeLastIf(ChatMessage.Role.MODEL))
		{
			return;
		}
		removeView(view);
		lastModelView = null;
		saveHistoryNow();
		startGeneration();
	}

	private void removeView(ModelMessageView view)
	{
		view.dispose();
		messageList.remove(view);
		afterContentChange(false);
	}

	// ================================================================== nova conversa / histórico

	private void newChat()
	{
		resetConversation();
		input.requestFocusInWindow();
	}

	private void confirmClearChat()
	{
		int choice = JOptionPane.showConfirmDialog(this,
			"Clear this chat? This can't be undone.", "Clear chat",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		if (choice == JOptionPane.OK_OPTION)
		{
			resetConversation();
		}
	}

	private void resetConversation()
	{
		ActiveGeneration generation = active;
		active = null;
		streamTimer.stop();
		if (generation != null && generation.generation != null)
		{
			generation.generation.cancel();
		}

		for (Component c : messageList.getComponents())
		{
			if (c instanceof ModelMessageView)
			{
				((ModelMessageView) c).dispose();
			}
		}
		messageList.removeAll();
		conversation.clear();
		lastModelView = null;
		historyStore.clear();
		setState(ChatState.IDLE);
		contentCards.show(content, CARD_EMPTY);
		messageList.revalidate();
		messageList.repaint();
	}

	void restore(List<ChatMessage> saved)
	{
		conversation.replaceAll(saved);
		ModelMessageView.Listener listener = new ModelMessageView.Listener()
		{
			@Override
			public void onRegenerate(ModelMessageView v)
			{
				regenerate(v);
			}

			@Override
			public void onRetry(ModelMessageView v)
			{
				retry(v);
			}
		};

		for (int i = 0; i < saved.size(); i++)
		{
			ChatMessage m = saved.get(i);
			if (m.getRole() == ChatMessage.Role.USER)
			{
				messageList.add(new UserMessageView(m.getContent()));
				continue;
			}
			ModelMessageView view = new ModelMessageView(listener);
			view.setMarkdown(m.getContent());
			view.finish(i == saved.size() - 1, null);
			messageList.add(view);
			lastModelView = view;
		}

		// RuneLite fechado no meio de uma resposta: permite gerar de novo
		if (saved.get(saved.size() - 1).getRole() == ChatMessage.Role.USER)
		{
			ModelMessageView interrupted = new ModelMessageView(listener);
			interrupted.showError("This response was interrupted.", true);
			messageList.add(interrupted);
		}
		showMessages();
		afterContentChange(true);
	}

	// ================================================================== scroll

	private void showMessages()
	{
		contentCards.show(content, CARD_MESSAGES);
	}

	/** O usuário está perto do fim da conversa (então o scroll deve acompanhar o texto novo). */
	private boolean isNearBottom()
	{
		JScrollBar bar = messageScroll.getVerticalScrollBar();
		return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 48;
	}

	private void afterContentChange(boolean scrollToBottom)
	{
		messageList.revalidate();
		messageList.repaint();
		if (scrollToBottom)
		{
			SwingUtilities.invokeLater(() ->
			{
				messageScroll.validate();
				JScrollBar bar = messageScroll.getVerticalScrollBar();
				bar.setValue(bar.getMaximum());
			});
		}
	}

	// ================================================================== configuração da chave

	private void showSetup(boolean setup)
	{
		if (setup)
		{
			keyField.setText("");
			setupStatus.setText("");
			backToChat.setVisible(!config.apiKey().trim().isEmpty());
		}
		rootCards.show(this, setup ? CARD_SETUP : CARD_CHAT);
	}

	private JPanel buildSetupView()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ChatTheme.BACKGROUND);
		panel.setBorder(BorderFactory.createEmptyBorder(12, 10, 12, 10));

		JLabel title = new JLabel("Connect GPTScape", ChatIcons.assistant(22), JLabel.LEFT);
		title.setIconTextGap(7);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ChatTheme.TEXT);
		panel.add(leftAligned(title));
		panel.add(Box.createVerticalStrut(8));
		panel.add(leftAligned(setupLabel("GPTScape runs on Google Gemini. Add your Gemini API key in the plugin settings, or paste it below. "
			+ "It's free, with no credit card required.", ChatTheme.MUTED)));
		panel.add(Box.createVerticalStrut(14));

		panel.add(leftAligned(setupLabel("<b>1.</b> Get a free API key from Google AI Studio.", ChatTheme.TEXT)));
		panel.add(Box.createVerticalStrut(5));
		JButton getKey = new JButton("Get a free API key");
		getKey.setToolTipText(API_KEY_URL);
		getKey.addActionListener(e -> LinkBrowser.browse(API_KEY_URL));
		panel.add(fullWidth(getKey));
		panel.add(Box.createVerticalStrut(14));

		panel.add(leftAligned(setupLabel("<b>2.</b> Click <b>Create API key</b> and copy it. "
			+ "It starts with <b>AIza</b>.", ChatTheme.TEXT)));
		panel.add(Box.createVerticalStrut(14));

		panel.add(leftAligned(setupLabel("<b>3.</b> Paste your key here:", ChatTheme.TEXT)));
		panel.add(Box.createVerticalStrut(5));
		keyField.setBackground(ChatTheme.SURFACE);
		keyField.setForeground(ChatTheme.TEXT);
		keyField.setCaretColor(ChatTheme.TEXT);
		keyField.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ChatTheme.BORDER),
			BorderFactory.createEmptyBorder(4, 6, 4, 6)));
		keyField.addActionListener(e -> saveKey());
		panel.add(fullWidth(keyField));
		panel.add(Box.createVerticalStrut(6));
		JButton save = new JButton("Save key");
		save.addActionListener(e -> saveKey());
		panel.add(fullWidth(save));
		panel.add(Box.createVerticalStrut(6));
		panel.add(leftAligned(setupStatus));
		panel.add(Box.createVerticalStrut(14));

		panel.add(leftAligned(setupLabel("Your key is stored only in your RuneLite settings on this computer. "
			+ "You can change it anytime from the chat menu.", ChatTheme.MUTED)));
		panel.add(Box.createVerticalStrut(8));
		panel.add(leftAligned(setupLabel("Free keys have a daily message limit. For unlimited use, you can turn on "
			+ "billing for your key in Google AI Studio at any time.", ChatTheme.MUTED)));
		panel.add(Box.createVerticalStrut(14));

		backToChat.addActionListener(e -> showSetup(false));
		panel.add(fullWidth(backToChat));

		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setBackground(ChatTheme.BACKGROUND);
		wrapper.add(panel, BorderLayout.NORTH);
		return wrapper;
	}

	private void saveKey()
	{
		String key = new String(keyField.getPassword()).trim();
		if (key.isEmpty())
		{
			setSetupStatus("Paste your API key first.", ChatTheme.ERROR);
			return;
		}
		if (key.contains(" ") || key.length() < 20)
		{
			setSetupStatus("That doesn't look like a valid key. Make sure you copied all of it.", ChatTheme.ERROR);
			return;
		}

		configManager.setConfiguration(GptScapeConfig.GROUP, "apiKey", key);
		keyField.setText("");
		showSetup(false);
		input.requestFocusInWindow();
	}

	private void setSetupStatus(String text, Color color)
	{
		setupStatus.setText("<html><body style='width:" + SETUP_TEXT_WIDTH + "px'>" + text + "</body></html>");
		setupStatus.setForeground(color);
	}

	/** Rótulo com quebra de linha para textos fixos da tela de configuração (nunca conteúdo do usuário). */
	private static JLabel setupLabel(String html, Color color)
	{
		JLabel label = new JLabel("<html><body style='width:" + SETUP_TEXT_WIDTH + "px'>" + html + "</body></html>");
		label.setFont(ChatTheme.BODY_FONT);
		label.setForeground(color);
		return label;
	}

	private static JComponent leftAligned(JComponent c)
	{
		c.setAlignmentX(Component.LEFT_ALIGNMENT);
		return c;
	}

	private static JComponent fullWidth(JComponent c)
	{
		c.setAlignmentX(Component.LEFT_ALIGNMENT);
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
		return c;
	}

	// ================================================================== componentes auxiliares

	/** Campo de texto com placeholder que só é desenhado (nunca vira conteúdo enviado). */
	private static final class PlaceholderTextArea extends JTextArea
	{
		private final String placeholder;

		PlaceholderTextArea(String placeholder)
		{
			this.placeholder = placeholder;
			addFocusListener(new FocusAdapter()
			{
				@Override
				public void focusGained(FocusEvent e)
				{
					repaint();
				}

				@Override
				public void focusLost(FocusEvent e)
				{
					repaint();
				}
			});
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			if (getDocument().getLength() == 0 && !isFocusOwner())
			{
				Graphics2D g2 = (Graphics2D) g.create();
				try
				{
					g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
					g2.setColor(ChatTheme.MUTED);
					g2.setFont(getFont());
					FontMetrics fm = g2.getFontMetrics();
					Insets in = getInsets();
					g2.drawString(placeholder, in.left, in.top + fm.getAscent());
				}
				finally
				{
					g2.dispose();
				}
			}
		}
	}

	/** Caixa arredondada da área de digitação. */
	private static final class RoundedPanel extends JPanel
	{
		RoundedPanel(java.awt.LayoutManager layout)
		{
			super(layout);
			setOpaque(false);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				RoundRectangle2D shape = new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, 20, 20);
				g2.setColor(ChatTheme.SURFACE);
				g2.fill(shape);
				g2.setColor(ChatTheme.BORDER);
				g2.draw(shape);
			}
			finally
			{
				g2.dispose();
			}
		}
	}

	/** Sugestão clicável no estado vazio. */
	private static final class SuggestionChip extends JButton
	{
		SuggestionChip(String text)
		{
			super(text);
			setFont(ChatTheme.BODY_FONT);
			setForeground(ChatTheme.TEXT);
			setHorizontalAlignment(LEFT);
			setContentAreaFilled(false);
			setBorderPainted(false);
			setFocusPainted(false);
			setOpaque(false);
			setRolloverEnabled(true);
			setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
			setToolTipText(text);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				RoundRectangle2D shape = new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, 14, 14);
				g2.setColor(getModel().isRollover() ? ChatTheme.USER_BUBBLE : ChatTheme.SURFACE);
				g2.fill(shape);
			}
			finally
			{
				g2.dispose();
			}
			super.paintComponent(g);
		}
	}
}
