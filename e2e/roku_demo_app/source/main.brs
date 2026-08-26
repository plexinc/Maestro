' Maestro Roku demo channel entry point. The channel's only purpose is to
' exercise Maestro's Roku driver: D-pad focus movement, Select activation,
' and view-hierarchy assertions against /query/app-ui.
sub main()
    screen = createObject("roSGScreen")
    m.port = createObject("roMessagePort")
    screen.setMessagePort(m.port)
    screen.createScene("MainScene")
    screen.show()

    while true
        msg = wait(0, m.port)
        if type(msg) = "roSGScreenEvent" then
            if msg.isScreenClosed() then return
        end if
    end while
end sub
