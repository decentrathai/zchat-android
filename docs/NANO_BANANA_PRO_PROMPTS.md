# ZCHAT Asset Generation Prompts for Nano Banana Pro

**Created:** 2026-01-26
**Purpose:** AI image generation prompts for ZCHAT cyberpunk UI redesign
**Generator:** Nano Banana Pro

---

## Prompt Engineering Best Practices Applied

Based on AI image generation research:
1. **Comma-separated keywords** - More effective than long sentences
2. **Specific colors with hex codes** - Ensures color accuracy
3. **Explicit size specifications** - Prevents scaling issues
4. **Style anchors** - "cyberpunk", "neon", "futuristic", "tech"
5. **Negative prompts** - What to avoid
6. **Technical specifications** - Stroke weight, corners, transparency

---

## Master Style Guide

All prompts should include these consistent elements:

```
STYLE BASE (include in all prompts):
- Cyberpunk aesthetic
- Neon glow effect
- Dark background (transparent or #050510)
- 2px stroke weight for outlines
- Circuit board / digital details
- Clean, minimalist but futuristic
- High contrast

COLOR PALETTE:
- Primary: Cyan #00FFFF
- Secondary: Magenta #FF00FF
- Background: Deep purple-black #050510
- Accent: Purple #8B5CF6

OUTPUT FORMAT:
- PNG with transparency
- 512x512px (will be scaled to 24dp/48dp on device)
```

---

## Section 1: P0 Icons (Critical - Blocks Launch)

### 1.1 Lock Shield Icon (`ic_lock_shield`)

**Purpose:** Security/encryption indicator for chat features
**Color:** Cyan (#00FFFF)
**Used in:** Welcome card, feature list

```
PROMPT:
cyberpunk security icon, shield with padlock, circuit board pattern inside shield, neon cyan glow, #00FFFF color, outlined style, 2px stroke weight, digital encrypted aesthetic, futuristic tech design, transparent dark background #050510, clean minimalist icon, subtle outer glow effect, vector style, 512x512 PNG

NEGATIVE PROMPT:
realistic, 3D render, photographic, gradient fill, solid fill, colorful, rainbow, red, green, yellow, orange, busy, cluttered, cartoonish, cute
```

---

### 1.2 No Server Icon (`ic_no_server`)

**Purpose:** Decentralized/no servers needed indicator
**Color:** Magenta (#FF00FF)
**Used in:** Welcome card, feature list

```
PROMPT:
cyberpunk server icon with X slash through it, decentralized concept, server rack crossed out, neon magenta glow, #FF00FF color, outlined style, 2px stroke weight, data streams around server, digital tech aesthetic, no servers needed concept, transparent dark background #050510, clean futuristic icon, 512x512 PNG

NEGATIVE PROMPT:
realistic server, photograph, 3D render, cyan, blue, green, happy, positive, colorful gradient, solid fill, cartoonish
```

---

### 1.3 Anonymous Icon (`ic_anonymous`)

**Purpose:** No phone number/identity needed
**Color:** Cyan (#00FFFF)
**Used in:** Welcome card, feature list

```
PROMPT:
cyberpunk anonymous mask icon, ghost figure with digital glitch effect, scan lines overlay, mysterious privacy concept, neon cyan glow, #00FFFF color, outlined style, 2px stroke weight, digital hacker aesthetic, no identity needed, transparent dark background #050510, minimalist futuristic design, 512x512 PNG

ALTERNATIVE PROMPT:
cyberpunk incognito icon, faceless silhouette with question mark, digital privacy symbol, neon cyan #00FFFF, glitch effect, scan lines, outlined 2px stroke, mysterious anonymous aesthetic, dark transparent background, 512x512 PNG

NEGATIVE PROMPT:
realistic face, photo, happy expression, detailed features, colorful, magenta, red, solid fill, 3D render
```

---

### 1.4 No Tracking Icon (`ic_no_tracking`)

**Purpose:** No surveillance/tracking indicator
**Color:** Magenta (#FF00FF)
**Used in:** Welcome card, feature list

```
PROMPT:
cyberpunk anti-surveillance icon, eye with slash through it, crossed out eye symbol, no tracking concept, neon magenta glow, #FF00FF color, outlined style, 2px stroke weight, digital circuit details, privacy protection aesthetic, transparent dark background #050510, futuristic warning icon, 512x512 PNG

NEGATIVE PROMPT:
realistic eye, photograph, detailed iris, cyan, blue, green, colorful, solid fill, 3D render, cute
```

---

### 1.5 Chat Bubble Icon (`ic_chat_bubble`)

**Purpose:** Messaging/chat indicator
**Color:** Cyan (#00FFFF)
**Used in:** Navigation, chat list, empty states

```
PROMPT:
cyberpunk chat bubble icon, speech bubble with circuit pattern inside, encrypted message aesthetic, digital communication symbol, neon cyan glow, #00FFFF color, outlined style, 2px stroke weight, tech scan lines, futuristic messenger icon, transparent dark background #050510, clean minimalist design, 512x512 PNG

NEGATIVE PROMPT:
realistic, 3D bubble, photograph, gradient fill, magenta, red, green, emoji inside, text inside, solid fill
```

---

### 1.6 Send Arrow Icon (`ic_send`)

**Purpose:** Send message action button
**Color:** Cyan (#00FFFF)
**Used in:** Chat input, message composition

```
PROMPT:
cyberpunk send arrow icon, arrow pointing right with motion trail, speed lines, transmission effect, digital data sending concept, neon cyan glow, #00FFFF color, outlined style, 2px stroke weight, futuristic tech aesthetic, paper airplane alternative, transparent dark background #050510, clean dynamic icon, 512x512 PNG

NEGATIVE PROMPT:
paper airplane realistic, photograph, 3D render, magenta, red, solid fill, slow, static, no motion
```

---

### 1.7 Add/Plus Icon (`ic_add`)

**Purpose:** Create new chat/add contact action
**Color:** Cyan (#00FFFF)
**Used in:** FAB, action buttons

```
PROMPT:
cyberpunk plus icon, add symbol with neon glow, subtle circuit details at corners, futuristic tech aesthetic, neon cyan glow, #00FFFF color, outlined style, 2px stroke weight, clean minimalist design, transparent dark background #050510, glowing edges, digital new action button, 512x512 PNG

NEGATIVE PROMPT:
realistic, photograph, 3D render, magenta, red, green, solid fill, busy, cluttered, ornate
```

---

## Section 2: P1 Icons (High Priority - Before Public Release)

### 2.1 Settings Gear Icon (`ic_settings`)

**Purpose:** Settings/configuration access
**Color:** Cyan (#00FFFF)
**Used in:** Header, navigation

```
PROMPT:
cyberpunk settings gear icon, cogwheel with circuit board details, digital tech aesthetic, neon cyan glow, #00FFFF color, outlined style, 2px stroke weight, futuristic configuration symbol, small data nodes on gear teeth, transparent dark background #050510, clean minimalist design, 512x512 PNG

NEGATIVE PROMPT:
realistic gear, photograph, 3D mechanical, magenta, red, solid fill, industrial
```

---

### 2.2 Contacts Icon (`ic_contacts`)

**Purpose:** Contact list/people
**Color:** Cyan (#00FFFF)
**Used in:** Navigation, contact screens

```
PROMPT:
cyberpunk person icon, human silhouette with tech overlay, circuit pattern on body, digital user profile concept, neon cyan glow, #00FFFF color, outlined style, 2px stroke weight, futuristic contacts aesthetic, scan lines, transparent dark background #050510, minimalist design, 512x512 PNG

NEGATIVE PROMPT:
realistic person, photograph, detailed face, magenta, red, solid fill, 3D render, emoji
```

---

### 2.3 QR Code Icon (`ic_qr_code`)

**Purpose:** QR code scan/display
**Color:** Cyan (#00FFFF)
**Used in:** Share address, receive

```
PROMPT:
cyberpunk QR code icon, stylized QR with neon glowing corners, digital scan aesthetic, futuristic barcode symbol, neon cyan glow, #00FFFF color, outlined style, 2px stroke weight, tech circuit corners, transparent dark background #050510, clean minimalist design, 512x512 PNG

NEGATIVE PROMPT:
realistic QR code, photograph, full QR pattern, magenta, red, solid fill, busy pattern
```

---

### 2.4 Wallet Icon (`ic_wallet`)

**Purpose:** Wallet/balance display
**Color:** Cyan (#00FFFF)
**Used in:** Header, wallet screens

```
PROMPT:
cyberpunk wallet icon, digital wallet with Z symbol inside, Zcash aesthetic, tech circuit details, neon cyan glow, #00FFFF color, outlined style, 2px stroke weight, cryptocurrency wallet concept, futuristic finance icon, transparent dark background #050510, clean design, 512x512 PNG

NEGATIVE PROMPT:
realistic wallet, leather, photograph, magenta, red, solid fill, bitcoin symbol, dollar sign
```

---

### 2.5 Copy Icon (`ic_copy`)

**Purpose:** Copy to clipboard action
**Color:** Cyan (#00FFFF)
**Used in:** Address display, text fields

```
PROMPT:
cyberpunk copy icon, two overlapping rectangles with flash effect, digital duplicate concept, neon cyan glow, #00FFFF color, outlined style, 2px stroke weight, tech clipboard aesthetic, data transfer symbol, transparent dark background #050510, minimalist design, 512x512 PNG

NEGATIVE PROMPT:
realistic papers, photograph, 3D render, magenta, red, solid fill
```

---

### 2.6 Destroy Icon (`ic_destroy`)

**Purpose:** Emergency data wipe action
**Color:** Magenta (#FF00FF)
**Used in:** Emergency wipe screens

```
PROMPT:
cyberpunk destroy icon, explosion shatter effect, digital destruction concept, data deletion symbol, neon magenta glow, #FF00FF color, outlined style, 2px stroke weight, fragmenting particles, tech disintegration aesthetic, warning action button, transparent dark background #050510, 512x512 PNG

ALTERNATIVE PROMPT:
cyberpunk self-destruct icon, broken shield with cracks, shattered data concept, neon magenta #FF00FF, glitch fragments, destruction aesthetic, outlined 2px stroke, dark transparent background, 512x512 PNG

NEGATIVE PROMPT:
realistic explosion, fire, photograph, cyan, blue, green, solid fill, peaceful
```

---

### 2.7 Warning Icon (`ic_warning`)

**Purpose:** Warning/alert indicator
**Color:** Magenta (#FF00FF)
**Used in:** Emergency screens, alerts

```
PROMPT:
cyberpunk warning triangle icon, exclamation mark inside triangle, digital alert concept, pulsing glow effect aesthetic, neon magenta glow, #FF00FF color, outlined style, 2px stroke weight, tech danger symbol, urgent notification, transparent dark background #050510, clean design, 512x512 PNG

NEGATIVE PROMPT:
realistic sign, photograph, yellow, orange, red, solid fill, cute, friendly
```

---

## Section 3: P2 Icons (Medium Priority - Post-Launch Polish)

### 3.1 Attach Icon (`ic_attach`)

```
PROMPT:
cyberpunk paperclip icon, attachment symbol with circuit details, digital file attach concept, neon cyan glow, #00FFFF color, outlined style, 2px stroke weight, tech aesthetic, transparent dark background #050510, minimalist design, 512x512 PNG
```

---

### 3.2 Camera Icon (`ic_camera`)

```
PROMPT:
cyberpunk camera icon, lens with neon ring, digital capture concept, tech viewfinder aesthetic, neon cyan glow, #00FFFF color, outlined style, 2px stroke weight, futuristic photography symbol, transparent dark background #050510, clean design, 512x512 PNG
```

---

### 3.3 Microphone Icon (`ic_mic`)

```
PROMPT:
cyberpunk microphone icon, mic with sound wave emanating, digital audio concept, voice recording aesthetic, neon cyan glow, #00FFFF color, outlined style, 2px stroke weight, tech sound symbol, transparent dark background #050510, minimalist design, 512x512 PNG
```

---

### 3.4 Backup Icon (`ic_backup`)

```
PROMPT:
cyberpunk cloud upload icon, cloud with upward arrow, digital backup concept, data storage aesthetic, neon cyan glow, #00FFFF color, outlined style, 2px stroke weight, tech cloud symbol, circuit details, transparent dark background #050510, clean design, 512x512 PNG
```

---

### 3.5 Restore Icon (`ic_restore`)

```
PROMPT:
cyberpunk restore icon, circular refresh arrow with circuit pattern, data recovery concept, digital sync aesthetic, neon cyan glow, #00FFFF color, outlined style, 2px stroke weight, tech reload symbol, transparent dark background #050510, minimalist design, 512x512 PNG
```

---

### 3.6 Verified Icon (`ic_verified`)

```
PROMPT:
cyberpunk checkmark icon, verification symbol with glow, digital confirmed concept, success aesthetic, neon green-cyan glow, #00FF88 color, outlined style, 2px stroke weight, tech approved symbol, transparent dark background #050510, clean design, 512x512 PNG
```

---

## Section 4: Background Assets

### 4.1 Splash Screen Background

**Purpose:** App launch screen background
**Size:** 1080x1920px (Full HD portrait)

```
PROMPT:
cyberpunk dark gradient background, vertical gradient from deep black #050510 at top to dark purple #1A1530 at bottom, subtle circuit board pattern overlay, floating digital particles, tech grid lines barely visible, futuristic atmosphere, no text, no logos, clean dark aesthetic, moody ambient, 1080x1920 PNG

NEGATIVE PROMPT:
bright colors, cyan dominant, magenta dominant, text, logos, busy pattern, realistic, photograph, people, objects
```

---

### 4.2 Circuit Pattern Overlay

**Purpose:** Subtle texture for cards and backgrounds
**Size:** 512x512px (tileable)

```
PROMPT:
seamless tileable circuit board pattern, dark purple #1A1530 background, very subtle cyan #00FFFF circuit lines at 10% opacity, digital tech texture, PCB traces, connection nodes, futuristic grid, minimalist barely visible pattern, seamless edges, 512x512 PNG

NEGATIVE PROMPT:
bright, high contrast, busy, colorful, magenta, red, green, non-tileable, text
```

---

### 4.3 Particle Texture

**Purpose:** Floating particles for splash animation
**Size:** 512x512px (tileable)

```
PROMPT:
floating digital particles on transparent background, small cyan #00FFFF dots scattered, varying sizes, some with tiny glow, digital dust aesthetic, sparse distribution, futuristic atmosphere particles, minimalist, tileable pattern, PNG with alpha transparency

NEGATIVE PROMPT:
dense, busy, colorful, magenta, large particles, stars, realistic dust
```

---

## Section 5: Logo Variants

### 5.1 Logo with Glow Effect

**Purpose:** Enhanced logo for splash screen
**Size:** 512x512px

```
PROMPT:
ZCHAT logo recreation, cyberpunk style, letter Z in futuristic angular design, neon cyan #00FFFF glowing outline, dark purple-black background #050510, subtle circuit pattern on letter, strong outer glow effect, tech aesthetic, clean sharp edges, premium quality, 512x512 PNG

NOTE: May need to use existing logo and just add glow effect in post-processing
```

---

## Section 6: Button/UI Element Textures

### 6.1 Glass Texture for Cards

**Purpose:** Glassmorphism background texture
**Size:** 256x256px

```
PROMPT:
frosted glass texture, subtle noise pattern, very light cyan #00FFFF tint at edges, semi-transparent, glass panel aesthetic, minimalist, blurry background simulation, 20% opacity appearance, clean, 256x256 PNG

NEGATIVE PROMPT:
colorful, busy pattern, solid color, opaque, magenta, red, green
```

---

## Post-Processing Instructions

After generating each asset in Nano Banana Pro:

### For Icons:
1. **Remove background** - Ensure pure transparency
2. **Check color accuracy** - Adjust to exact hex values if needed
3. **Verify stroke consistency** - Should be 2px throughout
4. **Scale test** - View at 24x24dp to ensure legibility
5. **Export as Vector** - Use Android Studio's Vector Asset tool

### For Backgrounds:
1. **Resolution check** - Ensure 1080x1920 or specified size
2. **Color calibration** - Match exact hex values
3. **Compression** - Use PNG-8 for simpler gradients, PNG-24 for detailed
4. **Test on device** - Check appearance on different screen densities

### Conversion to Android Vector Drawable:
```bash
# Using Android Studio:
1. Right-click drawable folder
2. New > Vector Asset
3. Choose "Local file"
4. Select generated PNG
5. Adjust size to 24dp or 48dp
6. Export
```

---

## Quick Reference: All Icon Names

| Priority | Icon Name | Color | Description |
|----------|-----------|-------|-------------|
| P0 | `ic_lock_shield` | Cyan | Shield + padlock |
| P0 | `ic_no_server` | Magenta | Server with X |
| P0 | `ic_anonymous` | Cyan | Ghost/mask |
| P0 | `ic_no_tracking` | Magenta | Eye with slash |
| P0 | `ic_chat_bubble` | Cyan | Chat bubble |
| P0 | `ic_send` | Cyan | Arrow right |
| P0 | `ic_add` | Cyan | Plus sign |
| P1 | `ic_settings` | Cyan | Gear/cog |
| P1 | `ic_contacts` | Cyan | Person |
| P1 | `ic_qr_code` | Cyan | QR code |
| P1 | `ic_wallet` | Cyan | Wallet + Z |
| P1 | `ic_copy` | Cyan | Two rectangles |
| P1 | `ic_destroy` | Magenta | Explosion |
| P1 | `ic_warning` | Magenta | Triangle |
| P2 | `ic_attach` | Cyan | Paperclip |
| P2 | `ic_camera` | Cyan | Camera |
| P2 | `ic_mic` | Cyan | Microphone |
| P2 | `ic_backup` | Cyan | Cloud upload |
| P2 | `ic_restore` | Cyan | Refresh |
| P2 | `ic_verified` | Green | Checkmark |

---

## Tips for Nano Banana Pro

1. **Generate multiple variations** - Create 3-4 versions of each icon and pick the best
2. **Iterate on style** - If first result isn't quite right, adjust keywords
3. **Consistency check** - Compare all icons side by side for style coherence
4. **Batch similar icons** - Generate all cyan icons together for consistency
5. **Save prompts** - Keep successful prompts for future reference

---

**Document Status:** Ready for asset generation
**Next Step:** Generate P0 icons first, then proceed to Phase 1 implementation
