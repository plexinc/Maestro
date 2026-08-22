' Navigation — 2x2 grid for testing directional D-pad navigation, plus a Back
' button. Mirrors the tvOS/Vega NavigationScreen (same labels and testIDs).
' SceneGraph has no spatial focus engine, so moves are an explicit transition map.
sub init()
    m.transitions = {
        "grid-top-left":     { right: "grid-top-right",   down: "grid-bottom-left" },
        "grid-top-right":    { left: "grid-top-left",     down: "grid-bottom-right" },
        "grid-bottom-left":  { right: "grid-bottom-right", up: "grid-top-left",  down: "back-button" },
        "grid-bottom-right": { left: "grid-bottom-left",   up: "grid-top-right", down: "back-button" },
        "back-button":       { up: "grid-bottom-left" }
    }
    m.currentId = "grid-top-left"

    m.top.findNode("back-button").observeField("buttonSelected", "onBackSelected")
    m.top.observeField("visible", "onVisibleChanged")
end sub

sub onVisibleChanged()
    if m.top.visible then
        m.currentId = "grid-top-left"
        m.top.findNode(m.currentId).setFocus(true)
    end if
end sub

sub onBackSelected()
    m.top.done = true
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false

    if key = "back" then
        m.top.done = true
        return true
    end if

    moves = m.transitions[m.currentId]
    if moves = invalid then return false
    nextId = moves[key]
    if nextId = invalid then return false

    m.currentId = nextId
    m.top.findNode(nextId).setFocus(true)
    return true
end function
