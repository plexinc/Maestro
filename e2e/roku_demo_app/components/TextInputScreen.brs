' Text Input — a native Keyboard node plus a label echoing the typed text.
' Mirrors the tvOS/Vega TextInputScreen (same labels and testIDs). The Keyboard
' receives focus on entry so Maestro's inputText (ECP LIT_ keypresses) and
' eraseText (Backspace) land in it directly, no keyboard-open step needed.
sub init()
    m.keyboard = m.top.findNode("text-field")
    m.typedLabel = m.top.findNode("typed-label")
    m.backButton = m.top.findNode("back-button")

    m.keyboard.observeField("text", "onTextChanged")
    m.backButton.observeField("buttonSelected", "onBackSelected")
    m.top.observeField("visible", "onVisibleChanged")
end sub

sub onVisibleChanged()
    if m.top.visible then
        m.keyboard.text = ""
        m.keyboard.setFocus(true)
    end if
end sub

sub onTextChanged()
    m.typedLabel.text = "Typed: " + m.keyboard.text
end sub

sub onBackSelected()
    m.top.done = true
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false

    if key = "back" then
        m.top.done = true
        return true
    else if key = "down" and m.keyboard.hasFocus() then
        m.backButton.setFocus(true)
        return true
    else if key = "up" and m.backButton.hasFocus() then
        m.keyboard.setFocus(true)
        return true
    end if

    return false
end function
