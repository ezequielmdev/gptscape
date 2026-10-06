package com.runebot;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import lombok.extern.slf4j.Slf4j;

/**
 * Markdown leve para Swing: separa blocos de código do texto e aplica estilos num StyledDocument.
 * Não usa HTML, então o conteúdo do modelo nunca é interpretado como marcação.
 */
@Slf4j
final class MarkdownRenderer
{
	/** Ponto de quebra de linha invisível. */
	private static final char ZERO_WIDTH_SPACE = '​';

	/** Atributo de caractere que guarda a URL de um link clicável. */
	static final String LINK_ATTRIBUTE = "gemini-link";

	private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)\\s*([\\w#+.\\-]*)\\s*$");
	private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$");
	private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+•]\\s+(.*)$");
	private static final Pattern ORDERED = Pattern.compile("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$");
	private static final Pattern QUOTE = Pattern.compile("^\\s*>\\s?(.*)$");
	private static final Pattern RULE = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");
	private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");
	private static final Pattern INLINE = Pattern.compile(
		"`([^`]+)`"                                                  // 1: `código`
			+ "|\\*\\*(.+?)\\*\\*"                                   // 2: **negrito**
			+ "|(?<!\\w)__(.+?)__(?!\\w)"                            // 3: __negrito__
			+ "|~~(.+?)~~"                                           // 4: ~~riscado~~
			+ "|\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)"            // 5,6: [texto](link)
			+ "|(https?://[^\\s<>()\\[\\]]*[^\\s<>()\\[\\].,;:!?'\"*_])" // 7: URL solta
			+ "|(?<![*\\w])\\*(?![\\s*])(.+?)(?<![\\s*])\\*(?![*\\w])"   // 8: *itálico*
			+ "|(?<![_\\w])_(?![\\s_])(.+?)(?<![\\s_])_(?![_\\w])");    // 9: _itálico_

	private MarkdownRenderer()
	{
	}

	// ------------------------------------------------------------------ blocos

	abstract static class Block
	{
	}

	static final class TextBlock extends Block
	{
		final String markdown;

		TextBlock(String markdown)
		{
			this.markdown = markdown;
		}
	}

	static final class CodeBlock extends Block
	{
		final String language;
		final String code;

		CodeBlock(String language, String code)
		{
			this.language = language;
			this.code = code;
		}
	}

	/** Separa blocos de código cercados (```) do restante. Um bloco ainda aberto (streaming) vai até o fim. */
	static List<Block> parseBlocks(String markdown)
	{
		List<Block> blocks = new ArrayList<>();
		String[] lines = normalize(markdown).split("\n", -1);
		StringBuilder text = new StringBuilder();

		int i = 0;
		while (i < lines.length)
		{
			Matcher fence = FENCE.matcher(lines[i]);
			if (!fence.matches())
			{
				text.append(lines[i]).append('\n');
				i++;
				continue;
			}

			flushText(blocks, text);
			String marker = fence.group(1);
			String language = fence.group(2);
			StringBuilder code = new StringBuilder();
			i++;
			while (i < lines.length && !lines[i].trim().equals(marker))
			{
				code.append(lines[i]).append('\n');
				i++;
			}
			i++; // pula a cerca de fechamento (se existir)
			String codeText = code.toString();
			if (codeText.endsWith("\n"))
			{
				codeText = codeText.substring(0, codeText.length() - 1);
			}
			blocks.add(new CodeBlock(language, codeText));
		}
		flushText(blocks, text);
		return blocks;
	}

	private static void flushText(List<Block> blocks, StringBuilder text)
	{
		String t = text.toString().replaceAll("\\s+$", "");
		if (!t.trim().isEmpty())
		{
			blocks.add(new TextBlock(t.replaceAll("^\\n+", "")));
		}
		text.setLength(0);
	}

	static String normalize(String markdown)
	{
		return markdown == null ? "" : markdown.replace("\r\n", "\n").replace('\r', '\n');
	}

	// ------------------------------------------------------------------ linhas

	enum LineType
	{
		BLANK,
		HEADING,
		BULLET,
		ORDERED,
		QUOTE,
		RULE,
		TABLE_ROW,
		TABLE_SEPARATOR,
		PARAGRAPH
	}

	static final class Line
	{
		final LineType type;
		final int level;
		final String marker;
		final String text;

		Line(LineType type, int level, String marker, String text)
		{
			this.type = type;
			this.level = level;
			this.marker = marker;
			this.text = text;
		}
	}

	static Line classify(String raw)
	{
		if (raw.trim().isEmpty())
		{
			return new Line(LineType.BLANK, 0, "", "");
		}
		Matcher m;
		if ((m = HEADING.matcher(raw)).matches())
		{
			return new Line(LineType.HEADING, m.group(1).length(), "", m.group(2));
		}
		if (RULE.matcher(raw).matches())
		{
			return new Line(LineType.RULE, 0, "", "");
		}
		if ((m = BULLET.matcher(raw)).matches())
		{
			return new Line(LineType.BULLET, indentLevel(m.group(1)), "", m.group(2));
		}
		if ((m = ORDERED.matcher(raw)).matches())
		{
			return new Line(LineType.ORDERED, indentLevel(m.group(1)), m.group(2) + ".", m.group(3));
		}
		if ((m = QUOTE.matcher(raw)).matches())
		{
			return new Line(LineType.QUOTE, 0, "", m.group(1));
		}
		String trimmed = raw.trim();
		if (trimmed.startsWith("|") && trimmed.indexOf('|', 1) > 0)
		{
			return TABLE_SEPARATOR.matcher(raw).matches()
				? new Line(LineType.TABLE_SEPARATOR, 0, "", "")
				: new Line(LineType.TABLE_ROW, 0, "", trimmed);
		}
		return new Line(LineType.PARAGRAPH, 0, "", raw.trim());
	}

	private static int indentLevel(String indent)
	{
		int spaces = 0;
		for (char c : indent.toCharArray())
		{
			spaces += c == '\t' ? 4 : 1;
		}
		return Math.min(spaces / 2, 4);
	}

	static List<String> tableCells(String row)
	{
		String r = row.trim();
		if (r.startsWith("|"))
		{
			r = r.substring(1);
		}
		if (r.endsWith("|"))
		{
			r = r.substring(0, r.length() - 1);
		}
		List<String> cells = new ArrayList<>();
		for (String cell : r.split("\\|", -1))
		{
			cells.add(cell.trim());
		}
		return cells;
	}

	// ------------------------------------------------------------------ inline

	static final class Span
	{
		final String text;
		final boolean bold;
		final boolean italic;
		final boolean code;
		final boolean strike;
		final String link;

		Span(String text, boolean bold, boolean italic, boolean code, boolean strike, String link)
		{
			this.text = text;
			this.bold = bold;
			this.italic = italic;
			this.code = code;
			this.strike = strike;
			this.link = link;
		}
	}

	static List<Span> parseInline(String text)
	{
		List<Span> spans = new ArrayList<>();
		parseInline(text, false, false, false, spans);
		return Collections.unmodifiableList(spans);
	}

	private static void parseInline(String text, boolean bold, boolean italic, boolean strike, List<Span> out)
	{
		Matcher m = INLINE.matcher(text);
		int last = 0;
		while (m.find())
		{
			if (m.start() > last)
			{
				out.add(new Span(text.substring(last, m.start()), bold, italic, false, strike, null));
			}
			if (m.group(1) != null)
			{
				out.add(new Span(m.group(1), bold, italic, true, strike, null));
			}
			else if (m.group(2) != null || m.group(3) != null)
			{
				parseInline(m.group(2) != null ? m.group(2) : m.group(3), true, italic, strike, out);
			}
			else if (m.group(4) != null)
			{
				parseInline(m.group(4), bold, italic, true, out);
			}
			else if (m.group(5) != null)
			{
				out.add(new Span(m.group(5).replace("**", "").replace("`", ""), bold, italic, false, strike, m.group(6)));
			}
			else if (m.group(7) != null)
			{
				out.add(new Span(m.group(7), bold, italic, false, strike, m.group(7)));
			}
			else
			{
				parseInline(m.group(8) != null ? m.group(8) : m.group(9), bold, true, strike, out);
			}
			last = m.end();
		}
		if (last < text.length())
		{
			out.add(new Span(text.substring(last), bold, italic, false, strike, null));
		}
	}

	// ------------------------------------------------------------------ Swing

	/** Substitui o conteúdo do documento pelo Markdown (sem blocos de código) renderizado. */
	static void render(StyledDocument doc, String markdown)
	{
		try
		{
			doc.remove(0, doc.getLength());
			String[] lines = normalize(markdown).split("\n", -1);
			boolean first = true;
			boolean gap = false;

			for (int i = 0; i < lines.length; i++)
			{
				Line line = classify(lines[i]);
				if (line.type == LineType.BLANK)
				{
					gap = !first;
					continue;
				}
				if (line.type == LineType.TABLE_SEPARATOR)
				{
					continue;
				}

				if (!first)
				{
					doc.insertString(doc.getLength(), "\n", base(ChatTheme.TEXT));
				}
				int start = doc.getLength();
				SimpleAttributeSet paragraph = new SimpleAttributeSet();
				StyleConstants.setLeftIndent(paragraph, 0);
				StyleConstants.setFirstLineIndent(paragraph, 0);
				StyleConstants.setSpaceAbove(paragraph, gap ? 7f : (line.type == LineType.HEADING && !first ? 5f : 1f));
				StyleConstants.setSpaceBelow(paragraph, line.type == LineType.HEADING ? 2f : 0f);
				StyleConstants.setLineSpacing(paragraph, 0.12f);

				switch (line.type)
				{
					case HEADING:
						insertSpans(doc, parseInline(line.text), ChatTheme.TEXT, true,
							line.level <= 1 ? 4 : line.level == 2 ? 2 : 1);
						break;
					case BULLET:
					{
						float indent = 4 + 12 * line.level;
						StyleConstants.setLeftIndent(paragraph, indent + 11);
						StyleConstants.setFirstLineIndent(paragraph, -11);
						doc.insertString(doc.getLength(), (line.level % 2 == 0 ? "•" : "◦") + "  ",
							base(ChatTheme.MUTED));
						insertSpans(doc, parseInline(line.text), ChatTheme.TEXT, false, 0);
						break;
					}
					case ORDERED:
					{
						float indent = 4 + 12 * line.level;
						float markerWidth = 8 + 6 * line.marker.length();
						StyleConstants.setLeftIndent(paragraph, indent + markerWidth);
						StyleConstants.setFirstLineIndent(paragraph, -markerWidth);
						doc.insertString(doc.getLength(), line.marker + " ", base(ChatTheme.MUTED));
						insertSpans(doc, parseInline(line.text), ChatTheme.TEXT, false, 0);
						break;
					}
					case QUOTE:
						StyleConstants.setLeftIndent(paragraph, 10);
						doc.insertString(doc.getLength(), "│ ", base(ChatTheme.MUTED));
						insertSpans(doc, parseInline(line.text), ChatTheme.MUTED, false, 0);
						break;
					case RULE:
						doc.insertString(doc.getLength(), "──────────",
							base(ChatTheme.BORDER));
						break;
					case TABLE_ROW:
					{
						boolean header = i + 1 < lines.length && classify(lines[i + 1]).type == LineType.TABLE_SEPARATOR;
						List<String> cells = tableCells(line.text);
						for (int c = 0; c < cells.size(); c++)
						{
							if (c > 0)
							{
								doc.insertString(doc.getLength(), "  │  ", base(ChatTheme.BORDER));
							}
							insertSpans(doc, parseInline(cells.get(c)), ChatTheme.TEXT, header, 0);
						}
						break;
					}
					default:
						insertSpans(doc, parseInline(line.text), ChatTheme.TEXT, false, 0);
						break;
				}

				doc.setParagraphAttributes(start, Math.max(1, doc.getLength() - start), paragraph, false);
				first = false;
				gap = false;
			}
		}
		catch (BadLocationException e)
		{
			log.debug("Markdown render failed", e);
		}
	}

	private static void insertSpans(StyledDocument doc, List<Span> spans, Color color, boolean forceBold, int sizeBoost)
		throws BadLocationException
	{
		for (Span span : spans)
		{
			SimpleAttributeSet attrs = base(color);
			StyleConstants.setBold(attrs, span.bold || forceBold);
			StyleConstants.setItalic(attrs, span.italic);
			StyleConstants.setStrikeThrough(attrs, span.strike);
			if (sizeBoost > 0)
			{
				StyleConstants.setFontSize(attrs, ChatTheme.BODY_FONT.getSize() + sizeBoost);
			}
			if (span.code)
			{
				StyleConstants.setFontFamily(attrs, ChatTheme.CODE_FONT.getFamily());
				StyleConstants.setForeground(attrs, ChatTheme.INLINE_CODE);
				StyleConstants.setBackground(attrs, ChatTheme.CODE_BACKGROUND);
			}
			if (span.link != null)
			{
				StyleConstants.setForeground(attrs, ChatTheme.LINK);
				StyleConstants.setUnderline(attrs, true);
				attrs.addAttribute(LINK_ATTRIBUTE, span.link);
			}
			doc.insertString(doc.getLength(), span.code ? span.text : breakable(span.text), attrs);
		}
	}

	private static final Pattern LONG_TOKEN = Pattern.compile("\\S{26,}");
	private static final Pattern BREAK_AFTER = Pattern.compile("([/\\-_.?&=,])");

	/** Permite quebrar palavras muito longas (ex.: URLs) num sidebar estreito, com quebras invisíveis. */
	static String breakable(String text)
	{
		Matcher m = LONG_TOKEN.matcher(text);
		if (!m.find())
		{
			return text;
		}
		StringBuffer sb = new StringBuffer();
		do
		{
			String token = BREAK_AFTER.matcher(m.group()).replaceAll("$1" + ZERO_WIDTH_SPACE);
			m.appendReplacement(sb, Matcher.quoteReplacement(token));
		}
		while (m.find());
		m.appendTail(sb);
		return sb.toString();
	}

	private static SimpleAttributeSet base(Color color)
	{
		SimpleAttributeSet attrs = new SimpleAttributeSet();
		StyleConstants.setFontFamily(attrs, ChatTheme.BODY_FONT.getFamily());
		StyleConstants.setFontSize(attrs, ChatTheme.BODY_FONT.getSize());
		StyleConstants.setForeground(attrs, color);
		return attrs;
	}
}
