import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

object MarkdownRenderer {
    private val parser: Parser = Parser.builder().build()
    private val htmlRenderer: HtmlRenderer = HtmlRenderer.builder().build()

    // This safelist is the only defense against stored XSS from user
    // markdown. Every tag, attribute, and protocol not listed here is
    // removed by Jsoup.clean, not just escaped. Do not widen this list
    // without checking the risk, since script content or a javascript:
    // link would run in another user's browser.
    private val safelist: Safelist = Safelist.basic()
        .addTags(
            "h1", "h2", "h3", "h4", "h5", "h6",
            "s", "del", "hr",
            "table", "thead", "tbody", "tr", "th", "td",
            "img",
        )
        .addAttributes("img", "src", "alt", "title")
        .addAttributes("a", "href", "title")
        // This allows only http, https, and mailto links.
        // It blocks javascript: and other script-carrying links.
        .addProtocols("a", "href", "http", "https", "mailto")
        .addProtocols("img", "src", "http", "https")


    // This turns raw markdown into sanitized HTML, safe to send to a browser.
    fun render(markdown: String): String {
        val document: Node = parser.parse(markdown)
        val rawHtml = htmlRenderer.render(document)

        return Jsoup.clean(rawHtml, safelist)
    }

    // This removes all HTML and keeps only plain text.
    // Use it for user-supplied fields that must not carry markup, for
    // example a user's bio.
    fun sanitizePlain(text: String): String {
        return Jsoup.clean(text, Safelist.none())
    }

    // This renders markdown to a short plain-text preview.
    // No route calls this function yet. It is not dead by mistake, but it
    // is also not wired up to any endpoint at the moment.
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