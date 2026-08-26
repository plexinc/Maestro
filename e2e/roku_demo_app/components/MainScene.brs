' Mirrors the tvOS/Vega demo apps (e2e/tvos_demo_app, e2e/vega_demo_app) — same
' screens, labels and testIDs — re-implemented in BrightScript/SceneGraph. The Home
' menu opens one test screen at a time; each screen reports "done" to return Home.
sub init()
    m.home = m.top.findNode("homeScreen")
    m.screens = {
        navigation: m.top.findNode("navigationScreen"),
        textinput: m.top.findNode("textInputScreen"),
        focus: m.top.findNode("focusScreen")
    }

    m.home.observeField("selection", "onMenuSelection")
    for each name in m.screens
        m.screens[name].observeField("done", "onScreenDone")
    end for

    ' Creating the (hidden) TextInputScreen's Keyboard can grab input focus after
    ' HomeScreen's init already claimed it — re-assert the default focus last.
    m.home.callFunc("focusDefault")
end sub

sub onMenuSelection(event as object)
    target = m.screens[event.getData()]
    if target = invalid then return
    m.home.visible = false
    target.visible = true
end sub

sub onScreenDone()
    for each name in m.screens
        m.screens[name].visible = false
    end for
    m.home.visible = true
end sub
