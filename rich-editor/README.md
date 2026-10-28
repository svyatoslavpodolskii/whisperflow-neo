# Android rich editor

Android-only source from Compose Rich Editor 1.0.0-rc10:
https://github.com/MohamedRejeb/compose-rich-editor/tree/v1.0.0-rc10

Copyright 2024 Mohamed Rejeb. Apache-2.0; see LICENSE.

Neo modifications: Android platform declarations replace expect/actual; Gradle uses
the app's Compose toolchain. RichTextState applies IME text replacements as a
removal followed by an insertion instead of guessing the operation from the net
length. This prevents selected multi-paragraph text and equal-length corrections
from losing characters. These modifications are covered by app editor tests.
