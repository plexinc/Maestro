package maestro.drivers

import com.google.common.truth.Truth.assertThat
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Test

/**
 * Pins maestro.createXPathFromElement, which withActiveElement uses to find the
 * focused element again before sending it keys. A path that matches more than
 * one element sends the keys to the first match instead, so siblings sharing a
 * class string (common with atomic CSS) must be told apart by position.
 */
class CreateXPathFromElementTest {

    @Test
    fun `siblings sharing a class are told apart by position`() {
        assertThat(xPathOf("keys[1]"))
            .isEqualTo("""id("root")/div[@class="row"][1]/button[@class="key"][2]""")
    }

    @Test
    fun `the first of several same-class siblings is position 1`() {
        assertThat(xPathOf("keys[0]"))
            .isEqualTo("""id("root")/div[@class="row"][1]/button[@class="key"][1]""")
    }

    @Test
    fun `siblings with a different class do not count towards the position`() {
        assertThat(xPathOf("keys[3]"))
            .isEqualTo("""id("root")/div[@class="row"][1]/button[@class="key wide"][1]""")
    }

    // --- harness -------------------------------------------------------------

    private val webScript: String by lazy {
        requireNotNull(javaClass.classLoader.getResource("maestro-web.js")) {
            "maestro-web.js was not found on the test classpath"
        }.readText()
    }

    private fun xPathOf(element: String): String? {
        Context.newBuilder("js").build().use { context ->
            context.eval("js", DOM_SCRIPT)
            context.eval("js", webScript)

            val value = context.eval("js", "maestro.createXPathFromElement($element)")
            return if (value.isNull) null else value.asString()
        }
    }

    private companion object {
        // <body><div id="root"><div class="row"> with three button.key and one button.key.wide
        val DOM_SCRIPT = """
            globalThis.Node = { TEXT_NODE: 3 };
            globalThis.window = globalThis;
            globalThis.innerWidth = 1024;
            globalThis.innerHeight = 768;

            const all = [];
            const element = (localName, attributes, children) => {
              const node = {
                nodeType: 1,
                tagName: localName.toUpperCase(),
                localName,
                id: attributes.id ?? '',
                attributes,
                hasAttribute(name) { return name in attributes; },
                getAttribute(name) { return name in attributes ? attributes[name] : null; },
                children,
                childNodes: children,
                parentNode: null,
                parentElement: null,
                previousSibling: null,
              };
              children.forEach((child, index) => {
                child.parentNode = node;
                child.parentElement = node;
                child.previousSibling = index > 0 ? children[index - 1] : null;
              });
              all.push(node);
              return node;
            };

            globalThis.keys = [
              element('button', { class: 'key' }, []),
              element('button', { class: 'key' }, []),
              element('button', { class: 'key' }, []),
              element('button', { class: 'key wide' }, []),
            ];
            const body = element('body', {}, [
              element('div', { id: 'root' }, [element('div', { class: 'row' }, keys)]),
            ]);

            globalThis.document = {
              nodeType: 9,
              body,
              readyState: 'complete',
              querySelectorAll() { return []; },
              getElementsByTagName() { return all; },
            };
            body.parentNode = document;

            globalThis.maestro = {};
        """.trimIndent()
    }
}
