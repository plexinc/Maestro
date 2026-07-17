import React, { MouseEventHandler, useEffect, useState } from "react";
import { DivProps } from "../../helpers/models";
import { AnnotatedScreenshot } from "./AnnotatedScreenshot";
import { isHotkeyPressed } from "react-hotkeys-hook";
import { useDeviceContext } from "../../context/DeviceContext";
import clsx from "clsx";
import { useRepl } from '../../context/ReplContext';

const useMetaKeyDown = () => {
  return isHotkeyPressed("meta");
};

export default function InteractableDevice({
  enableGestureControl = true,
}: {
  enableGestureControl?: boolean;
}) {
  const { deviceScreen, tvMode, inspectedElement } = useDeviceContext();
  const { runCommandYaml } = useRepl();
  const metaKeyDown = useMetaKeyDown();

  /**
   * In TV mode, map physical arrow keys to the D-pad, Enter to Center, and Esc to
   * the back button, so the device can be driven straight from the keyboard.
   * Skipped while typing in a field or while the action modal is open (it owns
   * arrow keys then).
   */
  useEffect(() => {
    if (!tvMode) return;

    const KEY_TO_DPAD: Record<string, string> = {
      ArrowUp: "Remote Dpad Up",
      ArrowDown: "Remote Dpad Down",
      ArrowLeft: "Remote Dpad Left",
      ArrowRight: "Remote Dpad Right",
      Enter: "Remote Dpad Center",
      Escape: "Remote Menu",
    };

    const onKeyDown = (e: KeyboardEvent) => {
      if (inspectedElement) return;
      if (e.metaKey || e.ctrlKey || e.altKey) return;
      const target = e.target as HTMLElement | null;
      const tag = target?.tagName;
      if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || target?.isContentEditable) {
        return;
      }
      const key = KEY_TO_DPAD[e.key];
      if (!key) return;
      e.preventDefault();
      runCommandYaml(`- pressKey: "${key}"`);
    };

    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [tvMode, inspectedElement, runCommandYaml]);

  const onTapGesture = async (x: number, y: number) => {
    if (tvMode) {
      await runCommandYaml('- pressKey: "Remote Dpad Center"');
    } else {
      await runCommandYaml(`- tapOn:
    point: "${Math.round(100 * x)}%,${Math.round(100 * y)}%"`);
    }
  };

  const onSwipeGesture = async (
    startX: number,
    startY: number,
    endX: number,
    endY: number,
    duration: number
  ) => {
    if (tvMode) {
      const deltaX = endX - startX;
      const deltaY = endY - startY;
      const key =
        Math.abs(deltaY) > Math.abs(deltaX)
          ? deltaY < 0
            ? "Remote Dpad Up"
            : "Remote Dpad Down"
          : deltaX > 0
            ? "Remote Dpad Right"
            : "Remote Dpad Left";
      await runCommandYaml(`- pressKey: "${key}"`);
    } else {
      const startXPercent = Math.round(startX * 100);
      const startYPercent = Math.round(startY * 100);
      const endXPercent = Math.round(endX * 100);
      const endYPercent = Math.round(endY * 100);
      await runCommandYaml(`
      swipe:
        start: "${startXPercent}%,${startYPercent}%"
        end: "${endXPercent}%,${endYPercent}%"
        duration: ${Math.round(duration)}
    `);
    }
  };

  return (
    <GestureDiv
      className={clsx(
        "rounded-lg overflow-hidden w-full",
        enableGestureControl ? "border-2 box-content border-pink-500" : ""
      )}
      style={{
        aspectRatio: deviceScreen
          ? deviceScreen.width / deviceScreen.height
          : 1,
      }}
      onTap={onTapGesture}
      onSwipe={onSwipeGesture}
      gesturesEnabled={enableGestureControl ? metaKeyDown : false}
    >
      <AnnotatedScreenshot
        annotationsEnabled={enableGestureControl ? !metaKeyDown : true}
      />
    </GestureDiv>
  );
}

type GestureEvent = {
  x: number;
  y: number;
  timestamp: number;
};

const createGestureEvent = (
  e: React.MouseEvent<HTMLDivElement, MouseEvent>
): GestureEvent => {
  const { top, left } = e.currentTarget.getBoundingClientRect();
  return {
    x: e.pageX - left,
    y: e.pageY - top,
    timestamp: e.timeStamp,
  };
};

const GestureDiv = ({
  onTap,
  onSwipe,
  gesturesEnabled = true,
  ...rest
}: {
  onTap: (x: number, y: number) => void;
  onSwipe: (
    startX: number,
    startY: number,
    endX: number,
    endY: number,
    duration: number
  ) => void;
  gesturesEnabled?: boolean;
} & DivProps) => {
  const [start, setStart] = useState<GestureEvent>();

  const onStart: MouseEventHandler<HTMLDivElement> = (e) => {
    if (!gesturesEnabled) return;
    setStart(createGestureEvent(e));
  };

  const onEnd: MouseEventHandler<HTMLDivElement> = (e) => {
    if (!gesturesEnabled || !start) return;
    const end = createGestureEvent(e);

    const { width: clientWidth, height: clientHeight } =
      e.currentTarget.getBoundingClientRect();

    const duration = end.timestamp - start.timestamp;
    const distance = Math.hypot(end.x - start.x, end.y - start.y);

    if (duration < 100 || distance < 10) {
      onTap(start.x / clientWidth, start.y / clientHeight);
    } else {
      onSwipe(
        start.x / clientWidth,
        start.y / clientHeight,
        end.x / clientWidth,
        end.y / clientHeight,
        duration
      );
    }
  };

  const onCancel = () => {
    setStart(undefined);
  };

  return (
    <div
      {...rest}
      onMouseDown={onStart}
      onMouseUp={onEnd}
      onMouseLeave={onCancel}
    />
  );
};
