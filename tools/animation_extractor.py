#!/usr/bin/env python3
"""
animation_extractor.py — Extract animation curves from video into replicable code.

Pipeline:
  1. ffmpeg extracts frames at high FPS
  2. OpenCV tracks elements between frames (color/shape/region tracking)
  3. Scipy fits cubic-bezier curves to the motion paths
  4. Outputs CSS cubic-bezier(), timing, and implementation code

Usage:
  python3 animation_extractor.py <video.mp4> --track-color "#000000" --fps 60
  python3 animation_extractor.py <video.mp4> --track-region 180,20,120,37

Requirements:
  pip3 install opencv-python numpy scipy
"""

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

import numpy as np

try:
    import cv2
    from scipy.optimize import curve_fit
except ImportError:
    print("Install: pip3 install opencv-python numpy scipy")
    sys.exit(1)


def extract_frames(video, out_dir, fps=60):
    os.makedirs(out_dir, exist_ok=True)
    subprocess.run(
        [
            "ffmpeg",
            "-y",
            "-i",
            video,
            "-vf",
            f"fps={fps}",
            "-q:v",
            "2",
            f"{out_dir}/frame_%04d.png",
        ],
        capture_output=True,
    )
    count = len(list(Path(out_dir).glob("frame_*.png")))
    print(f"Extracted {count} frames at {fps}fps")
    return count


def hex_to_hsv(hex_color):
    hex_color = hex_color.lstrip("#")
    r, g, b = (int(hex_color[i : i + 2], 16) for i in (0, 2, 4))
    color = np.uint8([[[b, g, r]]])
    return tuple(int(v) for v in cv2.cvtColor(color, cv2.COLOR_BGR2HSV)[0][0])


def track_by_color(frames_dir, hsv_target, tol=20):
    frames = sorted(Path(frames_dir).glob("frame_*.png"))
    data = []
    lower = np.array(
        [
            max(0, hsv_target[0] - tol),
            max(0, hsv_target[1] - 40),
            max(0, hsv_target[2] - 40),
        ]
    )
    upper = np.array(
        [
            min(179, hsv_target[0] + tol),
            min(255, hsv_target[1] + 40),
            min(255, hsv_target[2] + 40),
        ]
    )

    for i, f in enumerate(frames):
        img = cv2.imread(str(f))
        hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
        mask = cv2.inRange(hsv, lower, upper)
        contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

        if contours:
            c = max(contours, key=cv2.contourArea)
            area = cv2.contourArea(c)
            if area > 50:
                x, y, w, h = cv2.boundingRect(c)
                data.append(
                    {
                        "frame": i,
                        "x": x + w // 2,
                        "y": y + h // 2,
                        "w": w,
                        "h": h,
                        "area": int(area),
                    }
                )
                continue
        data.append({"frame": i, "x": 0, "y": 0, "w": 0, "h": 0, "area": 0})
    return data


def track_by_region(frames_dir, region):
    frames = sorted(Path(frames_dir).glob("frame_*.png"))
    first = cv2.imread(str(frames[0]))
    tracker = cv2.TrackerCSRT_create()
    tracker.init(first, region)
    data = []
    for i, f in enumerate(frames):
        frame = cv2.imread(str(f))
        ok, bbox = tracker.update(frame)
        if ok:
            x, y, w, h = [int(v) for v in bbox]
            data.append(
                {
                    "frame": i,
                    "x": x + w // 2,
                    "y": y + h // 2,
                    "w": w,
                    "h": h,
                    "area": w * h,
                }
            )
        else:
            data.append({"frame": i, "x": 0, "y": 0, "w": 0, "h": 0, "area": 0})
    return data


def fit_bezier(t_vals, y_vals):
    """Fit cubic-bezier to normalized data. Returns (x1, y1, x2, y2)."""
    if len(t_vals) < 4:
        return (0.25, 0.1, 0.25, 1.0)
    t = np.array(t_vals, dtype=float)
    y = np.array(y_vals, dtype=float)

    def bezier(t, x1, y1, x2, y2):
        return 3 * (1 - t) ** 2 * t * y1 + 3 * (1 - t) * t**2 * y2 + t**3

    try:
        popt, _ = curve_fit(
            bezier,
            t,
            y,
            p0=[0.3, 0.0, 0.7, 1.0],
            bounds=([0, -0.5, 0, 0.5], [1, 2.0, 1, 2.0]),
        )
        return tuple(round(v, 3) for v in popt)
    except:
        return (0.25, 0.1, 0.25, 1.0)


def analyze(data, fps):
    visible = [d for d in data if d["area"] > 0]
    if len(visible) < 4:
        print("Not enough visible frames to analyze")
        return

    times = [d["frame"] / fps for d in visible]
    widths = [d["w"] for d in visible]
    heights = [d["h"] for d in visible]
    xs = [d["x"] for d in visible]
    ys = [d["y"] for d in visible]
    duration_ms = int((times[-1] - times[0]) * 1000)

    # Normalize
    t_norm = [
        (t - times[0]) / (times[-1] - times[0]) if times[-1] != times[0] else 0
        for t in times
    ]

    def norm(vals):
        mn, mx = min(vals), max(vals)
        return [(v - mn) / (mx - mn) if mx != mn else 0 for v in vals]

    w_norm = norm(widths)
    h_norm = norm(heights)
    x_norm = norm(xs)
    y_norm = norm(ys)

    # Detect overshoot (spring behavior)
    w_overshoot = max(widths) > widths[-1] * 1.02
    h_overshoot = max(heights) > heights[-1] * 1.02

    # Fit curves
    w_bezier = fit_bezier(t_norm, w_norm)
    h_bezier = fit_bezier(t_norm, h_norm)
    x_bezier = fit_bezier(t_norm, x_norm)
    y_bezier = fit_bezier(t_norm, y_norm)

    # Detect phases (velocity-based)
    speeds = []
    for i in range(1, len(visible)):
        dt = times[i] - times[i - 1]
        if dt == 0:
            continue
        dw = abs(widths[i] - widths[i - 1])
        dh = abs(heights[i] - heights[i - 1])
        dx = abs(xs[i] - xs[i - 1])
        dy = abs(ys[i] - ys[i - 1])
        speeds.append((dw + dh + dx + dy) / dt)

    avg_speed = np.mean(speeds) if speeds else 0
    phases = []
    phase_start = 0
    moving = speeds[0] > avg_speed * 0.3 if speeds else False

    for i, s in enumerate(speeds):
        is_moving = s > avg_speed * 0.3
        if is_moving != moving:
            phases.append(
                {
                    "start_ms": int(times[phase_start] * 1000),
                    "end_ms": int(times[i] * 1000),
                    "type": "active" if moving else "idle",
                }
            )
            phase_start = i
            moving = is_moving

    # Output
    result = {
        "duration_ms": duration_ms,
        "frames_tracked": len(visible),
        "size": {
            "width": {"start": widths[0], "end": widths[-1], "peak": max(widths)},
            "height": {"start": heights[0], "end": heights[-1], "peak": max(heights)},
            "overshoot_w": w_overshoot,
            "overshoot_h": h_overshoot,
        },
        "position": {
            "x": {"start": xs[0], "end": xs[-1]},
            "y": {"start": ys[0], "end": ys[-1]},
        },
        "easing": {
            "width": f"cubic-bezier({w_bezier[0]}, {w_bezier[1]}, {w_bezier[2]}, {w_bezier[3]})",
            "height": f"cubic-bezier({h_bezier[0]}, {h_bezier[1]}, {h_bezier[2]}, {h_bezier[3]})",
            "x": f"cubic-bezier({x_bezier[0]}, {x_bezier[1]}, {x_bezier[2]}, {x_bezier[3]})",
            "y": f"cubic-bezier({y_bezier[0]}, {y_bezier[1]}, {y_bezier[2]}, {y_bezier[3]})",
        },
        "phases": phases,
        "code": {
            "css": f"transition: all {duration_ms}ms {w_bezier[0]} {w_bezier[1]} {w_bezier[2]} {w_bezier[3]};",
            "android": f"PathInterpolator({w_bezier[0]}f, {w_bezier[1]}f, {w_bezier[2]}f, {w_bezier[3]}f)",
            "spring_detected": w_overshoot or h_overshoot,
        },
    }

    print(f"\n{'=' * 60}")
    print(f"ANIMATION ANALYSIS")
    print(f"{'=' * 60}")
    print(f"  Duration: {duration_ms}ms")
    print(
        f"  Size: {widths[0]}x{heights[0]} → {widths[-1]}x{heights[-1]} (peak: {max(widths)}x{max(heights)})"
    )
    print(f"  Position: ({xs[0]},{ys[0]}) → ({xs[-1]},{ys[-1]})")
    print(f"  Spring/overshoot: W={w_overshoot}, H={h_overshoot}")
    print(f"\n  Easing curves:")
    print(f"    Width:  {result['easing']['width']}")
    print(f"    Height: {result['easing']['height']}")
    print(f"    X:      {result['easing']['x']}")
    print(f"    Y:      {result['easing']['y']}")
    print(f"\n  Phases: {len(phases)}")
    for p in phases:
        print(f"    [{p['start_ms']}→{p['end_ms']}ms] {p['type']}")
    print(f"{'=' * 60}")

    return result


def main():
    parser = argparse.ArgumentParser(description="Extract animation curves from video")
    parser.add_argument("video", help="Video file path")
    parser.add_argument("--fps", type=int, default=60)
    parser.add_argument("--track-color", help='Hex color to track (e.g. "#000000")')
    parser.add_argument("--track-region", help="Region to track: x,y,w,h")
    parser.add_argument("--output", default="./anim_out")
    args = parser.parse_args()

    frames_dir = os.path.join(args.output, "frames")
    extract_frames(args.video, frames_dir, args.fps)

    if args.track_color:
        data = track_by_color(frames_dir, hex_to_hsv(args.track_color))
    elif args.track_region:
        region = tuple(int(v) for v in args.track_region.split(","))
        data = track_by_region(frames_dir, region)
    else:
        print("Specify --track-color or --track-region")
        return

    with open(os.path.join(args.output, "tracking.json"), "w") as f:
        json.dump(data, f, indent=2)

    result = analyze(data, args.fps)
    if result:
        with open(os.path.join(args.output, "result.json"), "w") as f:
            json.dump(result, f, indent=2)
        print(f"\nResults saved to {args.output}/result.json")


if __name__ == "__main__":
    main()
