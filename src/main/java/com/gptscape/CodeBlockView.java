package com.gptscape;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.event.MouseWheelEvent;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.DefaultCaret;

/** Bloco de código: cabeçalho com a linguagem e "Copy code"; rolagem horizontal quando necessário. */
final class CodeBlockView extends JPanel
{
	private final JLabel language = new JLabel();
	private final JLabel copy;
	private final JTextArea code = new JTextArea();
	private final JScrollPane scroll;
	private final JPanel header = new JPanel(new BorderLayout());

	CodeBlockView()
	{
		super(new BorderLayout());
		setBackground(ChatTheme.CODE_BACKGROUND);
		setBorder(BorderFactory.createLineBorder(ChatTheme.BORDER));

		language.setFont(ChatTheme.SMALL_FONT);
		language.setForeground(ChatTheme.MUTED);
		copy = ChatTheme.actionLink("Copy code", "Copy this code to the clipboard", this::copyCode);
		header.setBackground(ChatTheme.CODE_HEADER);
		header.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
		header.add(language, BorderLayout.WEST);
		header.add(copy, BorderLayout.EAST);

		code.setEditable(false);
		code.setLineWrap(false);
		code.setFont(ChatTheme.CODE_FONT);
		code.setForeground(ChatTheme.TEXT);
		code.setBackground(ChatTheme.CODE_BACKGROUND);
		code.setCaretColor(ChatTheme.TEXT);
		code.setMargin(new Insets(6, 8, 6, 8));
		((DefaultCaret) code.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);

		scroll = new JScrollPane(code, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getViewport().setBackground(ChatTheme.CODE_BACKGROUND);
		// A roda do mouse rola o chat; Shift + roda rola o código na horizontal
		scroll.setWheelScrollingEnabled(false);
		scroll.addMouseWheelListener(this::forwardWheel);

		add(header, BorderLayout.NORTH);
		add(scroll, BorderLayout.CENTER);
	}

	void setCode(String lang, String text)
	{
		String label = lang == null || lang.isEmpty() ? "code" : lang;
		if (!label.equals(language.getText()))
		{
			language.setText(label);
		}
		if (!text.equals(code.getText()))
		{
			code.setText(text);
			revalidate();
		}
	}

	String getCode()
	{
		return code.getText();
	}

	private void copyCode()
	{
		if (ChatTheme.copyToClipboard(code.getText()))
		{
			copy.setText("Copied");
			Timer reset = new Timer(1500, e -> copy.setText("Copy code"));
			reset.setRepeats(false);
			reset.start();
		}
	}

	private void forwardWheel(MouseWheelEvent e)
	{
		if (e.isShiftDown())
		{
			JScrollBar bar = scroll.getHorizontalScrollBar();
			bar.setValue(bar.getValue() + e.getUnitsToScroll() * 12);
			return;
		}
		JScrollPane outer = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, this);
		if (outer != null)
		{
			outer.dispatchEvent(SwingUtilities.convertMouseEvent(scroll, e, outer));
		}
	}

	@Override
	public Dimension getPreferredSize()
	{
		int width = getWidth() > 0 ? getWidth() : 200;
		Insets insets = getInsets();
		Dimension text = code.getPreferredSize();
		int available = width - insets.left - insets.right;
		int scrollbar = text.width > available ? scroll.getHorizontalScrollBar().getPreferredSize().height : 0;
		return new Dimension(width, insets.top + insets.bottom + header.getPreferredSize().height + text.height + scrollbar);
	}
}
