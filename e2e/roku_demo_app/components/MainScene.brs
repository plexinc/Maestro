' Three individually-id'd buttons (not a ButtonGroup) so Maestro flows can
' assert focus per-button via `id:` + `focused:` selectors. Up/Down key events
' bubble from the focused button to the scene, which moves focus manually.
sub init()
    m.buttons = [
        m.top.findNode("button-one"),
        m.top.findNode("button-two"),
        m.top.findNode("button-three")
    ]
    m.status = m.top.findNode("statusLabel")
    m.focusIndex = 0

    for each button in m.buttons
        button.observeField("buttonSelected", "onButtonSelected")
    end for

    m.buttons[0].setFocus(true)
end sub

sub onButtonSelected(event as object)
    node = event.getRoSGNode()
    m.status.text = "Selected " + node.text
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false

    if key = "down" and m.focusIndex < m.buttons.count() - 1 then
        m.focusIndex = m.focusIndex + 1
        m.buttons[m.focusIndex].setFocus(true)
        return true
    else if key = "up" and m.focusIndex > 0 then
        m.focusIndex = m.focusIndex - 1
        m.buttons[m.focusIndex].setFocus(true)
        return true
    end if

    return false
end function
