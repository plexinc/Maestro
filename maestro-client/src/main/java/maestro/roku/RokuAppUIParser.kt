package maestro.roku

import maestro.TreeNode
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Parses Roku's ECP `/query/app-ui` XML response into Maestro's [TreeNode] structure.
 *
 * The app-ui response has the following structure:
 * ```
 * <app-ui>
 *   <status>OK</status>
 *   <topscreen>
 *     <plugin id="dev" name="MyApp"/>
 *     <screen focused="true" type="screen">
 *       <RenderableNode name="myId" subtype="Group" bounds="{0, 0, 1920, 1080}" ...>
 *         ...children...
 *       </RenderableNode>
 *     </screen>
 *   </topscreen>
 * </app-ui>
 * ```
 *
 * Node `name` attributes (the SceneGraph node ids) become `resource-id`; `bounds` are
 * accumulated with parent `translation` offsets into scene-absolute `[x1,y1][x2,y2]`
 * rects, matching the Android adapter's format. Nodes the device isn't rendering
 * (`visible="false"` or `opacity="0"`) are dropped along with their subtrees.
 *
 * Reference: roku-test-automation ECP.ts.
 */
object RokuAppUIParser {

    fun parse(document: Document, screenWidth: Int = 1920, screenHeight: Int = 1080): TreeNode {
        val root = document.documentElement

        // Navigate to topscreen > screen > children
        val topscreen = findChildElement(root, "topscreen")
        val screen = topscreen?.let { findChildElement(it, "screen") }

        if (screen == null) {
            return TreeNode(
                attributes = mutableMapOf("text" to "Roku App UI"),
                children = emptyList(),
                clickable = false,
                enabled = true,
                focused = false,
                checked = null,
                selected = null,
            )
        }

        val children = parseChildren(screen, offset = Offset(0.0, 0.0))

        return TreeNode(
            attributes = mutableMapOf(
                "text" to "Roku Screen",
                "bounds" to "[0,0][$screenWidth,$screenHeight]",
            ),
            children = children,
            clickable = false,
            enabled = true,
            focused = screen.getAttribute("focused") == "true",
            checked = null,
            selected = null,
        )
    }

    private fun parseChildren(parent: Element, offset: Offset, parentIsRowListItem: Boolean = false): List<TreeNode> {
        val childElements = getChildElements(parent)

        // If parent is RowListItem, remove the trailing duplicate Group (RTA pattern)
        val elements = if (parentIsRowListItem && childElements.size > 1) {
            childElements.dropLast(1)
        } else {
            childElements
        }

        return elements.mapNotNull { parseNode(it, offset, parentIsRowListItem) }
    }

    private fun parseNode(element: Element, parentOffset: Offset, parentIsRowListItem: Boolean = false): TreeNode? {
        val id = element.getAttribute("name").ifEmpty { null }
        val subtype = if (element.nodeName == "RenderableNode") "Group" else element.nodeName
        val focusable = element.getAttribute("focusable") == "true"
        val focused = element.getAttribute("focused") == "true"
        val visible = element.getAttribute("visible") != "false"
        val opacityStr = element.getAttribute("opacity").ifEmpty { "100" }
        val opacity = opacityStr.toDoubleOrNull()?.div(100) ?: 1.0

        // SceneGraph doesn't render an invisible node or anything beneath it, but ECP
        // still reports the subtree with real bounds. Maestro's ViewHierarchy.isVisible
        // only consults bounds, so keeping those nodes would make assertVisible match a
        // hidden element and assertNotVisible fail on one.
        if (!visible || opacity <= 0) return null

        val translation = parseArray(element.getAttribute("translation"))
        val bounds = parseArray(element.getAttribute("bounds"))
        val text = element.getAttribute("text").ifEmpty { null }
        val color = element.getAttribute("color").ifEmpty { null }
        val uri = element.getAttribute("uri").ifEmpty { null }

        // Calculate scene-relative offset that this node's children inherit
        val nodeOffset = if (subtype == "MarkupGrid" && parentIsRowListItem) {
            // MarkupGrid under RowListItem: use bounds offset
            if (bounds != null) {
                Offset(bounds[0] + parentOffset.x, bounds[1] + parentOffset.y)
            } else {
                parentOffset
            }
        } else if (translation != null) {
            Offset(translation[0] + parentOffset.x, translation[1] + parentOffset.y)
        } else {
            parentOffset
        }

        val sceneRect = if (bounds != null && bounds.size >= 4) {
            SceneRect(
                x = bounds[0] + parentOffset.x,
                y = bounds[1] + parentOffset.y,
                width = bounds[2],
                height = bounds[3],
            )
        } else null

        // Build bounds string in Maestro format: [x1,y1][x2,y2]
        val boundsAttr = sceneRect?.let {
            val x1 = it.x.toInt()
            val y1 = it.y.toInt()
            val x2 = (it.x + it.width).toInt()
            val y2 = (it.y + it.height).toInt()
            "[$x1,$y1][$x2,$y2]"
        }

        val attributes = mutableMapOf<String, String>()
        if (id != null) attributes["resource-id"] = id
        if (text != null) attributes["text"] = text
        if (boundsAttr != null) attributes["bounds"] = boundsAttr
        if (color != null) attributes["color"] = color
        if (uri != null) attributes["uri"] = uri
        attributes["subtype"] = subtype

        val isRowListItem = element.nodeName == "RowListItem"
        val childElements = getChildElements(element)
        val children = if (childElements.isNotEmpty()) {
            parseChildren(element, nodeOffset, parentIsRowListItem = isRowListItem)
        } else {
            emptyList()
        }

        return TreeNode(
            attributes = attributes,
            children = children,
            clickable = focusable,
            enabled = true,
            focused = focused,
            checked = null,
            selected = focused,
        )
    }

    private fun parseArray(value: String?): DoubleArray? {
        if (value.isNullOrEmpty()) return null
        // Roku returns arrays with curly braces: {0, 0, 1920, 1080}
        val cleaned = value.replace("{", "").replace("}", "").trim()
        if (cleaned.isEmpty()) return null
        return try {
            cleaned.split(",").map { it.trim().toDouble() }.toDoubleArray()
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun getChildElements(parent: Element): List<Element> {
        val result = mutableListOf<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                result.add(node as Element)
            }
        }
        return result
    }

    private fun findChildElement(parent: Element, name: String): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == name) {
                return node as Element
            }
        }
        return null
    }

    private data class Offset(val x: Double, val y: Double)

    private data class SceneRect(
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
    )
}
