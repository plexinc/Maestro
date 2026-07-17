package maestro.vega

import com.google.common.truth.Truth.assertThat
import maestro.TreeNode
import org.junit.jupiter.api.Test

internal class VegaPageSourceParserTest {

    // Two apps: the foreground app under test and the persistent Kepler launcher.
    // Mirrors the automation toolkit's `getPageSource` shape (traits dropped, bare
    // <child> wrappers, role/test_id/focusable/selected attributes, <text> labels).
    private val pageSource = """
        <?xml version="1.0"?>
        <root id="1">
          <app id="100" appName="com.giolaq.multitv.vega">
            <traits><publisherRoot applicationName="com.giolaq.multitv.vega"/></traits>
            <window x="0" y="0" width="1920" height="1080" id="200" test_id="1">
              <child x="0" y="0" width="1920" height="1080" id="201" test_id="2">
                <child x="67" y="23" width="177" height="74" id="210" selectable="true" selected="false" focusable="true" focused="true" test_id="14" role="button">
                  <traits><actions/></traits>
                  <child x="102" y="42" width="95" height="36" id="211" role="text" test_id="10"><text>Search</text></child>
                </child>
                <child x="244" y="23" width="165" height="74" id="220" selectable="true" selected="true" focusable="true" focused="false" test_id="22" role="button">
                  <child x="277" y="41" width="89" height="38" id="221" role="text" test_id="18"><text>Home</text></child>
                </child>
              </child>
            </window>
          </app>
          <app id="4294967297" appName="com.amazon.keplerlauncherapp">
            <window x="0" y="0" width="1920" height="1080" id="300" test_id="1">
              <child x="1486" y="60" width="314" height="108" id="301" clickable="true" focusable="true" test_id="8" role="button">
                <child x="1526" y="80" width="245" height="68" id="302" role="text" test_id="6"><text>Register this device</text></child>
              </child>
            </window>
          </app>
        </root>
    """.trimIndent()

    private fun TreeNode.byText(text: String): TreeNode? =
        aggregate().find { it.attributes["text"] == text }

    @Test
    fun `root is a full-screen Screen node`() {
        val root = VegaPageSourceParser.parse(pageSource)
        assertThat(root.attributes["class"]).isEqualTo("Screen")
        assertThat(root.attributes["bounds"]).isEqualTo("[0,0][1920,1080]")
    }

    @Test
    fun `surfaces foreground app buttons with text, bounds and resource-id`() {
        val root = VegaPageSourceParser.parse(pageSource)

        val search = root.byText("Search")
        assertThat(search).isNotNull()
        assertThat(search!!.attributes["resource-id"]).isEqualTo("10")
        assertThat(search.attributes["bounds"]).isEqualTo("[102,42][197,78]")

        val home = root.byText("Home")
        assertThat(home).isNotNull()
    }

    @Test
    fun `maps focused and selected state`() {
        val root = VegaPageSourceParser.parse(pageSource)

        // The focused Search button; role=button carries focusable/selectable => clickable.
        val searchButton = root.aggregate().find { it.attributes["resource-id"] == "14" }
        assertThat(searchButton).isNotNull()
        assertThat(searchButton!!.focused).isTrue()
        assertThat(searchButton.clickable).isTrue()

        val homeButton = root.aggregate().find { it.attributes["resource-id"] == "22" }
        assertThat(homeButton!!.selected).isTrue()
    }

    @Test
    fun `filters out the Kepler launcher app`() {
        val root = VegaPageSourceParser.parse(pageSource)
        assertThat(root.byText("Register this device")).isNull()
    }
}
