' Home — vertical menu of buttons that navigate to each test screen.
' Mirrors the tvOS/Vega HomeScreen (same labels and testIDs).
sub init()
    m.buttons = [
        m.top.findNode("menu-navigation"),
        m.top.findNode("menu-text-input"),
        m.top.findNode("menu-focus")
    ]
    m.selections = ["navigation", "textinput", "focus"]
    m.focusIndex = 0

    for each button in m.buttons
        button.observeField("buttonSelected", "onButtonSelected")
    end for
    m.top.observeField("visible", "onVisibleChanged")

    m.buttons[0].setFocus(true)
end sub

sub focusDefault()
    m.focusIndex = 0
    m.buttons[0].setFocus(true)
end sub

sub onVisibleChanged()
    if m.top.visible then
        m.focusIndex = 0
        m.buttons[0].setFocus(true)
    end if
end sub

sub onButtonSelected(event as object)
    id = event.getRoSGNode().id
    if id = "menu-navigation" then
        m.top.selection = "navigation"
    else if id = "menu-text-input" then
        m.top.selection = "textinput"
    else if id = "menu-focus" then
        m.top.selection = "focus"
    end if
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
