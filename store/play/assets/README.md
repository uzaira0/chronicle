# Google Play visual assets

These files are publication inputs for the `com.bcm.chronicle` Play listing.

| Asset | Source | Required output | Status |
|---|---|---|---|
| App icon | `app-icon-512.svg` using the shipped Chronicle mark | `app-icon-512.png`, 512 × 512 full-square PNG | Ready |
| Feature graphic | `feature-graphic.svg` | `feature-graphic.png`, 1024 × 500 opaque PNG | Ready |
| Phone screenshots | Final minimal signed Play candidate with synthetic test study | Six portrait screenshots in `screenshots/phone` | Blocked until the sealed candidate exists; no stale set is tracked |
| Tablet screenshots | Final minimal signed Play candidate with synthetic test study | Six screenshots in each selected tablet form factor | Blocked until the sealed candidate exists; no stale set is tracked |

The icon and feature graphic use only the shipped Chronicle mark and Android UI color tokens. The
Play icon intentionally has no pre-rounded corners or baked shadow; Google Play applies both. Regenerate
the PNG files after editing their SVG sources:

```bash
rsvg-convert --width 512 --height 512 store/play/assets/app-icon-512.svg | \
  magick png:- -alpha on -strip PNG32:store/play/assets/app-icon-512.png
rsvg-convert --width 1024 --height 500 store/play/assets/feature-graphic.svg | \
  magick png:- -alpha off -strip PNG24:store/play/assets/feature-graphic.png
```

Screenshot rules are in `../assets.md` and `screenshots/README.md`. Never capture real study,
participant, credential, app-usage, unlock-response, notification, or operational endpoint data.
