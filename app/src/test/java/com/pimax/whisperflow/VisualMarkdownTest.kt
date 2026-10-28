package com.pimax.whisperflow

import com.mohamedrejeb.richeditor.model.RichTextState
import androidx.compose.ui.text.TextRange
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VisualMarkdownTest {
    @Test fun unsupportedStructureIsNeverSilentlySerializedThroughTheVisualEditor() {
        assertTrue(requiresSourceMode("| A | B |\n|---|---|\n| 1 | 2 |"))
        assertTrue(requiresSourceMode("\u0060\u0060\u0060kotlin\nval x = 1\n\u0060\u0060\u0060"))
        assertFalse(requiresSourceMode("# Заголовок\n\n**Жирный**\n\n- [ ] Задача\n\n[[Заметка]]"))
    }
    @Test fun fullDocumentReplacementDoesNotReuseOldParagraphOffsets() {
        val state = RichTextState().setMarkdown("# Заголовок\n\n**Старый текст**")
        replaceVisualContent(state, "Последняя правка Я")
        assertEquals("Последняя правка Я", state.annotatedString.text)
        assertTrue(state.toMarkdown(), state.toMarkdown().contains("Последняя правка Я"))
    }
    @Test fun markdownFormattingRoundTripsThroughTheActualVisualEditor() {
        val input = "# Заголовок\n\n**Жирный** и *курсив*\n\n- Первый\n- Второй\n\n[Ссылка](https://example.test)\n\n\u0060код\u0060"
        val state = RichTextState().setMarkdown(input)
        assertFalse(state.annotatedString.text.contains("**"))
        val saved = portableMarkdown(state.toMarkdown())
        assertTrue(saved, saved.contains("# Заголовок"))
        assertTrue(saved, saved.contains("**Жирный**"))
        assertTrue(saved, saved.contains("https://example.test"))
        assertTrue(saved, saved.contains("Второй"))
        assertTrue(saved, saved.contains("\u0060код\u0060"))
    }
    @Test fun headingButtonFormatsTheWholeCurrentLineAsMarkdown() {
        val state = RichTextState().setMarkdown("Новая мысль")
        state.selection = TextRange(3)
        toggleNoteHeading(state)
        assertTrue(state.toMarkdown(), state.toMarkdown().startsWith("## "))
    }
    @Test fun obsidianLinksAndTaskMarkersRemainPortable() {
        val state = RichTextState().setMarkdown(prepareVisualMarkdown("См. [[Моя заметка]]\n\n- [ ] Сделать\n- [x] Готово"))
        val saved = portableMarkdown(state.toMarkdown())
        assertTrue(saved, saved.contains("[[Моя заметка]]"))
        assertTrue(saved, saved.contains("[ ]"))
        assertTrue(saved, saved.contains("[x]"))
    }
}
