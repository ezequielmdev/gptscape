package com.gptscape;

import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.JTextPane;
import javax.swing.text.DefaultCaret;
import net.runelite.client.util.LinkBrowser;

/** Trecho de texto Markdown de uma resposta (selecionável, com links clicáveis). */
final class MarkdownTextView extends JTextPane
{
	private String markdown;

	MarkdownTextView()
	{
		setEditable(false);
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder());
		setFont(ChatTheme.BODY_FONT);
		setForeground(ChatTheme.TEXT);
		setCaretColor(ChatTheme.TEXT);
		setSelectionColor(new java.awt.Color(80, 110, 160));
		// Não rolar sozinho ao atualizar o texto; o painel controla o scroll
		((DefaultCaret) getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);

		MouseAdapter links = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				String url = linkAt(e);
				// Só abre depois de um clique do usuário, e apenas http/https
				if (url != null && e.getButton() == MouseEvent.BUTTON1 && getSelectedText() == null)
				{
					LinkBrowser.browse(url);
				}
			}

			@Override
			public void mouseMoved(MouseEvent e)
			{
				setCursor(Cursor.getPredefinedCursor(linkAt(e) != null ? Cursor.HAND_CURSOR : Cursor.TEXT_CURSOR));
			}
		};
		addMouseListener(links);
		addMouseMotionListener(links);
	}

	/** Atualiza o conteúdo; não faz nada se o texto não mudou (comum durante o streaming). */
	void setMarkdown(String text)
	{
		if (text.equals(markdown))
		{
			return;
		}
		markdown = text;
		MarkdownRenderer.render(getStyledDocument(), text);
		revalidate();
		repaint();
	}

	private String linkAt(MouseEvent e)
	{
		int pos = viewToModel2D(e.getPoint());
		if (pos < 0 || pos >= getDocument().getLength())
		{
			return null;
		}
		Object url = getStyledDocument().getCharacterElement(pos).getAttributes()
			.getAttribute(MarkdownRenderer.LINK_ATTRIBUTE);
		if (!(url instanceof String))
		{
			return null;
		}
		String link = (String) url;
		return link.startsWith("https://") || link.startsWith("http://") ? link : null;
	}
}
