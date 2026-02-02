# ZCHAT UI Redesign Plan

**Version:** 1.0
**Created:** 2026-01-26
**Status:** Planning Phase

---

## Executive Summary

Complete UI overhaul to transform ZCHAT from a generic-looking app into a premium, visually stunning cyberpunk messenger. The redesign focuses on maximum neon glow effects, glassmorphism, custom icons, and a cohesive design language.

### Theme Strategy
- **3 Themes:** Cyberpunk (default), Light, Dark
- **Remove:** Default, DeepCyber (merge best elements into Cyberpunk)
- **Effects Level:** Maximum (glow, animations, glassmorphism)
- **Custom Icons:** Full custom neon-style icon set

---

## Part 1: Design System

### 1.1 Color Palette - Cyberpunk Theme

#### Base Colors (60% - Backgrounds)
| Name | Hex | RGB | Usage |
|------|-----|-----|-------|
| `bgDeep` | `#050510` | 5, 5, 16 | App background |
| `bgPrimary` | `#0D0B1A` | 13, 11, 26 | Primary surfaces |
| `bgSecondary` | `#1A1530` | 26, 21, 48 | Cards, dialogs |
| `bgTertiary` | `#251E45` | 37, 30, 69 | Elevated surfaces |

#### Surface Colors (30% - Cards, Components)
| Name | Hex | RGB | Usage |
|------|-----|-----|-------|
| `surfaceGlass` | `#1A153080` | 26, 21, 48, 50% | Glassmorphism panels |
| `surfaceBorder` | `#00FFFF20` | Cyan 12% | Glass borders |
| `surfaceHover` | `#00FFFF10` | Cyan 6% | Hover states |

#### Accent Colors (10% - Interactive Elements)
| Name | Hex | RGB | Usage |
|------|-----|-----|-------|
| `accentCyan` | `#00FFFF` | 0, 255, 255 | Primary accent, buttons |
| `accentCyanGlow` | `#00FFFF66` | Cyan 40% | Glow effect |
| `accentMagenta` | `#FF00FF` | 255, 0, 255 | Secondary accent |
| `accentMagentaGlow` | `#FF00FF66` | Magenta 40% | Glow effect |
| `accentPurple` | `#8B5CF6` | 139, 92, 246 | Tertiary accent |

#### Text Colors
| Name | Hex | RGB | Usage |
|------|-----|-----|-------|
| `textPrimary` | `#E8E8FF` | 232, 232, 255 | Primary text (NOT pure white) |
| `textSecondary` | `#A8A8CC` | 168, 168, 204 | Secondary text |
| `textTertiary` | `#6868A0` | 104, 104, 160 | Muted text |
| `textCyan` | `#00FFFF` | 0, 255, 255 | Highlighted text |
| `textMagenta` | `#FF00FF` | 255, 0, 255 | Accent text |

#### Semantic Colors
| Name | Hex | Usage |
|------|-----|-------|
| `success` | `#00FF88` | Success states (green-cyan) |
| `warning` | `#FFB800` | Warning states (amber) |
| `error` | `#FF0066` | Error states (magenta-red) |
| `info` | `#00BFFF` | Info states (light cyan) |

### 1.2 Typography

#### Font Family
- **Primary:** `Orbitron` or `Rajdhani` (Cyberpunk style)
- **Secondary:** `Inter` or `Roboto` (Body text)
- **Monospace:** `JetBrains Mono` (Addresses, codes)

#### Type Scale
| Name | Size | Weight | Usage |
|------|------|--------|-------|
| `displayLg` | 48sp | Bold | Splash screen title |
| `displayMd` | 36sp | Bold | Screen titles |
| `displaySm` | 28sp | SemiBold | Section headers |
| `headingLg` | 24sp | SemiBold | Card titles |
| `headingMd` | 20sp | Medium | List headers |
| `headingSm` | 18sp | Medium | Subsections |
| `bodyLg` | 16sp | Regular | Primary body text |
| `bodyMd` | 14sp | Regular | Secondary body text |
| `bodySm` | 12sp | Regular | Captions |
| `labelLg` | 14sp | SemiBold | Button labels |
| `labelMd` | 12sp | Medium | Tags, badges |
| `labelSm` | 10sp | Medium | Timestamps |

### 1.3 Effects & Modifiers

#### Glow Effects (Compose Modifiers)
```kotlin
// Cyan glow for primary elements
fun Modifier.cyanGlow(
    radius: Dp = 16.dp,
    alpha: Float = 0.4f
) = this.shadow(
    elevation = radius,
    spotColor = Color(0x6600FFFF),
    ambientColor = Color(0x3300FFFF)
)

// Magenta glow for secondary elements
fun Modifier.magentaGlow(
    radius: Dp = 12.dp,
    alpha: Float = 0.3f
) = this.shadow(
    elevation = radius,
    spotColor = Color(0x66FF00FF),
    ambientColor = Color(0x33FF00FF)
)

// Pulsing glow animation
fun Modifier.pulsingGlow(
    color: Color = Color(0xFF00FFFF),
    durationMs: Int = 2000
)
```

#### Glassmorphism
```kotlin
fun Modifier.glassMorphism(
    backgroundColor: Color = Color(0x401A1530),
    borderColor: Color = Color(0x2000FFFF),
    blurRadius: Dp = 20.dp,
    borderWidth: Dp = 1.dp,
    cornerRadius: Dp = 16.dp
) = this
    .clip(RoundedCornerShape(cornerRadius))
    .background(backgroundColor)
    .border(borderWidth, borderColor, RoundedCornerShape(cornerRadius))
```

#### Gradients
```kotlin
// Primary gradient (Cyan to Magenta)
val CyberGradient = Brush.linearGradient(
    colors = listOf(Color(0xFF00FFFF), Color(0xFFFF00FF))
)

// Background gradient (Deep to Surface)
val BackgroundGradient = Brush.verticalGradient(
    colors = listOf(Color(0xFF050510), Color(0xFF1A1530))
)

// Shimmer effect for loading
val ShimmerGradient = Brush.linearGradient(
    colors = listOf(
        Color(0x0000FFFF),
        Color(0x4000FFFF),
        Color(0x0000FFFF)
    )
)
```

### 1.4 Spacing & Layout

#### Spacing Scale
| Token | Value | Usage |
|-------|-------|-------|
| `xxs` | 4dp | Icon padding |
| `xs` | 8dp | Compact spacing |
| `sm` | 12dp | Small gaps |
| `md` | 16dp | Standard spacing |
| `lg` | 24dp | Section spacing |
| `xl` | 32dp | Large gaps |
| `xxl` | 48dp | Screen padding |
| `xxxl` | 64dp | Major sections |

#### Corner Radius
| Token | Value | Usage |
|-------|-------|-------|
| `none` | 0dp | Sharp edges |
| `sm` | 8dp | Small elements |
| `md` | 12dp | Buttons, inputs |
| `lg` | 16dp | Cards |
| `xl` | 24dp | Dialogs |
| `full` | 999dp | Pills, avatars |

---

## Part 2: Component Library

### 2.1 Buttons

#### Primary Button (Neon Cyan)
- Background: `accentCyan`
- Text: `bgDeep`
- Glow: 16dp cyan glow
- Corners: `md` (12dp)
- Height: 56dp
- Animation: Pulse on press

#### Secondary Button (Glass)
- Background: Glassmorphism
- Text: `accentCyan`
- Border: 1dp cyan @ 20%
- Corners: `md` (12dp)
- Height: 56dp

#### Ghost Button
- Background: Transparent
- Text: `accentCyan`
- No border
- Height: 48dp

#### FAB (Floating Action Button)
- Background: `accentCyan`
- Icon: `bgDeep`
- Size: 64dp
- Glow: 20dp cyan glow, pulsing
- Shadow: Elevated

### 2.2 Cards

#### Glass Card
```
┌──────────────────────────────────┐
│  [Glass background 40% opacity]  │
│  [Blur: 20dp]                    │
│  [Border: 1dp cyan @ 20%]        │
│  [Corner: 16dp]                  │
│  [Padding: 16dp]                 │
└──────────────────────────────────┘
```

#### Conversation Card
```
┌──────────────────────────────────┐
│ [Avatar]  Contact Name     12:34│
│           Last message preview...│
│           [Unread badge]         │
└──────────────────────────────────┘
```
- Avatar: 48dp with cyan border glow
- Unread badge: Magenta with glow

### 2.3 Message Bubbles

#### Outgoing Message
- Background: Gradient (Cyan → Cyan-Dark)
- Text: `bgDeep`
- Corner: 16dp (bottom-right: 4dp)
- Glow: Subtle cyan outer glow

#### Incoming Message
- Background: Glass (bgSecondary @ 60%)
- Text: `textPrimary`
- Border: 1dp purple @ 30%
- Corner: 16dp (bottom-left: 4dp)

### 2.4 Input Fields

#### Text Input
- Background: Glass
- Border: 1dp inactive, 2dp cyan active
- Text: `textPrimary`
- Placeholder: `textTertiary`
- Corner: `md`
- Focus glow: Cyan

### 2.5 Navigation

#### Top App Bar
- Background: Transparent or glass
- Title: Gradient text (Cyan → Magenta)
- Icons: Cyan with subtle glow

#### Bottom Navigation (if needed)
- Background: Glass
- Active: Cyan icon + glow + label
- Inactive: `textTertiary`

---

## Part 3: Custom Icon Set

### Required Icons (Neon Style)

#### Navigation Icons
- `ic_chat` - Chat bubble with neon outline
- `ic_contacts` - Person with circuit lines
- `ic_settings` - Gear with cyber detail
- `ic_qr_code` - QR with neon corners
- `ic_wallet` - Wallet with Z symbol

#### Action Icons
- `ic_send` - Arrow with glow trail
- `ic_attach` - Paperclip with circuit
- `ic_camera` - Camera lens with neon ring
- `ic_mic` - Microphone with wave
- `ic_add` - Plus with glow
- `ic_search` - Magnifier with cyber detail

#### Status Icons
- `ic_privacy_lock` - Shield with lock
- `ic_encrypted` - Lock with circuit
- `ic_verified` - Checkmark with glow
- `ic_warning` - Triangle with pulse
- `ic_error` - X with magenta glow

#### Feature Icons
- `ic_destroy` - Explosion/shatter effect
- `ic_emergency` - Alert with pulse
- `ic_backup` - Cloud with arrows
- `ic_restore` - Refresh with circuit

### Icon Style Guidelines
- Line weight: 2dp
- Corner radius: 2dp minimum
- Glow: Optional outer glow for emphasis
- Colors: Mono (uses tint) or gradient
- Size: 24dp standard, 48dp for large

---

## Part 4: Screen Designs

### 4.1 Splash Screen (CRITICAL FIX)

**Current Problem:** Bright cyan background (#00D9FF) is jarring

**New Design:**
```
┌──────────────────────────────────┐
│                                  │
│     [Animated background]        │
│     - Dark gradient base         │
│     - Floating particles         │
│     - Subtle grid pattern        │
│                                  │
│          ╔═══════════╗           │
│          ║   ZCHAT   ║           │
│          ║   LOGO    ║           │
│          ╚═══════════╝           │
│          [Neon glow pulse]       │
│                                  │
│     "True Privacy. Zero          │
│      Compromise."                │
│                                  │
│     [Loading indicator]          │
│     - Cyan line with glow        │
│                                  │
└──────────────────────────────────┘
```

**Colors:**
- Background: Gradient from #050510 to #1A1530
- Logo: Current cyberpunk logo (it's actually good!)
- Text: #E8E8FF with cyan glow
- Loading: Animated cyan line

### 4.2 Onboarding Welcome Screen

**New Design:**
```
┌──────────────────────────────────┐
│                                  │
│         [ZCHAT LOGO]             │
│         [Animated glow]          │
│                                  │
│    "Private messenger on         │
│     Zcash blockchain"            │
│                                  │
│                                  │
│  ┌────────────────────────────┐  │
│  │   Restore Existing Wallet  │  │
│  │   [Glass button]           │  │
│  └────────────────────────────┘  │
│                                  │
│  ┌────────────────────────────┐  │
│  │   Create New Wallet        │  │
│  │   [Primary cyan button]    │  │
│  │   [Pulsing glow]           │  │
│  └────────────────────────────┘  │
│                                  │
└──────────────────────────────────┘
```

### 4.3 Main Chat List

**New Design:**
```
┌──────────────────────────────────┐
│ ZCHAT    0.00000000 ZEC    ⚙️    │
│ u1aru...8y4 📋                   │
│ [Gradient title]  [Glass header] │
├──────────────────────────────────┤
│                                  │
│  ┌────────────────────────────┐  │
│  │ 🔒 Welcome to ZCHAT        │  │
│  │    True Privacy...         │  │
│  │    [Glass card]            │  │
│  │                            │  │
│  │ ✓ Messages encrypted...    │  │
│  │ ✓ No servers...            │  │
│  │ ✓ No phone number...       │  │
│  │ ✓ No AI, no clouds...      │  │
│  └────────────────────────────┘  │
│                                  │
│  "Tap + to send your first      │
│   private message"               │
│                                  │
│                        ┌────┐    │
│                        │ +  │    │
│                        │FAB │    │
│                        └────┘    │
│                    [Pulsing glow]│
│                                  │
│ ↻ Syncing...              58s   │
└──────────────────────────────────┘
```

### 4.4 Emergency Data Wipe

**New Design:**
```
┌──────────────────────────────────┐
│                                  │
│         [WARNING ICON]           │
│         Magenta glow             │
│         Pulsing animation        │
│                                  │
│     "Emergency Data Wipe"        │
│     [Gradient text]              │
│                                  │
│  ┌────────────────────────────┐  │
│  │ [Glass card]               │  │
│  │                            │  │
│  │ 🛡️ PIN Protection          │  │
│  │    Set a PIN to protect... │  │
│  │                            │  │
│  │ ⚡ Instant Wipe             │  │
│  │    All messages, contacts..│  │
│  │                            │  │
│  │ ⚠️ Unrecoverable           │  │
│  │    Make sure you have...   │  │
│  └────────────────────────────┘  │
│                                  │
│  ┌────────────────────────────┐  │
│  │   Set Up PIN Now           │  │
│  │   [Magenta button]         │  │
│  │   [Warning glow]           │  │
│  └────────────────────────────┘  │
│                                  │
│        Skip for Now              │
│        [Ghost text]              │
│                                  │
└──────────────────────────────────┘
```

---

## Part 5: Implementation Plan

### Phase 1: Foundation (Week 1)

#### 1.1 Color System Overhaul
- [ ] Create `CyberpunkColors.kt` with all new colors
- [ ] Create `LightThemeColors.kt` with light mode colors
- [ ] Create `DarkThemeColors.kt` with dark mode colors
- [ ] Remove DeepCyber and Default themes
- [ ] Update theme selector to show only 3 options

#### 1.2 Typography System
- [ ] Add Orbitron/Rajdhani font files
- [ ] Create `CyberpunkTypography.kt`
- [ ] Update typography scale

#### 1.3 Core Modifiers
- [ ] Create `GlowModifiers.kt` (cyanGlow, magentaGlow, pulsingGlow)
- [ ] Create `GlassMorphism.kt` modifier
- [ ] Create `GradientBrushes.kt`

### Phase 2: Components (Week 2)

#### 2.1 Button Components
- [ ] `CyberButton` - Primary with glow
- [ ] `GlassButton` - Secondary glass style
- [ ] `GhostButton` - Text only
- [ ] `CyberFAB` - FAB with pulse

#### 2.2 Card Components
- [ ] `GlassCard` - Glassmorphism card
- [ ] `ConversationCard` - Chat list item
- [ ] `InfoCard` - Feature/info display

#### 2.3 Input Components
- [ ] `CyberTextField` - Styled text input
- [ ] `CyberPinInput` - PIN entry field

### Phase 3: Icons (Week 2-3)

#### 3.1 Generate Custom Icons
- [ ] Navigation icons (5)
- [ ] Action icons (6)
- [ ] Status icons (5)
- [ ] Feature icons (4)

#### 3.2 Icon Integration
- [ ] Create icon resources
- [ ] Update all icon references
- [ ] Add glow effects where needed

### Phase 4: Screen Updates (Week 3-4)

#### 4.1 Critical Screens (Highest Priority)
- [ ] **Splash Screen** - Fix cyan background, add animations
- [ ] **Welcome/Onboarding** - New design with glass buttons
- [ ] **Main Chat List** - Glass cards, FAB glow

#### 4.2 Important Screens
- [ ] Emergency Wipe screens
- [ ] PIN setup screens
- [ ] Chat detail view
- [ ] Settings screens

#### 4.3 Secondary Screens
- [ ] QR code screens
- [ ] About/Info screens
- [ ] All remaining screens

### Phase 5: Animations (Week 4)

- [ ] Splash screen particle animation
- [ ] FAB pulsing glow
- [ ] Button press feedback
- [ ] Screen transitions
- [ ] Loading indicators

### Phase 6: Polish & Testing (Week 5)

- [ ] Visual consistency review
- [ ] Performance optimization
- [ ] Battery impact testing
- [ ] Accessibility check
- [ ] Multiple device testing

---

## Part 6: Assets Required (for Nano Banana Pro)

### Logo & Branding
1. **App Icon** - Cyberpunk Z logo (already have good version)
2. **Splash Logo** - Animated version with glow

### Custom Icons (PNG/SVG)
Generate with neon cyberpunk style:
1. Chat bubble - outline with circuit details
2. Contact/Person - with tech elements
3. Settings gear - cyber style
4. QR code - neon corners
5. Lock/Shield - security icon
6. Send arrow - with trail
7. Plus/Add - with glow
8. Warning/Alert - pulsing style
9. Destroy/Delete - explosion effect
10. Wallet - with Z symbol

### Background Assets
1. Particle overlay texture (subtle grid/circuit pattern)
2. Gradient mesh backgrounds for variation

### Prompt Guidelines for AI Generation
```
Style: Cyberpunk, neon, futuristic
Colors: Cyan (#00FFFF), Magenta (#FF00FF), Deep purple background
Line weight: 2px
Glow: Outer glow effect
Background: Transparent or dark (#050510)
Format: SVG preferred, PNG with alpha
Size: 512x512 for icons, 1080x1920 for screens
```

---

## Appendix: Reference Links

- [Mobile App Design Trends 2026](https://natively.dev/blog/best-mobile-app-design-trends-2026)
- [Dark Mode Best Practices](https://www.designstudiouiux.com/blog/dark-mode-ui-design-best-practices/)
- [Glassmorphism Guide](https://uxpilot.ai/blogs/glassmorphism-ui)
- [Cyberpunk Color Palettes](https://metaverseplanet.net/blog/cyberpunk-color-palette-generator/)
- [Dribbble Cyberpunk UI](https://dribbble.com/search/cyberpunk-ui)

---

**Next Steps:**
1. Review and approve this plan
2. Generate custom icons using Nano Banana Pro
3. Begin Phase 1 implementation
