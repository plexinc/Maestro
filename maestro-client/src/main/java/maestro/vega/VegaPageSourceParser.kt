package maestro.vega

import maestro.TreeNode
import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses the Vega automation toolkit's `getPageSource` XML into Maestro's [TreeNode].
 *
 * Shape: `<root><app appName><window x/y/width/height><child role test_id focusable
 * selectable clickable focused selected>…<text>label</text></child></window></app></root>`.
 * `<traits>` subtrees are metadata (dropped); bare structural `<child>` wrappers (no role,
 * interactivity, or text) are flattened away; the persistent Kepler launcher app is
 * filtered out so only the foreground app under test is surfaced.
 *
 * Coordinates are absolute device pixels, so `bounds` is emitted as `[x,y][x+w,y+h]`
 * exactly like the Android adapter (no normalization).
 */
object VegaPageSourceParser {

    private const val LAUNCHER_APP = "com.amazon.keplerlauncherapp"
    private val documentBuilderFactory = DocumentBuilderFactory.newInstance()

    fun parse(xml: String): TreeNode {
        val document = documentBuilderFactory
            .newDocumentBuilder()
            .parse(xml.byteInputStream())
        val root = document.documentElement
            ?: return TreeNode(attributes = mutableMapOf("class" to "Screen"))

        val (screenW, screenH) = findScreenSize(root)
        val children = foregroundScopes(root).flatMap { convert(it) }

        return TreeNode(
            attributes = mutableMapOf(
                "class" to "Screen",
                "bounds" to "[0,0][$screenW,$screenH]",
            ),
            children = children,
        )
    }

    /** The `<app>` subtrees to render: foreground apps, excluding the launcher. */
    private fun foregroundScopes(root: Element): List<Element> {
        val apps = childElements(root).filter { it.tagName == "app" }
        if (apps.isEmpty()) return listOf(root)
        val foreground = apps.filterNot { it.getAttribute("appName") == LAUNCHER_APP }
        return foreground.ifEmpty { apps }
    }

    private fun convert(element: Element): List<TreeNode> {
        val childNodes = childElements(element)
            .filter { it.tagName != "traits" && it.tagName != "text" }
            .flatMap { convert(it) }

        if (!isMeaningful(element)) return childNodes

        val attributes = mutableMapOf<String, String>()
        element.getAttribute("role").takeIf { it.isNotEmpty() }?.let { attributes["class"] = it }
        element.getAttribute("test_id").takeIf { it.isNotEmpty() }?.let { attributes["resource-id"] = it }
        labelOf(element).takeIf { it.isNotEmpty() }?.let { attributes["text"] = it }
        attributes["bounds"] = boundsOf(element)

        val interactive = isInteractive(element)
        // Surface the real boolean (not just true) so `focused: false` / `selected: false`
        // selectors can match; null when the element has no such state at all.
        val focused: Boolean? = if (element.hasAttribute("focused")) boolAttr(element, "focused") else null
        val selected: Boolean? = if (element.hasAttribute("selected")) boolAttr(element, "selected") else null
        if (interactive) attributes["clickable"] = "true"
        focused?.let { attributes["focused"] = it.toString() }
        selected?.let { attributes["selected"] = it.toString() }

        return listOf(
            TreeNode(
                attributes = attributes,
                children = childNodes,
                clickable = if (interactive) true else null,
                focused = focused,
                selected = selected,
            )
        )
    }

    // A node earns a line in the tree when it carries semantic meaning: an explicit
    // role, interactivity, or its own text. Bare structural wrappers are flattened.
    private fun isMeaningful(element: Element): Boolean {
        return element.getAttribute("role").isNotEmpty() ||
            isInteractive(element) ||
            labelOf(element).isNotEmpty()
    }

    private fun isInteractive(element: Element): Boolean {
        return boolAttr(element, "focusable") || boolAttr(element, "selectable") || boolAttr(element, "clickable")
    }

    /** Label = this node's direct text plus the text of its direct `<text>` children. */
    private fun labelOf(element: Element): String {
        val parts = mutableListOf<String>()
        val nodes = element.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            when {
                node.nodeType == Node.TEXT_NODE -> node.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
                node is Element && node.tagName == "text" -> node.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
            }
        }
        return parts.joinToString(" ").trim()
    }

    private fun boundsOf(element: Element): String {
        val x = intAttr(element, "x")
        val y = intAttr(element, "y")
        val w = intAttr(element, "width")
        val h = intAttr(element, "height")
        return "[$x,$y][${x + w},${y + h}]"
    }

    /** Screen rect for the root Screen node: the sized `<window>`, else first sized node, else VVD default. */
    private fun findScreenSize(root: Element): Pair<Int, Int> {
        var firstSized: Pair<Int, Int>? = null
        val queue = ArrayDeque<Element>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val w = intAttr(node, "width")
            val h = intAttr(node, "height")
            if (w > 0 && h > 0) {
                if (node.tagName == "window") return w to h
                if (firstSized == null) firstSized = w to h
            }
            queue.addAll(childElements(node))
        }
        return firstSized ?: (1920 to 1080)
    }

    private fun childElements(node: Element): List<Element> {
        val result = mutableListOf<Element>()
        val nodes = node.childNodes
        for (i in 0 until nodes.length) {
            (nodes.item(i) as? Element)?.let { result.add(it) }
        }
        return result
    }

    private fun boolAttr(element: Element, name: String): Boolean {
        val value = element.getAttribute(name).trim().lowercase()
        return value == "true" || value == "1"
    }

    private fun intAttr(element: Element, name: String): Int {
        return element.getAttribute(name).toIntOrNull() ?: 0
    }
}
