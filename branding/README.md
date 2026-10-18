# SakuraBox visual assets

`sakurabox-design.json` is the single geometry definition. The five-petal outline
was redrawn as vectors from the user-supplied `8861.jpg` reference. The attachment
itself is not included in the APK or used as a bitmap foreground.

Run with the existing local Python/Pillow environment:

```text
python tools/generate-sakurabox-icons.py
python tools/generate-sakurabox-icons.py --output-root <preview-directory>
```

Generated SVG, Android VectorDrawables and legacy PNGs share one contour. All
adaptive layers use a 108dp canvas; artwork including strokes fits inside the
central 66dp circle. Foreground layers are transparent. The white background and
launcher mask are separate. API 26 adaptive resources and API 33 monochrome
resources are separately qualified; API 21–25 use density-specific PNGs.

System icons use a slightly stronger white stroke on transparency; SystemUI owns
their tint. Notifications have a dedicated resource instead of a launcher bitmap.
The four animated-vector resource IDs remain compatible with ServiceButton;
animations change stroke alpha without changing the VPN state machine.

The legacy `ic_qu_shadowsocks_launcher` resource name is retained for existing
shortcut callers, but now draws the SakuraBox flower. The separate paper-plane
foreground used as the Telegram navigation symbol is preserved.

Product name: SakuraBox. Attribution: **Based on NekoBox for Android**. Original
authors, upstream links, GPL and third-party license files remain unchanged.
