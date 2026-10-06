package com.gptscape;

import java.util.List;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.Element;
import javax.swing.text.StyleConstants;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class MarkdownRendererTest
{
	@Test
	public void separaBlocosDeCodigo()
	{
		List<MarkdownRenderer.Block> blocks = MarkdownRenderer.parseBlocks(
			"Intro\n\n```java\npublic void exemplo()\n{\n}\n```\nFim");
		assertEquals(3, blocks.size());
		MarkdownRenderer.CodeBlock code = (MarkdownRenderer.CodeBlock) blocks.get(1);
		assertEquals("java", code.language);
		assertEquals("public void exemplo()\n{\n}", code.code);
		assertEquals("Fim", ((MarkdownRenderer.TextBlock) blocks.get(2)).markdown);
	}

	@Test
	public void blocoAbertoDuranteStreamingViraCodigo()
	{
		List<MarkdownRenderer.Block> blocks = MarkdownRenderer.parseBlocks("Veja:\n```python\nprint('oi')");
		assertEquals(2, blocks.size());
		assertEquals("print('oi')", ((MarkdownRenderer.CodeBlock) blocks.get(1)).code);
	}

	@Test
	public void estilosInline()
	{
		List<MarkdownRenderer.Span> spans = MarkdownRenderer.parseInline(
			"Use **Twisted bow** e *Prayer* com `code` em [Wiki](https://oldschool.runescape.wiki/)");
		assertTrue(spans.stream().anyMatch(s -> s.bold && s.text.equals("Twisted bow")));
		assertTrue(spans.stream().anyMatch(s -> s.italic && s.text.equals("Prayer")));
		assertTrue(spans.stream().anyMatch(s -> s.code && s.text.equals("code")));
		assertTrue(spans.stream().anyMatch(s -> "https://oldschool.runescape.wiki/".equals(s.link) && s.text.equals("Wiki")));
	}

	@Test
	public void naoQuebraNomesComUnderscoreNemMultiplicacao()
	{
		List<MarkdownRenderer.Span> spans = MarkdownRenderer.parseInline("snake_case_name e 2 * 3 * 4");
		assertEquals(1, spans.size());
		assertFalse(spans.get(0).italic);
	}

	@Test
	public void urlSoltaSemPontuacaoFinal()
	{
		List<MarkdownRenderer.Span> spans = MarkdownRenderer.parseInline("Veja https://example.com/a.");
		assertTrue(spans.stream().anyMatch(s -> "https://example.com/a".equals(s.link)));
	}

	@Test
	public void classificaLinhas()
	{
		assertEquals(MarkdownRenderer.LineType.HEADING, MarkdownRenderer.classify("## Gear").type);
		assertEquals(MarkdownRenderer.LineType.BULLET, MarkdownRenderer.classify("- item").type);
		assertEquals(1, MarkdownRenderer.classify("  - sub").level);
		assertEquals("3.", MarkdownRenderer.classify("3. passo").marker);
		assertEquals(MarkdownRenderer.LineType.TABLE_SEPARATOR, MarkdownRenderer.classify("|---|:--:|").type);
		assertEquals(MarkdownRenderer.LineType.RULE, MarkdownRenderer.classify("---").type);
	}

	@Test
	public void renderizaSemSimbolosCrusEComLinks() throws Exception
	{
		DefaultStyledDocument doc = new DefaultStyledDocument();
		MarkdownRenderer.render(doc, "# Título\n\n**negrito** e [link](https://example.com)\n- item");
		String text = doc.getText(0, doc.getLength());
		assertFalse(text.contains("**"));
		assertFalse(text.contains("#"));
		assertTrue(text.contains("Título"));
		assertTrue(text.contains("•  item"));

		int pos = text.indexOf("link");
		Element el = doc.getCharacterElement(pos);
		assertEquals("https://example.com", el.getAttributes().getAttribute(MarkdownRenderer.LINK_ATTRIBUTE));
		assertTrue(StyleConstants.isBold(doc.getCharacterElement(text.indexOf("negrito")).getAttributes()));
		assertNull(doc.getCharacterElement(text.indexOf("item")).getAttributes().getAttribute(MarkdownRenderer.LINK_ATTRIBUTE));
	}

	@Test
	public void htmlNaoEhInterpretado() throws Exception
	{
		DefaultStyledDocument doc = new DefaultStyledDocument();
		MarkdownRenderer.render(doc, "<b>oi</b> <script>alert(1)</script>");
		assertTrue(doc.getText(0, doc.getLength()).contains("<script>"));
	}
}
