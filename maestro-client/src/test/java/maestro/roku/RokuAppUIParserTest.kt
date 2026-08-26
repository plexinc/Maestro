package maestro.roku

import com.google.common.truth.Truth.assertThat
import maestro.TreeNode
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

internal class RokuAppUIParserTest {

    // Mirrors the ECP /query/app-ui shape: topscreen > screen > RenderableNode tree,
    // curly-brace bounds/translation arrays, `name` node ids, RowListItem's trailing
    // duplicate Group (the RTA pattern the parser must drop).
    private val appUi = """
        <?xml version="1.0" encoding="UTF-8" ?>
        <app-ui>
          <status>OK</status>
          <topscreen>
            <plugin id="dev" name="Maestro Roku Demo"/>
            <screen focused="true" type="screen">
              <RenderableNode name="rootGroup" subtype="Group" bounds="{0, 0, 1920, 1080}">
                <Label name="titleLabel" text="Roku Demo" bounds="{100, 50, 400, 60}" color="#FFFFFFFF"/>
                <RenderableNode name="menu" subtype="Group" translation="{100, 200}" bounds="{100, 200, 400, 400}">
                  <Button name="button-one" text="Button One" focusable="true" focused="true" bounds="{0, 0, 400, 100}"/>
                  <Button name="button-two" text="Button Two" focusable="true" focused="false" bounds="{0, 120, 400, 100}"/>
                  <Label name="hiddenLabel" text="Hidden" visible="false" bounds="{0, 240, 400, 100}"/>
                  <Label name="fadedLabel" text="Faded" opacity="0" bounds="{0, 360, 400, 100}"/>
                </RenderableNode>
                <RenderableNode name="hiddenKeyboard" subtype="Group" visible="false" bounds="{0, 900, 1920, 180}">
                  <Button name="keyboard-key-a" text="A" focusable="true" bounds="{0, 0, 100, 100}"/>
                </RenderableNode>
                <RowListItem name="row0" bounds="{0, 600, 1920, 300}">
                  <MarkupGrid name="grid0" bounds="{60, 0, 1800, 300}">
                    <Poster name="poster0" uri="pkg:/images/poster.png" bounds="{0, 0, 300, 300}"/>
                  </MarkupGrid>
                  <RenderableNode name="rtaDuplicate" subtype="Group" bounds="{0, 0, 1920, 300}"/>
                </RowListItem>
              </RenderableNode>
            </screen>
          </topscreen>
        </app-ui>
    """.trimIndent()

    private fun parse(xml: String = appUi): TreeNode {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray()))
        return RokuAppUIParser.parse(document)
    }

    private fun TreeNode.byId(id: String): TreeNode? =
        aggregate().find { it.attributes["resource-id"] == id }

    @Test
    fun `root is a full-screen Roku Screen node`() {
        val root = parse()
        assertThat(root.attributes["text"]).isEqualTo("Roku Screen")
        assertThat(root.attributes["bounds"]).isEqualTo("[0,0][1920,1080]")
        assertThat(root.focused).isTrue()
    }

    @Test
    fun `honors an explicit design resolution`() {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(appUi.toByteArray()))
        val root = RokuAppUIParser.parse(document, screenWidth = 1280, screenHeight = 720)
        assertThat(root.attributes["bounds"]).isEqualTo("[0,0][1280,720]")
    }

    @Test
    fun `surfaces node ids, text and scene-absolute bounds`() {
        val root = parse()

        val title = root.byId("titleLabel")
        assertThat(title).isNotNull()
        assertThat(title!!.attributes["text"]).isEqualTo("Roku Demo")
        assertThat(title.attributes["bounds"]).isEqualTo("[100,50][500,110]")
        assertThat(title.attributes["color"]).isEqualTo("#FFFFFFFF")

        // Children accumulate the parent's translation offset
        val buttonTwo = root.byId("button-two")
        assertThat(buttonTwo).isNotNull()
        assertThat(buttonTwo!!.attributes["bounds"]).isEqualTo("[100,320][500,420]")
    }

    @Test
    fun `maps focusable and focused state`() {
        val root = parse()

        val focused = root.byId("button-one")!!
        assertThat(focused.clickable).isTrue()
        assertThat(focused.focused).isTrue()
        assertThat(focused.selected).isTrue()

        val unfocused = root.byId("button-two")!!
        assertThat(unfocused.clickable).isTrue()
        assertThat(unfocused.focused).isFalse()
    }

    // ViewHierarchy.isVisible only consults bounds, so an unrendered node left in the
    // tree would satisfy assertVisible and break assertNotVisible.
    @Test
    fun `nodes the device is not rendering are dropped`() {
        val root = parse()
        assertThat(root.byId("hiddenLabel")).isNull()
        assertThat(root.byId("fadedLabel")).isNull()
    }

    @Test
    fun `an invisible node takes its subtree with it`() {
        val root = parse()
        assertThat(root.byId("hiddenKeyboard")).isNull()
        assertThat(root.byId("keyboard-key-a")).isNull()
    }

    @Test
    fun `drops the trailing duplicate group under RowListItem`() {
        val root = parse()
        assertThat(root.byId("rtaDuplicate")).isNull()
        assertThat(root.byId("poster0")).isNotNull()
    }

    @Test
    fun `missing screen yields an empty placeholder tree`() {
        val root = parse("<app-ui><status>OK</status></app-ui>")
        assertThat(root.attributes["text"]).isEqualTo("Roku App UI")
        assertThat(root.children).isEmpty()
    }
}
