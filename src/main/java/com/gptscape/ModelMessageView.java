package com.gptscape;

import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;
import javax.swing.Box;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.Timer;
import net.runelite.client.ui.FontManager;

/**
 * Resposta do Gemini: ícone + nome, texto sem caixa ao redor, status animado ("Thinking...")
 * e ações discretas (Copy / Regenerate / Try again). Durante o streaming só esta view é atualizada.
 */
final class ModelMessageView extends JPanel
{
	interface Listener
	{
		void onRegenerate(ModelMessageView view);

		void onRetry(ModelMessageView view);
	}

	private final JPanel body = new JPanel(new StackLayout(8));
	private final JLabel status = new JLabel();
	private final JTextArea error = ChatTheme.wrappingText("", ChatTheme.ERROR, ChatTheme.BODY_FONT);
	private final JLabel note = new JLabel();
	private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
	private final JLabel copy;
	private final JLabel regenerate;
	private final JLabel retry;
	private final JLabel help;
	private final JTextArea hint = ChatTheme.wrappingText("", ChatTheme.MUTED, ChatTheme.BODY_FONT);
	private final Timer dots;
	private Runnable helpAction;

	private String markdown = "";
	private String statusText;
	private int dotCount;

	ModelMessageView(Listener listener)
	{
		super(new StackLayout(5));
		setOpaque(false);

		JLabel title = new JLabel("GPTScape", ChatIcons.assistant(18), JLabel.LEFT);
		title.setIconTextGap(6);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ChatTheme.TEXT);
		JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		header.setOpaque(false);
		header.add(title);

		body.setOpaque(false);

		status.setFont(ChatTheme.BODY_FONT.deriveFont(Font.ITALIC));
		status.setForeground(ChatTheme.MUTED);
		status.setVisible(false);
		dots = new Timer(450, e -> animateStatus());

		error.setVisible(false);

		note.setFont(ChatTheme.SMALL_FONT.deriveFont(Font.ITALIC));
		note.setForeground(ChatTheme.MUTED);

		hint.setVisible(false);

		copy = ChatTheme.actionLink("Copy", "Copy response", this::copyResponse);
		regenerate = ChatTheme.actionLink("Regenerate", "Regenerate response", () -> listener.onRegenerate(this));
		retry = ChatTheme.actionLink("Try again", "Send your last message again", () -> listener.onRetry(this));
		help = ChatTheme.actionLink("", null, ChatTheme.LINK, () ->
		{
			if (helpAction != null)
			{
				helpAction.run();
			}
		});
		help.setVisible(false);
		actions.setOpaque(false);
		actions.setVisible(false);

		add(header);
		add(body);
		add(status);
		add(error);
		add(hint);
		add(actions);
	}

	/** Mostra um status animado (null esconde). Ex.: "Thinking", "Searching: zulrah". */
	void showStatus(String text)
	{
		if (text == null)
		{
			dots.stop();
			status.setVisible(false);
			statusText = null;
			return;
		}
		if (!text.equals(statusText))
		{
			statusText = text;
			dotCount = 3;
			status.setText(text + "...");
		}
		status.setVisible(true);
		if (!dots.isRunning())
		{
			dots.start();
		}
	}

	private void animateStatus()
	{
		if (statusText != null)
		{
			dotCount = dotCount % 3 + 1;
			status.setText(statusText + "...".substring(0, dotCount));
		}
	}

	/** Atualiza o conteúdo reaproveitando os blocos já existentes (só o que mudou é redesenhado). */
	void setMarkdown(String text)
	{
		boolean grew = text.length() > markdown.length();
		markdown = text;
		List<MarkdownRenderer.Block> blocks = MarkdownRenderer.parseBlocks(text);

		for (int i = 0; i < blocks.size(); i++)
		{
			MarkdownRenderer.Block block = blocks.get(i);
			Component existing = i < body.getComponentCount() ? body.getComponent(i) : null;
			if (block instanceof MarkdownRenderer.CodeBlock)
			{
				MarkdownRenderer.CodeBlock cb = (MarkdownRenderer.CodeBlock) block;
				CodeBlockView view = existing instanceof CodeBlockView ? (CodeBlockView) existing : new CodeBlockView();
				view.setCode(cb.language, cb.code);
				place(view, existing, i);
			}
			else
			{
				MarkdownTextView view = existing instanceof MarkdownTextView ? (MarkdownTextView) existing : new MarkdownTextView();
				view.setMarkdown(((MarkdownRenderer.TextBlock) block).markdown);
				place(view, existing, i);
			}
		}
		while (body.getComponentCount() > blocks.size())
		{
			body.remove(body.getComponentCount() - 1);
		}
		if (grew)
		{
			showStatus(null);
		}
		body.revalidate();
	}

	private void place(Component view, Component existing, int index)
	{
		if (view != existing)
		{
			if (existing != null)
			{
				body.remove(index);
			}
			body.add(view, index);
		}
	}

	/** Resposta concluída (ou interrompida com texto parcial). */
	void finish(boolean canRegenerate, String noteText)
	{
		showStatus(null);
		error.setVisible(false);
		hint.setVisible(false);
		help.setVisible(false);
		copy.setVisible(!markdown.isEmpty());
		regenerate.setVisible(canRegenerate);
		retry.setVisible(false);
		note.setText(noteText == null ? "" : noteText);
		note.setVisible(noteText != null);
		layoutActions();
		revalidate();
	}

	/** Falha: mostra a mensagem amigável e, se fizer sentido, "Try again". */
	void showError(String message, boolean canRetry)
	{
		showError(message, canRetry, null, null, null);
	}

	/**
	 * Falha com explicação extra e uma ação de ajuda (ex.: cota gratuita esgotada → "Get unlimited messages").
	 */
	void showError(String message, boolean canRetry, String hintText, String helpText, Runnable onHelp)
	{
		showStatus(null);
		error.setText(message);
		error.setVisible(true);
		hint.setText(hintText == null ? "" : hintText);
		hint.setVisible(hintText != null);
		helpAction = onHelp;
		help.setText(helpText == null ? "" : helpText);
		help.setVisible(helpText != null && onHelp != null);
		copy.setVisible(!markdown.isEmpty());
		regenerate.setVisible(false);
		retry.setVisible(canRetry);
		note.setVisible(false);
		layoutActions();
		revalidate();
	}

	void setRegenerateVisible(boolean visible)
	{
		if (regenerate.isVisible() != visible)
		{
			regenerate.setVisible(visible);
			layoutActions();
		}
	}

	/** Monta a linha de ações só com os itens visíveis, sem espaços sobrando. */
	private void layoutActions()
	{
		actions.removeAll();
		for (JLabel item : new JLabel[]{copy, regenerate, retry, help, note})
		{
			if (item.isVisible())
			{
				if (actions.getComponentCount() > 0)
				{
					actions.add(Box.createHorizontalStrut(14));
				}
				actions.add(item);
			}
		}
		actions.setVisible(actions.getComponentCount() > 0);
		actions.revalidate();
		actions.repaint();
	}

	String getMarkdown()
	{
		return markdown;
	}

	/** Para a animação (view removida ou plugin desligado). */
	void dispose()
	{
		dots.stop();
	}

	private void copyResponse()
	{
		if (ChatTheme.copyToClipboard(markdown))
		{
			copy.setText("Copied");
			Timer reset = new Timer(1500, e -> copy.setText("Copy"));
			reset.setRepeats(false);
			reset.start();
		}
	}
}
