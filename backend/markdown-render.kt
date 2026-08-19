import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

object MarkdownRenderer {
    private val parser: Parser = Parser.builder().build()
    private val htmlRenderer: HtmlRenderer = HtmlRenderer.builder().build()
    private val safelist: Safelist = Safelist.basic()
        .addTags(
            "h1", "h2", "h3", "h4", "h5", "h6",
            "s", "del", "hr",
            "table", "thead", "tbody", "tr", "th", "td",
            "img",
        )
        .addAttributes("img", "src", "alt", "title")
        .addAttributes("a", "href", "title")
        // only http/https -> no js/ts
        .addProtocols("a", "href", "http", "https", "mailto")
        .addProtocols("img", "src", "http", "https")


    // raw markdown -> sanitized html
    fun render(markdown: String): String {
        val document: Node = parser.parse(markdown)
        val rawHtml = htmlRenderer.render(document)

        return Jsoup.clean(rawHtml, safelist)
    }

    // plaintext in previews
    fun renderPlainTextPreview(markdown: String, maxLength: Int = 200): String {
        val document: Node = parser.parse(markdown)
        val rawHtml = htmlRenderer.render(document)

        val plainText = Jsoup.clean(rawHtml, Safelist.none())
            .replace(Regex("\\s+"), " ")
            .trim()

        return if (plainText.length <= maxLength) {
            plainText
        } else {
            plainText.take(maxLength).trimEnd() + "..."
        }
    }
}