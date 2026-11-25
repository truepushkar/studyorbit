---
name: StudyOrbit
description: An illustrated space atlas for planning and timing study sessions.
colors:
  paper: "#F8F6F2"
  ink: "#211E38"
  muted: "#605F72"
  accent: "#4541A0"
  on-accent: "#FFFFFF"
  accent-surface: "#E9E5FC"
  chart-lavender: "#8984CD"
  line: "#DEDBE3"
  forest: "#166558"
  surface: "#FFFFFF"
  night: "#EDEAF3"
  track: "#D7D2E7"
  coral: "#FFBCAC"
  overlay: "#171632"
  overlay-text: "#FFF9F6"
  overlay-muted: "#C6C5DD"
  overlay-secondary: "#363355"
  widget: "#222044"
typography:
  heading:
    fontFamily: Inter
    fontSize: 36sp
    fontWeight: 700
    lineHeight: 42sp
    letterSpacing: -0.5sp
  field:
    fontFamily: Inter
    fontSize: 16sp
    fontWeight: 400
  button:
    fontFamily: Inter
    fontSize: 14sp
    fontWeight: 600
rounded:
  field: 10dp
  button: 14dp
  panel: 16dp
spacing:
  compact: 8dp
  control-gap: 12dp
  inset: 16dp
  page-gutter: 24dp
components:
  button-primary:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.on-accent}"
    typography: "{typography.button}"
    rounded: "{rounded.button}"
  field:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.field}"
  panel:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.panel}"
---

# StudyOrbit

## Overview

Each destination has an illustrated space-travel poster. Focus places the running time and start button on its artwork. Block uses its poster for protection status, Planner for the selected week, and Progress for the daily goal. The surrounding reading and editing surfaces use warm paper.

## Colors

Use deep violet ink for primary text and muted violet for units and supporting copy. Indigo marks selected dates, links and primary actions on light surfaces. On the Focus poster, the timer is white and the start button is coral with dark text. The seven-day study chart uses an indigo line and a lavender selection halo. Selecting a day shows its recorded minutes. The device-time bar uses proportional segments with matching category labels.

The floating timer and blocking overlay use a midnight background with coral accents. Blocking explanations use pale lavender. Home-screen widgets use a slightly lighter midnight surface with warm white text.

## Typography

Bundled Inter carries headings, controls and body copy. Headings are bold and closely spaced. Use tabular numerals for changing time. The poster shows five-character timer values at 60sp and longer values at 44sp to accommodate hours.

## Layout

Place the four destinations in an inset, deep-violet bottom bar with 29dp corners and a lavender selected tab. Center the content column with a maximum width of 580dp and respect the safe drawing area. The illustrated panels fill the available content width and crop the image to their bounds. Keep labels, status and controls as native elements over the artwork.

## Elevation & Depth

White cards sit on the warm paper surface. Fine pale outlines distinguish fields and panels, and an indigo outline identifies the focused field. Lavender fills group related controls.

## Shapes

Use rounded panels for illustrations and content groups. Fields use tighter corners. The poster’s start control and focus/break selector use pill shapes.

## Components

Start a session directly from its poster. Below it, a session-options row opens the task and strict-mode controls. Keep the numeric timer legible as its text changes length. Pair each tab icon with a visible label and communicate state through native selection semantics.
