' Focus — verifies programmatic focus: Button 2 (not the first button) receives
' focus when the screen opens, the Roku equivalent of the tvOS/Vega screen's
' requestTVFocus + hasTVPreferredFocus. Same labels and testIDs as those apps.
sub init()
    m.buttons = [
        m.top.findNode("focus-button-1"),
        m.top.findNode("focus-button-2"),
        m.top.findNode("back-button")
    ]
    m.focusIndex = 1

    m.top.findNode("back-button").observeField("buttonSelected", "onBackSelected")
    m.top.observeField("visible", "onVisibleChanged")
end sub

sub onVisibleChanged()
    if m.top.visible then
        ' Programmatic focus lands on Button 2, not the first button.
        m.focusIndex = 1
        m.buttons[m.focusIndex].setFocus(true)
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
    else if key = "down" and m.focusIndex < m.buttons.count() - 1 then
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
