package co.electriccoin.zcash.ui.design.theme.colors

/**
 * NIGHTWIRE theme colors — Cypherpunk Edition
 *
 * Deep dark backgrounds with cyan/magenta/green accents.
 * Maps the Nightwire color palette to the ZashiColorsInternal structure,
 * auto-propagating the theme to all Zashi-inherited screens.
 */
val ZypherpunkZashiColorsInternal =
    ZashiColorsInternal(
        Surfaces =
            Surfaces(
                bgPrimary = NightwireColors.BgBase,
                bgAdjust = NightwireColors.BgElevated,
                bgSecondary = NightwireColors.BgSurface,
                bgTertiary = NightwireColors.BgElevated,
                bgQuaternary = NightwireColors.BgHover,
                strokePrimary = NightwireColors.AccentPrimary,
                strokeSecondary = NightwireColors.BorderDefault,
                bgAlt = NightwireColors.TextPrimary,
                bgHide = NightwireColors.BgBase,
                brandBg = NightwireColors.AccentPrimary,
                brandFg = NightwireColors.TextOnAccent,
                divider = NightwireColors.BorderDefault
            ),
        Text =
            Text(
                textPrimary = NightwireColors.TextPrimary,
                textSecondary = NightwireColors.TextSecondary,
                textTertiary = NightwireColors.TextTertiary,
                textQuaternary = NightwireColors.TextTertiary,
                textSupport = NightwireColors.TextSecondary,
                textDisabled = NightwireColors.TextTertiary,
                textError = NightwireColors.ColorDanger,
                textLink = NightwireColors.AccentPrimary,
                textLight = NightwireColors.TextPrimary,
                textLightSupport = NightwireColors.TextSecondary
            ),
        Btns =
            Btns(
                Brand =
                    BtnBrand(
                        btnBrandBg = NightwireColors.AccentPrimary,
                        btnBrandBgHover = NightwireColors.AccentPrimaryDim,
                        btnBrandFg = NightwireColors.TextOnAccent,
                        btnBrandFgHover = NightwireColors.TextOnAccent,
                        btnBrandBgDisabled = NightwireColors.BgHover,
                        btnBrandFgDisabled = NightwireColors.TextTertiary
                    ),
                Secondary =
                    BtnSecondary(
                        btnSecondaryBg = NightwireColors.BgBase,
                        btnSecondaryBgHover = NightwireColors.BgSurface,
                        btnSecondaryFg = NightwireColors.AccentPrimary,
                        btnSecondaryFgHover = NightwireColors.AccentPrimary,
                        btnSecondaryBorder = NightwireColors.BorderActive,
                        btnSecondaryBorderHover = NightwireColors.AccentPrimary,
                        btnSecondaryBgDisabled = NightwireColors.BgElevated,
                        btnSecondaryFgDisabled = NightwireColors.TextTertiary
                    ),
                Tertiary =
                    BtnTertiary(
                        btnTertiaryBg = NightwireColors.BgElevated,
                        btnTertiaryBgHover = NightwireColors.BgInput,
                        btnTertiaryFg = NightwireColors.AccentPrimary,
                        btnTertiaryFgHover = NightwireColors.AccentPrimary,
                        btnTertiaryBgDisabled = NightwireColors.BgElevated,
                        btnTertiaryFgDisabled = NightwireColors.TextTertiary
                    ),
                Quaternary =
                    BtnQuaternary(
                        btnQuartBg = NightwireColors.BgHover,
                        btnQuartBgHover = NightwireColors.BgInput,
                        btnQuartFg = NightwireColors.TextPrimary,
                        btnQuartFgHover = NightwireColors.TextPrimary,
                        btnQuartBgDisabled = NightwireColors.BgElevated,
                        btnQuartFgDisabled = NightwireColors.TextTertiary
                    ),
                Destructive1 =
                    BtnDestructive1(
                        btnDestroy1Bg = NightwireColors.BgBase,
                        btnDestroy1BgHover = NightwireColors.BgSurface,
                        btnDestroy1Fg = NightwireColors.ColorDanger,
                        btnDestroy1FgHover = NightwireColors.ColorDanger,
                        btnDestroy1Border = NightwireColors.ColorDanger,
                        btnDestroy1BorderHover = NightwireColors.DestroyRed,
                        btnDestroy1BgDisabled = NightwireColors.BgElevated,
                        btnDestroy1FgDisabled = NightwireColors.TextTertiary
                    ),
                Destructive2 =
                    BtnDestructive2(
                        btnDestroy2Bg = NightwireColors.ColorDanger,
                        btnDestroy2BgHover = NightwireColors.DestroyRed,
                        btnDestroy2Fg = NightwireColors.TextPrimary,
                        btnDestroy2BgDisabled = NightwireColors.BgElevated,
                        btnDestroy2FgDisabled = NightwireColors.TextTertiary
                    ),
                Primary =
                    BtnPrimary(
                        btnPrimaryBg = NightwireColors.AccentPrimary,
                        btnPrimaryBgHover = NightwireColors.AccentPrimaryDim,
                        btnPrimaryFg = NightwireColors.TextOnAccent,
                        btnPrimaryBgDisabled = NightwireColors.BgHover,
                        btnBoldFgDisabled = NightwireColors.TextTertiary
                    ),
                Ghost =
                    BtnGhost(
                        btnGhostBg = NightwireColors.BgBase,
                        btnGhostBgHover = NightwireColors.BgSurface,
                        btnGhostFg = NightwireColors.AccentPrimary,
                        btnGhostBgDisabled = NightwireColors.BgElevated,
                        btnGhostFgDisabled = NightwireColors.TextTertiary
                    )
            ),
        Avatars =
            Avatars(
                avatarProfileBorder = NightwireColors.AccentPrimary,
                avatarBg = NightwireColors.BgHover,
                avatarBgSecondary = NightwireColors.BgElevated,
                avatarStatus = NightwireColors.AccentSuccess,
                avatarTextFg = NightwireColors.TextPrimary,
                avatarBadgeBg = NightwireColors.AccentSecondary,
                avatarBadgeFg = NightwireColors.TextOnAccent
            ),
        Sliders =
            Sliders(
                sliderHandleBorder = NightwireColors.AccentPrimary,
                sliderHandleBg = NightwireColors.BgBase
            ),
        Inputs =
            Inputs(
                Default =
                    InputDefault(
                        bg = NightwireColors.BgInput,
                        bgAlt = NightwireColors.BgElevated,
                        label = NightwireColors.TextPrimary,
                        text = NightwireColors.TextSecondary,
                        hint = NightwireColors.TextTertiary,
                        required = NightwireColors.AccentSecondary,
                        icon = NightwireColors.AccentPrimaryDim,
                        stroke = NightwireColors.BorderDefault
                    ),
                Hover =
                    InputHover(
                        bg = NightwireColors.BgHover,
                        bgAlt = NightwireColors.BgInput,
                        asideBg = NightwireColors.BgElevated,
                        stroke = NightwireColors.BorderActive,
                        label = NightwireColors.TextPrimary,
                        text = NightwireColors.TextSecondary,
                        hint = NightwireColors.TextTertiary,
                        icon = NightwireColors.AccentPrimary,
                        required = NightwireColors.AccentSecondary
                    ),
                Filled =
                    InputFilled(
                        bg = NightwireColors.BgInput,
                        bgAlt = NightwireColors.BgElevated,
                        asideBg = NightwireColors.BgElevated,
                        stroke = NightwireColors.BorderActive,
                        label = NightwireColors.TextPrimary,
                        text = NightwireColors.TextPrimary,
                        hint = NightwireColors.TextTertiary,
                        icon = NightwireColors.AccentPrimary,
                        iconMain = NightwireColors.AccentPrimary,
                        required = NightwireColors.AccentSecondary
                    ),
                Focused =
                    InputFocused(
                        bg = NightwireColors.BgInput,
                        asideBg = NightwireColors.BgElevated,
                        stroke = NightwireColors.AccentPrimary,
                        stroke2 = NightwireColors.BorderActive,
                        label = NightwireColors.TextPrimary,
                        text = NightwireColors.TextPrimary,
                        hint = NightwireColors.TextTertiary,
                        icon = NightwireColors.AccentPrimary,
                        iconMain = NightwireColors.AccentPrimary,
                        defaultRequired = NightwireColors.AccentSecondary
                    ),
                Disabled =
                    InputDisabled(
                        bg = NightwireColors.BgElevated,
                        stroke = NightwireColors.BorderDefault,
                        label = NightwireColors.TextTertiary,
                        text = NightwireColors.TextTertiary,
                        hint = NightwireColors.TextTertiary,
                        icon = NightwireColors.TextTertiary,
                        iconMain = NightwireColors.TextTertiary,
                        required = NightwireColors.TextTertiary
                    ),
                ErrorDefault =
                    InputErrorDefault(
                        bg = NightwireColors.BgInput,
                        bgAlt = NightwireColors.BgElevated,
                        label = NightwireColors.TextPrimary,
                        text = NightwireColors.TextSecondary,
                        textAside = NightwireColors.TextTertiary,
                        textMain = NightwireColors.TextPrimary,
                        hint = NightwireColors.ColorDanger,
                        icon = NightwireColors.ColorDanger,
                        iconMain = NightwireColors.ColorDanger,
                        stroke = NightwireColors.ColorDanger,
                        strokeAlt = NightwireColors.BorderDefault,
                        dropdown = NightwireColors.TextTertiary
                    ),
                ErrorHover =
                    InputErrorHover(
                        bg = NightwireColors.BgInput,
                        bgAlt = NightwireColors.BgElevated,
                        label = NightwireColors.TextPrimary,
                        text = NightwireColors.TextSecondary,
                        textAside = NightwireColors.TextTertiary,
                        textMain = NightwireColors.TextPrimary,
                        hint = NightwireColors.ColorDanger,
                        icon = NightwireColors.ColorDanger,
                        iconMain = NightwireColors.ColorDanger,
                        stroke = NightwireColors.ColorDanger,
                        strokeAlt = NightwireColors.BorderDefault,
                        dropdown = NightwireColors.TextTertiary
                    ),
                ErrorFilled =
                    InputErrorFilled(
                        bg = NightwireColors.BgInput,
                        bgAlt = NightwireColors.BgElevated,
                        label = NightwireColors.TextPrimary,
                        text = NightwireColors.TextPrimary,
                        textAside = NightwireColors.TextTertiary,
                        hint = NightwireColors.ColorDanger,
                        icon = NightwireColors.ColorDanger,
                        iconMain = NightwireColors.ColorDanger,
                        stroke = NightwireColors.ColorDanger,
                        strokeAlt = NightwireColors.BorderDefault,
                        dropdown = NightwireColors.TextTertiary
                    ),
                ErrorFocused =
                    InputErrorFocused(
                        bg = NightwireColors.BgInput,
                        bgAlt = NightwireColors.BgElevated,
                        label = NightwireColors.TextPrimary,
                        text = NightwireColors.TextPrimary,
                        textAside = NightwireColors.TextTertiary,
                        hint = NightwireColors.ColorDanger,
                        icon = NightwireColors.ColorDanger,
                        iconMain = NightwireColors.ColorDanger,
                        stroke = NightwireColors.ColorDanger,
                        strokeAlt = NightwireColors.BorderDefault,
                        dropdown = NightwireColors.TextTertiary
                    )
            ),
        Accordion =
            Accordion(
                xBtnDefaultFg = NightwireColors.AccentPrimary,
                xBtnHoverBg = NightwireColors.BgInput,
                xBtnOnHoverBg = NightwireColors.BgInput,
                xBtnHoverFg = NightwireColors.AccentPrimary,
                xBtnFocusBg = NightwireColors.BgHover,
                xBtnFocusFg = NightwireColors.AccentPrimary,
                xBtnFocusStroke = NightwireColors.AccentPrimary,
                xBtnDisabledBg = NightwireColors.BgElevated,
                xBtnDisabledFg = NightwireColors.TextTertiary,
                defaultBg = NightwireColors.BgBase,
                defaultStroke = NightwireColors.BorderDefault,
                defaultIcon = NightwireColors.AccentPrimaryDim,
                focusStroke = NightwireColors.AccentPrimary,
                expandedBg = NightwireColors.BgSurface,
                expandedHoverBg = NightwireColors.BgElevated,
                expandedStroke = NightwireColors.BorderActive,
                dividers = NightwireColors.BorderDefault,
                expandedFocusStroke = NightwireColors.AccentPrimary
            ),
        Switcher =
            Switcher(
                defaultText = NightwireColors.TextSecondary,
                defaultTagBg = NightwireColors.BgHover,
                defaultIcon = NightwireColors.AccentPrimaryDim,
                hoverBg = NightwireColors.BgInput,
                hoverTagBg = NightwireColors.BgElevated,
                hoverIcon = NightwireColors.AccentPrimary,
                hoverText = NightwireColors.TextPrimary,
                hoverTagText = NightwireColors.TextPrimary,
                selectedBg = NightwireColors.AccentPrimary,
                selectedIcon = NightwireColors.TextOnAccent,
                selectedText = NightwireColors.TextOnAccent,
                selectedTagBg = NightwireColors.AccentPrimaryDim,
                selectedStroke = NightwireColors.AccentPrimary,
                disabledText = NightwireColors.TextTertiary,
                disabledIcon = NightwireColors.TextTertiary,
                disabledTagBg = NightwireColors.BgElevated,
                surfacePrimary = NightwireColors.BgSurface
            ),
        Toggles =
            Toggles(
                tgDefaultBg = NightwireColors.BgHover,
                tgDefaultFg = NightwireColors.TextTertiary,
                tgActiveBg = NightwireColors.AccentPrimary,
                tgActiveFg = NightwireColors.TextOnAccent,
                tgDefaultHoverBg = NightwireColors.BgInput,
                tgDefaultHoverFg = NightwireColors.TextSecondary,
                tgActiveHoverBg = NightwireColors.AccentPrimaryDim,
                tgActiveHoverFg = NightwireColors.TextOnAccent,
                tgDefaultDisabledBg = NightwireColors.BgElevated,
                tgDefaultDisabledFg = NightwireColors.TextTertiary,
                tgActiveDisabledBg = NightwireColors.BgElevated,
                tgActiveDisabledFg = NightwireColors.TextTertiary
            ),
        Tags =
            Tags(
                tcDefaultFg = NightwireColors.AccentPrimary,
                tcHoverBg = NightwireColors.BgInput,
                tcHoverFg = NightwireColors.AccentPrimary,
                tcCountBg = NightwireColors.AccentSecondary,
                tcCountFg = NightwireColors.TextOnAccent,
                statusIndicator = NightwireColors.AccentSuccess,
                surfacePrimary = NightwireColors.BgBase,
                surfaceStroke = NightwireColors.BorderActive
            ),
        Dropdowns =
            Dropdowns(
                Default =
                    DropdownDefault(
                        bg = NightwireColors.BgInput,
                        label = NightwireColors.TextPrimary,
                        text = NightwireColors.TextSecondary,
                        hint = NightwireColors.TextTertiary,
                        required = NightwireColors.AccentSecondary,
                        icon = NightwireColors.AccentPrimaryDim,
                        dropdown = NightwireColors.AccentPrimary,
                        active = NightwireColors.AccentPrimary
                    ),
                Filled =
                    DropdownFilled(
                        bg = NightwireColors.BgInput,
                        label = NightwireColors.TextPrimary,
                        textMain = NightwireColors.TextPrimary,
                        textSupport = NightwireColors.TextSecondary,
                        hint = NightwireColors.TextTertiary,
                        required = NightwireColors.AccentSecondary,
                        icon = NightwireColors.AccentPrimary,
                        dropdown = NightwireColors.AccentPrimary,
                        active = NightwireColors.AccentPrimary
                    ),
                Focused =
                    DropdownFocused(
                        bg = NightwireColors.BgInput,
                        stroke = NightwireColors.AccentPrimary,
                        label = NightwireColors.TextPrimary,
                        textMain = NightwireColors.TextPrimary,
                        textSupport = NightwireColors.TextSecondary,
                        hint = NightwireColors.TextTertiary,
                        defaultRequired = NightwireColors.AccentSecondary,
                        icon = NightwireColors.AccentPrimary,
                        dropdown = NightwireColors.AccentPrimary,
                        active = NightwireColors.AccentPrimary
                    ),
                Disabled =
                    DropdownDisabled(
                        bg = NightwireColors.BgElevated,
                        stroke = NightwireColors.BorderDefault,
                        label = NightwireColors.TextTertiary,
                        textMain = NightwireColors.TextTertiary,
                        textSupport = NightwireColors.TextTertiary,
                        hint = NightwireColors.TextTertiary,
                        required = NightwireColors.TextTertiary,
                        icon = NightwireColors.TextTertiary,
                        dropdown = NightwireColors.TextTertiary,
                        active = NightwireColors.TextTertiary
                    ),
                Parts =
                    DropdownParts(
                        scrollBar = NightwireColors.AccentPrimaryDim,
                        divider = NightwireColors.BorderDefault,
                        lhText = NightwireColors.TextSecondary,
                        lhBorder = NightwireColors.BorderDefault,
                        liTextPrimary = NightwireColors.TextPrimary,
                        liTextSecondary = NightwireColors.TextSecondary,
                        liTextTertiary = NightwireColors.TextTertiary,
                        liFgDisabled = NightwireColors.TextTertiary,
                        liIconDisabled = NightwireColors.TextTertiary,
                        liBgHover = NightwireColors.BgInput,
                        statusActive = NightwireColors.AccentPrimary,
                        statusMain = NightwireColors.AccentPrimary,
                        statusDisabled = NightwireColors.TextTertiary,
                        bgDisabled = NightwireColors.BgElevated
                    )
            ),
        Tabs =
            Tabs(
                defaultText = NightwireColors.TextSecondary,
                defaultIcon = NightwireColors.AccentPrimaryDim,
                defaultTagBg = NightwireColors.BgElevated,
                hoverText = NightwireColors.TextSecondary,
                hoverTagText = NightwireColors.TextSecondary,
                hoverIcon = NightwireColors.AccentPrimary,
                hoverTagBg = NightwireColors.BgInput,
                hoverBorder = NightwireColors.AccentPrimaryDim,
                selectedText = NightwireColors.AccentPrimary,
                selectedIcon = NightwireColors.AccentPrimary,
                selectedTagBg = NightwireColors.AccentPrimaryDim,
                selectedBorder = NightwireColors.AccentPrimary,
                disabledText = NightwireColors.TextTertiary,
                disabledIcon = NightwireColors.TextTertiary,
                disabledTagBg = NightwireColors.BgElevated,
                disabledTagText = NightwireColors.TextTertiary
            ),
        Checkboxes =
            Checkboxes(
                boxOffBg = NightwireColors.BgInput,
                boxOffStroke = NightwireColors.BorderActive,
                boxOffHoverBg = NightwireColors.BgHover,
                boxOffHoverStroke = NightwireColors.AccentPrimary,
                boxOffDisabledBg = NightwireColors.BgElevated,
                boxOffDisabledStroke = NightwireColors.BorderDefault,
                boxOnBg = NightwireColors.AccentPrimary,
                boxOnFg = NightwireColors.TextOnAccent,
                boxOnHoverBg = NightwireColors.AccentPrimaryDim,
                boxOnDisabledBg = NightwireColors.BgElevated,
                boxOnDisabledStroke = NightwireColors.BorderDefault,
                boxOnDisabledFg = NightwireColors.TextTertiary
            ),
        Loading =
            Loading(
                loadingBgPrimary = NightwireColors.BgBase,
                loadingBgSecondary = NightwireColors.BgInput,
                loadingFgPrimary = NightwireColors.AccentPrimary
            ),
        Modals =
            Modals(
                defaultBg = NightwireColors.BgElevated,
                defaultFg = NightwireColors.AccentPrimary,
                hoverBg = NightwireColors.BgInput,
                hoverFg = NightwireColors.AccentPrimary,
                focusedBg = NightwireColors.BgHover,
                focusedStroke = NightwireColors.AccentPrimary,
                disabledBg = NightwireColors.BgElevated,
                disabledFg = NightwireColors.TextTertiary,
                surfacePrimary = NightwireColors.BgElevated,
                surfaceStroke = NightwireColors.BorderDefault
            ),
        HintTooltips =
            HintTooltips(
                surfacePrimary = NightwireColors.BgInput,
                defaultBg = NightwireColors.BgInput,
                defaultFg = NightwireColors.TextPrimary,
                hoverBg = NightwireColors.BgHover,
                hoverFg = NightwireColors.TextPrimary,
                focusedBg = NightwireColors.BgHover,
                focusedStroke = NightwireColors.AccentPrimary,
                disabledBg = NightwireColors.BgElevated,
                disabledFg = NightwireColors.TextTertiary
            ),
        TwoFA =
            TwoFA(
                defaultBg = NightwireColors.BgInput,
                defaultStroke = NightwireColors.BorderDefault,
                defaultText = NightwireColors.TextTertiary,
                focusedBg = NightwireColors.BgInput,
                focusedStroke = NightwireColors.AccentPrimary,
                focusedText = NightwireColors.AccentPrimary,
                filledBg = NightwireColors.BgInput,
                filledStroke = NightwireColors.BorderActive,
                filledText = NightwireColors.TextPrimary,
                disabledBg = NightwireColors.BgElevated,
                disabledText = NightwireColors.TextTertiary,
                separatorDash = NightwireColors.AccentPrimaryDim
            ),
        Utility =
            Utility(
                Gray =
                    UtilityGray(
                        utilityGray700 = NightwireColors.TextPrimary,
                        utilityGray600 = NightwireColors.TextSecondary,
                        utilityGray500 = NightwireColors.TextTertiary,
                        utilityGray200 = NightwireColors.BgHover,
                        utilityGray50 = NightwireColors.BgSurface,
                        utilityGray100 = NightwireColors.BgElevated,
                        utilityGray400 = NightwireColors.TextTertiary,
                        utilityGray300 = NightwireColors.BgHover,
                        utilityGray900 = NightwireColors.TextPrimary,
                        utilityGray800 = NightwireColors.TextSecondary
                    ),
                SuccessGreen =
                    UtilitySuccessGreen(
                        utilitySuccess600 = NightwireColors.AccentSuccess,
                        utilitySuccess700 = NightwireColors.AccentSuccess,
                        utilitySuccess500 = NightwireColors.AccentSuccessDim,
                        utilitySuccess200 = NightwireColors.BubblePayment,
                        utilitySuccess800 = NightwireColors.AccentSuccess,
                        utilitySuccess50 = NightwireColors.BgBase,
                        utilitySuccess100 = NightwireColors.BubblePayment,
                        utilitySuccess400 = NightwireColors.AccentSuccessDim,
                        utilitySuccess300 = NightwireColors.AccentSuccessDim
                    ),
                ErrorRed =
                    UtilityErrorRed(
                        utilityError600 = NightwireColors.ColorDanger,
                        utilityError700 = NightwireColors.DestroyRed,
                        utilityError500 = NightwireColors.ColorDanger,
                        utilityError200 = NightwireColors.BgElevated,
                        utilityError800 = NightwireColors.DestroyRed,
                        utilityError50 = NightwireColors.BgBase,
                        utilityError100 = NightwireColors.BgSurface,
                        utilityError400 = NightwireColors.ColorDanger,
                        utilityError300 = NightwireColors.ColorDanger
                    ),
                WarningYellow =
                    UtilityWarningYellow(
                        utilityOrange600 = NightwireColors.ColorWarning,
                        utilityOrange700 = NightwireColors.ColorWarning,
                        utilityOrange500 = NightwireColors.ColorWarning,
                        utilityOrange200 = NightwireColors.BgElevated,
                        utilityOrange800 = NightwireColors.ColorWarning,
                        utilityOrange50 = NightwireColors.BgBase,
                        utilityOrange100 = NightwireColors.BgSurface,
                        utilityOrange400 = NightwireColors.ColorWarning,
                        utilityOrange300 = NightwireColors.ColorWarning
                    ),
                HyperBlue =
                    UtilityHyperBlue(
                        utilityBlueDark600 = NightwireColors.AccentPrimary,
                        utilityBlueDark700 = NightwireColors.AccentPrimary,
                        utilityBlueDark500 = NightwireColors.AccentPrimaryDim,
                        utilityBlueDark200 = NightwireColors.BgElevated,
                        utilityBlueDark800 = NightwireColors.AccentPrimary,
                        utilityBlueDark50 = NightwireColors.BgBase,
                        utilityBlueDark100 = NightwireColors.BgSurface,
                        utilityBlueDark400 = NightwireColors.AccentPrimaryDim,
                        utilityBlueDark300 = NightwireColors.AccentPrimaryDim
                    ),
                Indigo =
                    UtilityIndigo(
                        utilityIndigo600 = NightwireColors.AccentSecondary,
                        utilityIndigo700 = NightwireColors.AccentSecondary,
                        utilityIndigo500 = NightwireColors.AccentSecondaryDim,
                        utilityIndigo200 = NightwireColors.BgElevated,
                        utilityIndigo800 = NightwireColors.AccentSecondary,
                        utilityIndigo50 = NightwireColors.BgBase,
                        utilityIndigo100 = NightwireColors.BgSurface,
                        utilityIndigo400 = NightwireColors.AccentSecondaryDim,
                        utilityIndigo300 = NightwireColors.AccentSecondaryDim
                    ),
                Purple =
                    UtilityPurple(
                        utilityPurple600 = NightwireColors.AccentSecondary,
                        utilityPurple700 = NightwireColors.AccentSecondary,
                        utilityPurple500 = NightwireColors.AccentSecondaryDim,
                        utilityPurple200 = NightwireColors.BgElevated,
                        utilityPurple800 = NightwireColors.AccentSecondary,
                        utilityPurple50 = NightwireColors.BgBase,
                        utilityPurple100 = NightwireColors.BgSurface,
                        utilityPurple400 = NightwireColors.AccentSecondaryDim,
                        utilityPurple300 = NightwireColors.AccentSecondaryDim,
                        utilityPurple900 = NightwireColors.AccentSecondary
                    ),
                Espresso =
                    UtilityEspresso(
                        utilityEspresso700 = NightwireColors.TextPrimary,
                        utilityEspresso600 = NightwireColors.TextSecondary,
                        utilityEspresso500 = NightwireColors.TextTertiary,
                        utilityEspresso200 = NightwireColors.BgHover,
                        utilityEspresso50 = NightwireColors.BgBase,
                        utilityEspresso100 = NightwireColors.BgSurface,
                        utilityEspresso400 = NightwireColors.TextTertiary,
                        utilityEspresso300 = NightwireColors.BgHover,
                        utilityEspresso800 = NightwireColors.TextPrimary,
                        utilityEspresso900 = NightwireColors.TextPrimary,
                        utilityEspresso950 = NightwireColors.TextPrimary
                    )
            ),
        Transparent =
            Transparent(
                bgPrimary = TransparentColorPalette.Dark
            ),
        NoTheme = NoTheme(welcomeText = NightwireColors.AccentPrimary)
    )
