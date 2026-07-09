package co.electriccoin.zcash.ui.design.theme.colors

/**
 * NIGHTWIRE LIGHT — Daylight Edition mapping into ZashiColorsInternal
 *
 * Bone paper background with teal-cyan/garnet/forest-green accents.
 * Maps the Nightwire color palette to the ZashiColorsInternal structure,
 * auto-propagating the theme to all Zashi-inherited screens.
 */
val NightwireLightZashiColorsInternal =
    ZashiColorsInternal(
        Surfaces =
            Surfaces(
                bgPrimary = NightwireLightColors.BgBase,
                bgAdjust = NightwireLightColors.BgElevated,
                bgSecondary = NightwireLightColors.BgSurface,
                bgTertiary = NightwireLightColors.BgElevated,
                bgQuaternary = NightwireLightColors.BgHover,
                strokePrimary = NightwireLightColors.AccentPrimary,
                strokeSecondary = NightwireLightColors.BorderDefault,
                bgAlt = NightwireLightColors.TextPrimary,
                bgHide = NightwireLightColors.BgBase,
                brandBg = NightwireLightColors.AccentPrimary,
                brandFg = NightwireLightColors.TextOnAccent,
                divider = NightwireLightColors.BorderDefault
            ),
        Text =
            Text(
                textPrimary = NightwireLightColors.TextPrimary,
                textSecondary = NightwireLightColors.TextSecondary,
                textTertiary = NightwireLightColors.TextTertiary,
                textQuaternary = NightwireLightColors.TextTertiary,
                textSupport = NightwireLightColors.TextSecondary,
                textDisabled = NightwireLightColors.TextTertiary,
                textError = NightwireLightColors.ColorDanger,
                textLink = NightwireLightColors.AccentPrimary,
                textLight = NightwireLightColors.TextPrimary,
                textLightSupport = NightwireLightColors.TextSecondary
            ),
        Btns =
            Btns(
                Brand =
                    BtnBrand(
                        btnBrandBg = NightwireLightColors.AccentPrimary,
                        btnBrandBgHover = NightwireLightColors.AccentPrimaryDim,
                        btnBrandFg = NightwireLightColors.TextOnAccent,
                        btnBrandFgHover = NightwireLightColors.TextOnAccent,
                        btnBrandBgDisabled = NightwireLightColors.BgHover,
                        btnBrandFgDisabled = NightwireLightColors.TextTertiary
                    ),
                Secondary =
                    BtnSecondary(
                        btnSecondaryBg = NightwireLightColors.BgBase,
                        btnSecondaryBgHover = NightwireLightColors.BgSurface,
                        btnSecondaryFg = NightwireLightColors.AccentPrimary,
                        btnSecondaryFgHover = NightwireLightColors.AccentPrimary,
                        btnSecondaryBorder = NightwireLightColors.BorderActive,
                        btnSecondaryBorderHover = NightwireLightColors.AccentPrimary,
                        btnSecondaryBgDisabled = NightwireLightColors.BgElevated,
                        btnSecondaryFgDisabled = NightwireLightColors.TextTertiary
                    ),
                Tertiary =
                    BtnTertiary(
                        btnTertiaryBg = NightwireLightColors.BgElevated,
                        btnTertiaryBgHover = NightwireLightColors.BgInput,
                        btnTertiaryFg = NightwireLightColors.AccentPrimary,
                        btnTertiaryFgHover = NightwireLightColors.AccentPrimary,
                        btnTertiaryBgDisabled = NightwireLightColors.BgElevated,
                        btnTertiaryFgDisabled = NightwireLightColors.TextTertiary
                    ),
                Quaternary =
                    BtnQuaternary(
                        btnQuartBg = NightwireLightColors.BgHover,
                        btnQuartBgHover = NightwireLightColors.BgInput,
                        btnQuartFg = NightwireLightColors.TextPrimary,
                        btnQuartFgHover = NightwireLightColors.TextPrimary,
                        btnQuartBgDisabled = NightwireLightColors.BgElevated,
                        btnQuartFgDisabled = NightwireLightColors.TextTertiary
                    ),
                Destructive1 =
                    BtnDestructive1(
                        btnDestroy1Bg = NightwireLightColors.BgBase,
                        btnDestroy1BgHover = NightwireLightColors.BgSurface,
                        btnDestroy1Fg = NightwireLightColors.ColorDanger,
                        btnDestroy1FgHover = NightwireLightColors.ColorDanger,
                        btnDestroy1Border = NightwireLightColors.ColorDanger,
                        btnDestroy1BorderHover = NightwireLightColors.DestroyRed,
                        btnDestroy1BgDisabled = NightwireLightColors.BgElevated,
                        btnDestroy1FgDisabled = NightwireLightColors.TextTertiary
                    ),
                Destructive2 =
                    BtnDestructive2(
                        btnDestroy2Bg = NightwireLightColors.ColorDanger,
                        btnDestroy2BgHover = NightwireLightColors.DestroyRed,
                        btnDestroy2Fg = NightwireLightColors.TextOnAccent,
                        btnDestroy2BgDisabled = NightwireLightColors.BgElevated,
                        btnDestroy2FgDisabled = NightwireLightColors.TextTertiary
                    ),
                Primary =
                    BtnPrimary(
                        btnPrimaryBg = NightwireLightColors.AccentPrimary,
                        btnPrimaryBgHover = NightwireLightColors.AccentPrimaryDim,
                        btnPrimaryFg = NightwireLightColors.TextOnAccent,
                        btnPrimaryBgDisabled = NightwireLightColors.BgHover,
                        btnBoldFgDisabled = NightwireLightColors.TextTertiary
                    ),
                Ghost =
                    BtnGhost(
                        btnGhostBg = NightwireLightColors.BgBase,
                        btnGhostBgHover = NightwireLightColors.BgSurface,
                        btnGhostFg = NightwireLightColors.AccentPrimary,
                        btnGhostBgDisabled = NightwireLightColors.BgElevated,
                        btnGhostFgDisabled = NightwireLightColors.TextTertiary
                    )
            ),
        Avatars =
            Avatars(
                avatarProfileBorder = NightwireLightColors.AccentPrimary,
                avatarBg = NightwireLightColors.BgHover,
                avatarBgSecondary = NightwireLightColors.BgElevated,
                avatarStatus = NightwireLightColors.AccentSuccess,
                avatarTextFg = NightwireLightColors.TextPrimary,
                avatarBadgeBg = NightwireLightColors.AccentSecondary,
                avatarBadgeFg = NightwireLightColors.TextOnAccent
            ),
        Sliders =
            Sliders(
                sliderHandleBorder = NightwireLightColors.AccentPrimary,
                sliderHandleBg = NightwireLightColors.BgBase
            ),
        Inputs =
            Inputs(
                Default =
                    InputDefault(
                        bg = NightwireLightColors.BgInput,
                        bgAlt = NightwireLightColors.BgElevated,
                        label = NightwireLightColors.TextPrimary,
                        text = NightwireLightColors.TextSecondary,
                        hint = NightwireLightColors.TextTertiary,
                        required = NightwireLightColors.AccentSecondary,
                        icon = NightwireLightColors.AccentPrimaryDim,
                        stroke = NightwireLightColors.BorderDefault
                    ),
                Hover =
                    InputHover(
                        bg = NightwireLightColors.BgHover,
                        bgAlt = NightwireLightColors.BgInput,
                        asideBg = NightwireLightColors.BgElevated,
                        stroke = NightwireLightColors.BorderActive,
                        label = NightwireLightColors.TextPrimary,
                        text = NightwireLightColors.TextSecondary,
                        hint = NightwireLightColors.TextTertiary,
                        icon = NightwireLightColors.AccentPrimary,
                        required = NightwireLightColors.AccentSecondary
                    ),
                Filled =
                    InputFilled(
                        bg = NightwireLightColors.BgInput,
                        bgAlt = NightwireLightColors.BgElevated,
                        asideBg = NightwireLightColors.BgElevated,
                        stroke = NightwireLightColors.BorderActive,
                        label = NightwireLightColors.TextPrimary,
                        text = NightwireLightColors.TextPrimary,
                        hint = NightwireLightColors.TextTertiary,
                        icon = NightwireLightColors.AccentPrimary,
                        iconMain = NightwireLightColors.AccentPrimary,
                        required = NightwireLightColors.AccentSecondary
                    ),
                Focused =
                    InputFocused(
                        bg = NightwireLightColors.BgInput,
                        asideBg = NightwireLightColors.BgElevated,
                        stroke = NightwireLightColors.AccentPrimary,
                        stroke2 = NightwireLightColors.BorderActive,
                        label = NightwireLightColors.TextPrimary,
                        text = NightwireLightColors.TextPrimary,
                        hint = NightwireLightColors.TextTertiary,
                        icon = NightwireLightColors.AccentPrimary,
                        iconMain = NightwireLightColors.AccentPrimary,
                        defaultRequired = NightwireLightColors.AccentSecondary
                    ),
                Disabled =
                    InputDisabled(
                        bg = NightwireLightColors.BgElevated,
                        stroke = NightwireLightColors.BorderDefault,
                        label = NightwireLightColors.TextTertiary,
                        text = NightwireLightColors.TextTertiary,
                        hint = NightwireLightColors.TextTertiary,
                        icon = NightwireLightColors.TextTertiary,
                        iconMain = NightwireLightColors.TextTertiary,
                        required = NightwireLightColors.TextTertiary
                    ),
                ErrorDefault =
                    InputErrorDefault(
                        bg = NightwireLightColors.BgInput,
                        bgAlt = NightwireLightColors.BgElevated,
                        label = NightwireLightColors.TextPrimary,
                        text = NightwireLightColors.TextSecondary,
                        textAside = NightwireLightColors.TextTertiary,
                        textMain = NightwireLightColors.TextPrimary,
                        hint = NightwireLightColors.ColorDanger,
                        icon = NightwireLightColors.ColorDanger,
                        iconMain = NightwireLightColors.ColorDanger,
                        stroke = NightwireLightColors.ColorDanger,
                        strokeAlt = NightwireLightColors.BorderDefault,
                        dropdown = NightwireLightColors.TextTertiary
                    ),
                ErrorHover =
                    InputErrorHover(
                        bg = NightwireLightColors.BgInput,
                        bgAlt = NightwireLightColors.BgElevated,
                        label = NightwireLightColors.TextPrimary,
                        text = NightwireLightColors.TextSecondary,
                        textAside = NightwireLightColors.TextTertiary,
                        textMain = NightwireLightColors.TextPrimary,
                        hint = NightwireLightColors.ColorDanger,
                        icon = NightwireLightColors.ColorDanger,
                        iconMain = NightwireLightColors.ColorDanger,
                        stroke = NightwireLightColors.ColorDanger,
                        strokeAlt = NightwireLightColors.BorderDefault,
                        dropdown = NightwireLightColors.TextTertiary
                    ),
                ErrorFilled =
                    InputErrorFilled(
                        bg = NightwireLightColors.BgInput,
                        bgAlt = NightwireLightColors.BgElevated,
                        label = NightwireLightColors.TextPrimary,
                        text = NightwireLightColors.TextPrimary,
                        textAside = NightwireLightColors.TextTertiary,
                        hint = NightwireLightColors.ColorDanger,
                        icon = NightwireLightColors.ColorDanger,
                        iconMain = NightwireLightColors.ColorDanger,
                        stroke = NightwireLightColors.ColorDanger,
                        strokeAlt = NightwireLightColors.BorderDefault,
                        dropdown = NightwireLightColors.TextTertiary
                    ),
                ErrorFocused =
                    InputErrorFocused(
                        bg = NightwireLightColors.BgInput,
                        bgAlt = NightwireLightColors.BgElevated,
                        label = NightwireLightColors.TextPrimary,
                        text = NightwireLightColors.TextPrimary,
                        textAside = NightwireLightColors.TextTertiary,
                        hint = NightwireLightColors.ColorDanger,
                        icon = NightwireLightColors.ColorDanger,
                        iconMain = NightwireLightColors.ColorDanger,
                        stroke = NightwireLightColors.ColorDanger,
                        strokeAlt = NightwireLightColors.BorderDefault,
                        dropdown = NightwireLightColors.TextTertiary
                    )
            ),
        Accordion =
            Accordion(
                xBtnDefaultFg = NightwireLightColors.AccentPrimary,
                xBtnHoverBg = NightwireLightColors.BgInput,
                xBtnOnHoverBg = NightwireLightColors.BgInput,
                xBtnHoverFg = NightwireLightColors.AccentPrimary,
                xBtnFocusBg = NightwireLightColors.BgHover,
                xBtnFocusFg = NightwireLightColors.AccentPrimary,
                xBtnFocusStroke = NightwireLightColors.AccentPrimary,
                xBtnDisabledBg = NightwireLightColors.BgElevated,
                xBtnDisabledFg = NightwireLightColors.TextTertiary,
                defaultBg = NightwireLightColors.BgBase,
                defaultStroke = NightwireLightColors.BorderDefault,
                defaultIcon = NightwireLightColors.AccentPrimaryDim,
                focusStroke = NightwireLightColors.AccentPrimary,
                expandedBg = NightwireLightColors.BgSurface,
                expandedHoverBg = NightwireLightColors.BgElevated,
                expandedStroke = NightwireLightColors.BorderActive,
                dividers = NightwireLightColors.BorderDefault,
                expandedFocusStroke = NightwireLightColors.AccentPrimary
            ),
        Switcher =
            Switcher(
                defaultText = NightwireLightColors.TextSecondary,
                defaultTagBg = NightwireLightColors.BgHover,
                defaultIcon = NightwireLightColors.AccentPrimaryDim,
                hoverBg = NightwireLightColors.BgInput,
                hoverTagBg = NightwireLightColors.BgElevated,
                hoverIcon = NightwireLightColors.AccentPrimary,
                hoverText = NightwireLightColors.TextPrimary,
                hoverTagText = NightwireLightColors.TextPrimary,
                selectedBg = NightwireLightColors.AccentPrimary,
                selectedIcon = NightwireLightColors.TextOnAccent,
                selectedText = NightwireLightColors.TextOnAccent,
                selectedTagBg = NightwireLightColors.AccentPrimaryDim,
                selectedStroke = NightwireLightColors.AccentPrimary,
                disabledText = NightwireLightColors.TextTertiary,
                disabledIcon = NightwireLightColors.TextTertiary,
                disabledTagBg = NightwireLightColors.BgElevated,
                surfacePrimary = NightwireLightColors.BgSurface
            ),
        Toggles =
            Toggles(
                tgDefaultBg = NightwireLightColors.BgHover,
                tgDefaultFg = NightwireLightColors.TextTertiary,
                tgActiveBg = NightwireLightColors.AccentPrimary,
                tgActiveFg = NightwireLightColors.TextOnAccent,
                tgDefaultHoverBg = NightwireLightColors.BgInput,
                tgDefaultHoverFg = NightwireLightColors.TextSecondary,
                tgActiveHoverBg = NightwireLightColors.AccentPrimaryDim,
                tgActiveHoverFg = NightwireLightColors.TextOnAccent,
                tgDefaultDisabledBg = NightwireLightColors.BgElevated,
                tgDefaultDisabledFg = NightwireLightColors.TextTertiary,
                tgActiveDisabledBg = NightwireLightColors.BgElevated,
                tgActiveDisabledFg = NightwireLightColors.TextTertiary
            ),
        Tags =
            Tags(
                tcDefaultFg = NightwireLightColors.AccentPrimary,
                tcHoverBg = NightwireLightColors.BgInput,
                tcHoverFg = NightwireLightColors.AccentPrimary,
                tcCountBg = NightwireLightColors.AccentSecondary,
                tcCountFg = NightwireLightColors.TextOnAccent,
                statusIndicator = NightwireLightColors.AccentSuccess,
                surfacePrimary = NightwireLightColors.BgBase,
                surfaceStroke = NightwireLightColors.BorderActive
            ),
        Dropdowns =
            Dropdowns(
                Default =
                    DropdownDefault(
                        bg = NightwireLightColors.BgInput,
                        label = NightwireLightColors.TextPrimary,
                        text = NightwireLightColors.TextSecondary,
                        hint = NightwireLightColors.TextTertiary,
                        required = NightwireLightColors.AccentSecondary,
                        icon = NightwireLightColors.AccentPrimaryDim,
                        dropdown = NightwireLightColors.AccentPrimary,
                        active = NightwireLightColors.AccentPrimary
                    ),
                Filled =
                    DropdownFilled(
                        bg = NightwireLightColors.BgInput,
                        label = NightwireLightColors.TextPrimary,
                        textMain = NightwireLightColors.TextPrimary,
                        textSupport = NightwireLightColors.TextSecondary,
                        hint = NightwireLightColors.TextTertiary,
                        required = NightwireLightColors.AccentSecondary,
                        icon = NightwireLightColors.AccentPrimary,
                        dropdown = NightwireLightColors.AccentPrimary,
                        active = NightwireLightColors.AccentPrimary
                    ),
                Focused =
                    DropdownFocused(
                        bg = NightwireLightColors.BgInput,
                        stroke = NightwireLightColors.AccentPrimary,
                        label = NightwireLightColors.TextPrimary,
                        textMain = NightwireLightColors.TextPrimary,
                        textSupport = NightwireLightColors.TextSecondary,
                        hint = NightwireLightColors.TextTertiary,
                        defaultRequired = NightwireLightColors.AccentSecondary,
                        icon = NightwireLightColors.AccentPrimary,
                        dropdown = NightwireLightColors.AccentPrimary,
                        active = NightwireLightColors.AccentPrimary
                    ),
                Disabled =
                    DropdownDisabled(
                        bg = NightwireLightColors.BgElevated,
                        stroke = NightwireLightColors.BorderDefault,
                        label = NightwireLightColors.TextTertiary,
                        textMain = NightwireLightColors.TextTertiary,
                        textSupport = NightwireLightColors.TextTertiary,
                        hint = NightwireLightColors.TextTertiary,
                        required = NightwireLightColors.TextTertiary,
                        icon = NightwireLightColors.TextTertiary,
                        dropdown = NightwireLightColors.TextTertiary,
                        active = NightwireLightColors.TextTertiary
                    ),
                Parts =
                    DropdownParts(
                        scrollBar = NightwireLightColors.AccentPrimaryDim,
                        divider = NightwireLightColors.BorderDefault,
                        lhText = NightwireLightColors.TextSecondary,
                        lhBorder = NightwireLightColors.BorderDefault,
                        liTextPrimary = NightwireLightColors.TextPrimary,
                        liTextSecondary = NightwireLightColors.TextSecondary,
                        liTextTertiary = NightwireLightColors.TextTertiary,
                        liFgDisabled = NightwireLightColors.TextTertiary,
                        liIconDisabled = NightwireLightColors.TextTertiary,
                        liBgHover = NightwireLightColors.BgInput,
                        statusActive = NightwireLightColors.AccentPrimary,
                        statusMain = NightwireLightColors.AccentPrimary,
                        statusDisabled = NightwireLightColors.TextTertiary,
                        bgDisabled = NightwireLightColors.BgElevated
                    )
            ),
        Tabs =
            Tabs(
                defaultText = NightwireLightColors.TextSecondary,
                defaultIcon = NightwireLightColors.AccentPrimaryDim,
                defaultTagBg = NightwireLightColors.BgElevated,
                hoverText = NightwireLightColors.TextSecondary,
                hoverTagText = NightwireLightColors.TextSecondary,
                hoverIcon = NightwireLightColors.AccentPrimary,
                hoverTagBg = NightwireLightColors.BgInput,
                hoverBorder = NightwireLightColors.AccentPrimaryDim,
                selectedText = NightwireLightColors.AccentPrimary,
                selectedIcon = NightwireLightColors.AccentPrimary,
                selectedTagBg = NightwireLightColors.AccentPrimaryDim,
                selectedBorder = NightwireLightColors.AccentPrimary,
                disabledText = NightwireLightColors.TextTertiary,
                disabledIcon = NightwireLightColors.TextTertiary,
                disabledTagBg = NightwireLightColors.BgElevated,
                disabledTagText = NightwireLightColors.TextTertiary
            ),
        Checkboxes =
            Checkboxes(
                boxOffBg = NightwireLightColors.BgInput,
                boxOffStroke = NightwireLightColors.BorderActive,
                boxOffHoverBg = NightwireLightColors.BgHover,
                boxOffHoverStroke = NightwireLightColors.AccentPrimary,
                boxOffDisabledBg = NightwireLightColors.BgElevated,
                boxOffDisabledStroke = NightwireLightColors.BorderDefault,
                boxOnBg = NightwireLightColors.AccentPrimary,
                boxOnFg = NightwireLightColors.TextOnAccent,
                boxOnHoverBg = NightwireLightColors.AccentPrimaryDim,
                boxOnDisabledBg = NightwireLightColors.BgElevated,
                boxOnDisabledStroke = NightwireLightColors.BorderDefault,
                boxOnDisabledFg = NightwireLightColors.TextTertiary
            ),
        Loading =
            Loading(
                loadingBgPrimary = NightwireLightColors.BgBase,
                loadingBgSecondary = NightwireLightColors.BgInput,
                loadingFgPrimary = NightwireLightColors.AccentPrimary
            ),
        Modals =
            Modals(
                defaultBg = NightwireLightColors.BgElevated,
                defaultFg = NightwireLightColors.AccentPrimary,
                hoverBg = NightwireLightColors.BgInput,
                hoverFg = NightwireLightColors.AccentPrimary,
                focusedBg = NightwireLightColors.BgHover,
                focusedStroke = NightwireLightColors.AccentPrimary,
                disabledBg = NightwireLightColors.BgElevated,
                disabledFg = NightwireLightColors.TextTertiary,
                surfacePrimary = NightwireLightColors.BgElevated,
                surfaceStroke = NightwireLightColors.BorderDefault
            ),
        HintTooltips =
            HintTooltips(
                surfacePrimary = NightwireLightColors.BgInput,
                defaultBg = NightwireLightColors.BgInput,
                defaultFg = NightwireLightColors.TextPrimary,
                hoverBg = NightwireLightColors.BgHover,
                hoverFg = NightwireLightColors.TextPrimary,
                focusedBg = NightwireLightColors.BgHover,
                focusedStroke = NightwireLightColors.AccentPrimary,
                disabledBg = NightwireLightColors.BgElevated,
                disabledFg = NightwireLightColors.TextTertiary
            ),
        TwoFA =
            TwoFA(
                defaultBg = NightwireLightColors.BgInput,
                defaultStroke = NightwireLightColors.BorderDefault,
                defaultText = NightwireLightColors.TextTertiary,
                focusedBg = NightwireLightColors.BgInput,
                focusedStroke = NightwireLightColors.AccentPrimary,
                focusedText = NightwireLightColors.AccentPrimary,
                filledBg = NightwireLightColors.BgInput,
                filledStroke = NightwireLightColors.BorderActive,
                filledText = NightwireLightColors.TextPrimary,
                disabledBg = NightwireLightColors.BgElevated,
                disabledText = NightwireLightColors.TextTertiary,
                separatorDash = NightwireLightColors.AccentPrimaryDim
            ),
        Utility =
            Utility(
                Gray =
                    UtilityGray(
                        utilityGray700 = NightwireLightColors.TextPrimary,
                        utilityGray600 = NightwireLightColors.TextSecondary,
                        utilityGray500 = NightwireLightColors.TextTertiary,
                        utilityGray200 = NightwireLightColors.BgHover,
                        utilityGray50 = NightwireLightColors.BgSurface,
                        utilityGray100 = NightwireLightColors.BgElevated,
                        utilityGray400 = NightwireLightColors.TextTertiary,
                        utilityGray300 = NightwireLightColors.BgHover,
                        utilityGray900 = NightwireLightColors.TextPrimary,
                        utilityGray800 = NightwireLightColors.TextSecondary
                    ),
                SuccessGreen =
                    UtilitySuccessGreen(
                        utilitySuccess600 = NightwireLightColors.AccentSuccess,
                        utilitySuccess700 = NightwireLightColors.AccentSuccess,
                        utilitySuccess500 = NightwireLightColors.AccentSuccessDim,
                        utilitySuccess200 = NightwireLightColors.BubblePayment,
                        utilitySuccess800 = NightwireLightColors.AccentSuccess,
                        utilitySuccess50 = NightwireLightColors.BgBase,
                        utilitySuccess100 = NightwireLightColors.BubblePayment,
                        utilitySuccess400 = NightwireLightColors.AccentSuccessDim,
                        utilitySuccess300 = NightwireLightColors.AccentSuccessDim
                    ),
                ErrorRed =
                    UtilityErrorRed(
                        utilityError600 = NightwireLightColors.ColorDanger,
                        utilityError700 = NightwireLightColors.DestroyRed,
                        utilityError500 = NightwireLightColors.ColorDanger,
                        utilityError200 = NightwireLightColors.BgElevated,
                        utilityError800 = NightwireLightColors.DestroyRed,
                        utilityError50 = NightwireLightColors.BgBase,
                        utilityError100 = NightwireLightColors.BgSurface,
                        utilityError400 = NightwireLightColors.ColorDanger,
                        utilityError300 = NightwireLightColors.ColorDanger
                    ),
                WarningYellow =
                    UtilityWarningYellow(
                        utilityOrange600 = NightwireLightColors.ColorWarning,
                        utilityOrange700 = NightwireLightColors.ColorWarning,
                        utilityOrange500 = NightwireLightColors.ColorWarning,
                        utilityOrange200 = NightwireLightColors.BgElevated,
                        utilityOrange800 = NightwireLightColors.ColorWarning,
                        utilityOrange50 = NightwireLightColors.BgBase,
                        utilityOrange100 = NightwireLightColors.BgSurface,
                        utilityOrange400 = NightwireLightColors.ColorWarning,
                        utilityOrange300 = NightwireLightColors.ColorWarning
                    ),
                HyperBlue =
                    UtilityHyperBlue(
                        utilityBlueDark600 = NightwireLightColors.AccentPrimary,
                        utilityBlueDark700 = NightwireLightColors.AccentPrimary,
                        utilityBlueDark500 = NightwireLightColors.AccentPrimaryDim,
                        utilityBlueDark200 = NightwireLightColors.BgElevated,
                        utilityBlueDark800 = NightwireLightColors.AccentPrimary,
                        utilityBlueDark50 = NightwireLightColors.BgBase,
                        utilityBlueDark100 = NightwireLightColors.BgSurface,
                        utilityBlueDark400 = NightwireLightColors.AccentPrimaryDim,
                        utilityBlueDark300 = NightwireLightColors.AccentPrimaryDim
                    ),
                Indigo =
                    UtilityIndigo(
                        utilityIndigo600 = NightwireLightColors.AccentSecondary,
                        utilityIndigo700 = NightwireLightColors.AccentSecondary,
                        utilityIndigo500 = NightwireLightColors.AccentSecondaryDim,
                        utilityIndigo200 = NightwireLightColors.BgElevated,
                        utilityIndigo800 = NightwireLightColors.AccentSecondary,
                        utilityIndigo50 = NightwireLightColors.BgBase,
                        utilityIndigo100 = NightwireLightColors.BgSurface,
                        utilityIndigo400 = NightwireLightColors.AccentSecondaryDim,
                        utilityIndigo300 = NightwireLightColors.AccentSecondaryDim
                    ),
                // Zashi's "Purple" utility is used for shielded-receive badges and QR accents. In
                // Nightwire we remap those highlights to the cyan AccentPrimary so Receive/Swap/Request
                // screens stay consistent with the rest of the app instead of popping magenta.
                Purple =
                    UtilityPurple(
                        utilityPurple600 = NightwireLightColors.AccentPrimary,
                        utilityPurple700 = NightwireLightColors.AccentPrimary,
                        utilityPurple500 = NightwireLightColors.AccentPrimaryDim,
                        utilityPurple200 = NightwireLightColors.BgElevated,
                        utilityPurple800 = NightwireLightColors.AccentPrimary,
                        utilityPurple50 = NightwireLightColors.BgBase,
                        utilityPurple100 = NightwireLightColors.BgSurface,
                        utilityPurple400 = NightwireLightColors.AccentPrimaryDim,
                        utilityPurple300 = NightwireLightColors.AccentPrimaryDim,
                        utilityPurple900 = NightwireLightColors.AccentPrimary
                    ),
                Espresso =
                    UtilityEspresso(
                        utilityEspresso700 = NightwireLightColors.TextPrimary,
                        utilityEspresso600 = NightwireLightColors.TextSecondary,
                        utilityEspresso500 = NightwireLightColors.TextTertiary,
                        utilityEspresso200 = NightwireLightColors.BgHover,
                        utilityEspresso50 = NightwireLightColors.BgBase,
                        utilityEspresso100 = NightwireLightColors.BgSurface,
                        utilityEspresso400 = NightwireLightColors.TextTertiary,
                        utilityEspresso300 = NightwireLightColors.BgHover,
                        utilityEspresso800 = NightwireLightColors.TextPrimary,
                        utilityEspresso900 = NightwireLightColors.TextPrimary,
                        utilityEspresso950 = NightwireLightColors.TextPrimary
                    )
            ),
        Transparent =
            Transparent(
                bgPrimary = TransparentColorPalette.Light
            ),
        NoTheme = NoTheme(welcomeText = NightwireLightColors.AccentPrimary)
    )
