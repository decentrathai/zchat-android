package co.electriccoin.zcash.ui.design.theme.colors

/**
 * ZYPHERPUNK theme colors - Full cyberpunk experience with:
 * - Ultra-dark near-black backgrounds
 * - Intense neon cyan/magenta accents
 * - Circuit board aesthetics
 * - Transmission/matrix-style headers
 * - Enhanced glow effects
 */
val ZypherpunkZashiColorsInternal =
    ZashiColorsInternal(
        Surfaces =
            Surfaces(
                bgPrimary = ZypherpunkBase.Background,
                bgAdjust = ZypherpunkShades.`06dp`,
                bgSecondary = ZypherpunkShades.`06dp`,
                bgTertiary = ZypherpunkShades.`08dp`,
                bgQuaternary = ZypherpunkPurple.`600`,
                strokePrimary = ZypherpunkCyan.`400`,
                strokeSecondary = ZypherpunkPurple.`600`,
                bgAlt = ZypherpunkBase.Text,
                bgHide = ZypherpunkBase.Background,
                brandBg = ZypherpunkBase.Cyan,
                brandFg = ZypherpunkBase.Background,
                divider = ZypherpunkPurple.`700`
            ),
        Text =
            Text(
                textPrimary = ZypherpunkBase.Text,
                textSecondary = ZypherpunkBase.TextSecondary,
                textTertiary = ZypherpunkBase.TextSecondary,
                textQuaternary = ZypherpunkPurple.`300`,
                textSupport = ZypherpunkPurple.`400`,
                textDisabled = ZypherpunkPurple.`600`,
                textError = ZypherpunkMagenta.`300`,
                textLink = ZypherpunkCyan.`400`,
                textLight = ZypherpunkBase.Text,
                textLightSupport = ZypherpunkBase.TextSecondary
            ),
        Btns =
            Btns(
                Brand =
                    BtnBrand(
                        btnBrandBg = ZypherpunkCyan.`400`,
                        btnBrandBgHover = ZypherpunkBase.CyanGlow,
                        btnBrandFg = ZypherpunkBase.Background,
                        btnBrandFgHover = ZypherpunkBase.Background,
                        btnBrandBgDisabled = ZypherpunkPurple.`700`,
                        btnBrandFgDisabled = ZypherpunkPurple.`500`
                    ),
                Secondary =
                    BtnSecondary(
                        btnSecondaryBg = ZypherpunkBase.Background,
                        btnSecondaryBgHover = ZypherpunkShades.`04dp`,
                        btnSecondaryFg = ZypherpunkCyan.`400`,
                        btnSecondaryFgHover = ZypherpunkBase.CyanGlow,
                        btnSecondaryBorder = ZypherpunkCyan.`600`,
                        btnSecondaryBorderHover = ZypherpunkCyan.`400`,
                        btnSecondaryBgDisabled = ZypherpunkPurple.`800`,
                        btnSecondaryFgDisabled = ZypherpunkPurple.`500`
                    ),
                Tertiary =
                    BtnTertiary(
                        btnTertiaryBg = ZypherpunkShades.`06dp`,
                        btnTertiaryBgHover = ZypherpunkShades.`08dp`,
                        btnTertiaryFg = ZypherpunkCyan.`300`,
                        btnTertiaryFgHover = ZypherpunkBase.CyanGlow,
                        btnTertiaryBgDisabled = ZypherpunkPurple.`800`,
                        btnTertiaryFgDisabled = ZypherpunkPurple.`500`
                    ),
                Quaternary =
                    BtnQuaternary(
                        btnQuartBg = ZypherpunkPurple.`600`,
                        btnQuartBgHover = ZypherpunkPurple.`500`,
                        btnQuartFg = ZypherpunkBase.Text,
                        btnQuartFgHover = ZypherpunkBase.Text,
                        btnQuartBgDisabled = ZypherpunkPurple.`800`,
                        btnQuartFgDisabled = ZypherpunkPurple.`500`
                    ),
                Destructive1 =
                    BtnDestructive1(
                        btnDestroy1Bg = ZypherpunkMagenta.`950`,
                        btnDestroy1BgHover = ZypherpunkMagenta.`900`,
                        btnDestroy1Fg = ZypherpunkMagenta.`100`,
                        btnDestroy1FgHover = ZypherpunkMagenta.`50`,
                        btnDestroy1Border = ZypherpunkMagenta.`600`,
                        btnDestroy1BorderHover = ZypherpunkMagenta.`500`,
                        btnDestroy1BgDisabled = ZypherpunkPurple.`800`,
                        btnDestroy1FgDisabled = ZypherpunkPurple.`500`
                    ),
                Destructive2 =
                    BtnDestructive2(
                        btnDestroy2Bg = ZypherpunkMagenta.`500`,
                        btnDestroy2BgHover = ZypherpunkMagenta.`600`,
                        btnDestroy2Fg = ZypherpunkMagenta.`50`,
                        btnDestroy2BgDisabled = ZypherpunkPurple.`800`,
                        btnDestroy2FgDisabled = ZypherpunkPurple.`500`
                    ),
                Primary =
                    BtnPrimary(
                        btnPrimaryBg = ZypherpunkCyan.`400`,
                        btnPrimaryBgHover = ZypherpunkBase.CyanGlow,
                        btnPrimaryFg = ZypherpunkBase.Background,
                        btnPrimaryBgDisabled = ZypherpunkPurple.`800`,
                        btnBoldFgDisabled = ZypherpunkPurple.`500`
                    ),
                Ghost =
                    BtnGhost(
                        btnGhostBg = ZypherpunkBase.Background,
                        btnGhostBgHover = ZypherpunkShades.`04dp`,
                        btnGhostFg = ZypherpunkCyan.`400`,
                        btnGhostBgDisabled = ZypherpunkPurple.`800`,
                        btnGhostFgDisabled = ZypherpunkPurple.`500`
                    )
            ),
        Avatars =
            Avatars(
                avatarProfileBorder = ZypherpunkCyan.`400`,
                avatarBg = ZypherpunkPurple.`600`,
                avatarBgSecondary = ZypherpunkPurple.`500`,
                avatarStatus = ZypherpunkAccent.TransmissionGreen,
                avatarTextFg = ZypherpunkBase.Text,
                avatarBadgeBg = ZypherpunkMagenta.`400`,
                avatarBadgeFg = ZypherpunkBase.Background
            ),
        Sliders =
            Sliders(
                sliderHandleBorder = ZypherpunkCyan.`400`,
                sliderHandleBg = ZypherpunkBase.Background
            ),
        Inputs =
            Inputs(
                Default =
                    InputDefault(
                        bg = ZypherpunkShades.`06dp`,
                        bgAlt = ZypherpunkShades.`04dp`,
                        label = ZypherpunkBase.Text,
                        text = ZypherpunkPurple.`300`,
                        hint = ZypherpunkPurple.`400`,
                        required = ZypherpunkMagenta.`400`,
                        icon = ZypherpunkCyan.`500`,
                        stroke = ZypherpunkPurple.`600`
                    ),
                Hover =
                    InputHover(
                        bg = ZypherpunkShades.`08dp`,
                        bgAlt = ZypherpunkShades.`06dp`,
                        asideBg = ZypherpunkShades.`06dp`,
                        stroke = ZypherpunkCyan.`600`,
                        label = ZypherpunkBase.Text,
                        text = ZypherpunkBase.TextSecondary,
                        hint = ZypherpunkPurple.`400`,
                        icon = ZypherpunkCyan.`400`,
                        required = ZypherpunkMagenta.`400`
                    ),
                Filled =
                    InputFilled(
                        bg = ZypherpunkShades.`06dp`,
                        bgAlt = ZypherpunkShades.`04dp`,
                        asideBg = ZypherpunkShades.`06dp`,
                        stroke = ZypherpunkCyan.`500`,
                        label = ZypherpunkBase.Text,
                        text = ZypherpunkBase.Text,
                        hint = ZypherpunkPurple.`400`,
                        icon = ZypherpunkCyan.`400`,
                        iconMain = ZypherpunkCyan.`400`,
                        required = ZypherpunkMagenta.`400`
                    ),
                Focused =
                    InputFocused(
                        bg = ZypherpunkShades.`04dp`,
                        asideBg = ZypherpunkShades.`06dp`,
                        stroke = ZypherpunkCyan.`400`,
                        stroke2 = ZypherpunkPurple.`600`,
                        label = ZypherpunkBase.Text,
                        text = ZypherpunkBase.Text,
                        hint = ZypherpunkPurple.`400`,
                        icon = ZypherpunkCyan.`400`,
                        iconMain = ZypherpunkBase.CyanGlow,
                        defaultRequired = ZypherpunkMagenta.`400`
                    ),
                Disabled =
                    InputDisabled(
                        bg = ZypherpunkShades.`06dp`,
                        stroke = ZypherpunkPurple.`700`,
                        label = ZypherpunkPurple.`400`,
                        text = ZypherpunkPurple.`500`,
                        hint = ZypherpunkPurple.`600`,
                        icon = ZypherpunkPurple.`600`,
                        iconMain = ZypherpunkPurple.`600`,
                        required = ZypherpunkMagenta.`700`
                    ),
                ErrorDefault =
                    InputErrorDefault(
                        bg = ZypherpunkShades.`04dp`,
                        bgAlt = ZypherpunkShades.`06dp`,
                        label = ZypherpunkBase.Text,
                        text = ZypherpunkPurple.`300`,
                        textAside = ZypherpunkPurple.`400`,
                        textMain = ZypherpunkBase.Text,
                        hint = ZypherpunkMagenta.`400`,
                        icon = ZypherpunkMagenta.`400`,
                        iconMain = ZypherpunkMagenta.`500`,
                        stroke = ZypherpunkMagenta.`400`,
                        strokeAlt = ZypherpunkPurple.`600`,
                        dropdown = ZypherpunkPurple.`500`
                    ),
                ErrorHover =
                    InputErrorHover(
                        bg = ZypherpunkShades.`04dp`,
                        bgAlt = ZypherpunkShades.`06dp`,
                        label = ZypherpunkBase.Text,
                        text = ZypherpunkBase.TextSecondary,
                        textAside = ZypherpunkPurple.`400`,
                        textMain = ZypherpunkBase.Text,
                        hint = ZypherpunkMagenta.`400`,
                        icon = ZypherpunkMagenta.`400`,
                        iconMain = ZypherpunkMagenta.`500`,
                        stroke = ZypherpunkMagenta.`500`,
                        strokeAlt = ZypherpunkPurple.`600`,
                        dropdown = ZypherpunkPurple.`500`
                    ),
                ErrorFilled =
                    InputErrorFilled(
                        bg = ZypherpunkShades.`04dp`,
                        bgAlt = ZypherpunkShades.`06dp`,
                        label = ZypherpunkBase.Text,
                        text = ZypherpunkBase.Text,
                        textAside = ZypherpunkPurple.`400`,
                        hint = ZypherpunkMagenta.`400`,
                        icon = ZypherpunkMagenta.`400`,
                        iconMain = ZypherpunkMagenta.`500`,
                        stroke = ZypherpunkMagenta.`500`,
                        strokeAlt = ZypherpunkPurple.`600`,
                        dropdown = ZypherpunkPurple.`500`
                    ),
                ErrorFocused =
                    InputErrorFocused(
                        bg = ZypherpunkShades.`04dp`,
                        bgAlt = ZypherpunkShades.`06dp`,
                        label = ZypherpunkBase.Text,
                        text = ZypherpunkBase.Text,
                        textAside = ZypherpunkPurple.`400`,
                        hint = ZypherpunkMagenta.`400`,
                        icon = ZypherpunkMagenta.`400`,
                        iconMain = ZypherpunkMagenta.`500`,
                        stroke = ZypherpunkMagenta.`400`,
                        strokeAlt = ZypherpunkPurple.`600`,
                        dropdown = ZypherpunkPurple.`500`
                    )
            ),
        Accordion =
            Accordion(
                xBtnDefaultFg = ZypherpunkCyan.`400`,
                xBtnHoverBg = ZypherpunkShades.`08dp`,
                xBtnOnHoverBg = ZypherpunkShades.`08dp`,
                xBtnHoverFg = ZypherpunkBase.CyanGlow,
                xBtnFocusBg = ZypherpunkShades.`12dp`,
                xBtnFocusFg = ZypherpunkBase.CyanGlow,
                xBtnFocusStroke = ZypherpunkCyan.`400`,
                xBtnDisabledBg = ZypherpunkPurple.`800`,
                xBtnDisabledFg = ZypherpunkPurple.`600`,
                defaultBg = ZypherpunkBase.Background,
                defaultStroke = ZypherpunkPurple.`700`,
                defaultIcon = ZypherpunkCyan.`500`,
                focusStroke = ZypherpunkCyan.`400`,
                expandedBg = ZypherpunkShades.`06dp`,
                expandedHoverBg = ZypherpunkShades.`08dp`,
                expandedStroke = ZypherpunkCyan.`600`,
                dividers = ZypherpunkPurple.`600`,
                expandedFocusStroke = ZypherpunkCyan.`400`
            ),
        Switcher =
            Switcher(
                defaultText = ZypherpunkBase.TextSecondary,
                defaultTagBg = ZypherpunkPurple.`600`,
                defaultIcon = ZypherpunkCyan.`500`,
                hoverBg = ZypherpunkShades.`08dp`,
                hoverTagBg = ZypherpunkPurple.`500`,
                hoverIcon = ZypherpunkCyan.`400`,
                hoverText = ZypherpunkBase.Text,
                hoverTagText = ZypherpunkBase.Text,
                selectedBg = ZypherpunkCyan.`400`,
                selectedIcon = ZypherpunkBase.Background,
                selectedText = ZypherpunkBase.Background,
                selectedTagBg = ZypherpunkBase.CyanGlow,
                selectedStroke = ZypherpunkCyan.`400`,
                disabledText = ZypherpunkPurple.`500`,
                disabledIcon = ZypherpunkPurple.`600`,
                disabledTagBg = ZypherpunkPurple.`700`,
                surfacePrimary = ZypherpunkShades.`06dp`
            ),
        Toggles =
            Toggles(
                tgDefaultBg = ZypherpunkPurple.`600`,
                tgDefaultFg = ZypherpunkPurple.`400`,
                tgActiveBg = ZypherpunkCyan.`400`,
                tgActiveFg = ZypherpunkBase.Background,
                tgDefaultHoverBg = ZypherpunkPurple.`500`,
                tgDefaultHoverFg = ZypherpunkPurple.`300`,
                tgActiveHoverBg = ZypherpunkBase.CyanGlow,
                tgActiveHoverFg = ZypherpunkBase.Background,
                tgDefaultDisabledBg = ZypherpunkPurple.`700`,
                tgDefaultDisabledFg = ZypherpunkPurple.`500`,
                tgActiveDisabledBg = ZypherpunkPurple.`700`,
                tgActiveDisabledFg = ZypherpunkPurple.`500`
            ),
        Tags =
            Tags(
                tcDefaultFg = ZypherpunkCyan.`400`,
                tcHoverBg = ZypherpunkShades.`08dp`,
                tcHoverFg = ZypherpunkBase.CyanGlow,
                tcCountBg = ZypherpunkMagenta.`600`,
                tcCountFg = ZypherpunkMagenta.`100`,
                statusIndicator = ZypherpunkAccent.TransmissionGreen,
                surfacePrimary = ZypherpunkBase.Background,
                surfaceStroke = ZypherpunkCyan.`600`
            ),
        Dropdowns =
            Dropdowns(
                Default =
                    DropdownDefault(
                        bg = ZypherpunkShades.`06dp`,
                        label = ZypherpunkBase.Text,
                        text = ZypherpunkPurple.`300`,
                        hint = ZypherpunkPurple.`400`,
                        required = ZypherpunkMagenta.`400`,
                        icon = ZypherpunkCyan.`500`,
                        dropdown = ZypherpunkCyan.`400`,
                        active = ZypherpunkCyan.`400`
                    ),
                Filled =
                    DropdownFilled(
                        bg = ZypherpunkShades.`06dp`,
                        label = ZypherpunkBase.Text,
                        textMain = ZypherpunkBase.Text,
                        textSupport = ZypherpunkBase.TextSecondary,
                        hint = ZypherpunkPurple.`400`,
                        required = ZypherpunkMagenta.`400`,
                        icon = ZypherpunkCyan.`400`,
                        dropdown = ZypherpunkCyan.`400`,
                        active = ZypherpunkCyan.`400`
                    ),
                Focused =
                    DropdownFocused(
                        bg = ZypherpunkShades.`04dp`,
                        stroke = ZypherpunkCyan.`400`,
                        label = ZypherpunkBase.Text,
                        textMain = ZypherpunkBase.Text,
                        textSupport = ZypherpunkBase.TextSecondary,
                        hint = ZypherpunkPurple.`400`,
                        defaultRequired = ZypherpunkMagenta.`400`,
                        icon = ZypherpunkCyan.`400`,
                        dropdown = ZypherpunkCyan.`400`,
                        active = ZypherpunkBase.CyanGlow
                    ),
                Disabled =
                    DropdownDisabled(
                        bg = ZypherpunkShades.`06dp`,
                        stroke = ZypherpunkPurple.`700`,
                        label = ZypherpunkPurple.`400`,
                        textMain = ZypherpunkPurple.`500`,
                        textSupport = ZypherpunkPurple.`600`,
                        hint = ZypherpunkPurple.`600`,
                        required = ZypherpunkMagenta.`700`,
                        icon = ZypherpunkPurple.`600`,
                        dropdown = ZypherpunkPurple.`600`,
                        active = ZypherpunkPurple.`500`
                    ),
                Parts =
                    DropdownParts(
                        scrollBar = ZypherpunkCyan.`600`,
                        divider = ZypherpunkPurple.`600`,
                        lhText = ZypherpunkBase.TextSecondary,
                        lhBorder = ZypherpunkPurple.`600`,
                        liTextPrimary = ZypherpunkBase.Text,
                        liTextSecondary = ZypherpunkBase.TextSecondary,
                        liTextTertiary = ZypherpunkPurple.`400`,
                        liFgDisabled = ZypherpunkPurple.`500`,
                        liIconDisabled = ZypherpunkPurple.`600`,
                        liBgHover = ZypherpunkShades.`08dp`,
                        statusActive = ZypherpunkCyan.`400`,
                        statusMain = ZypherpunkCyan.`400`,
                        statusDisabled = ZypherpunkPurple.`600`,
                        bgDisabled = ZypherpunkShades.`06dp`
                    )
            ),
        Tabs =
            Tabs(
                defaultText = ZypherpunkPurple.`300`,
                defaultIcon = ZypherpunkCyan.`500`,
                defaultTagBg = ZypherpunkPurple.`700`,
                hoverText = ZypherpunkBase.TextSecondary,
                hoverTagText = ZypherpunkBase.TextSecondary,
                hoverIcon = ZypherpunkCyan.`400`,
                hoverTagBg = ZypherpunkPurple.`500`,
                hoverBorder = ZypherpunkCyan.`500`,
                selectedText = ZypherpunkCyan.`400`,
                selectedIcon = ZypherpunkCyan.`400`,
                selectedTagBg = ZypherpunkCyan.`600`,
                selectedBorder = ZypherpunkCyan.`400`,
                disabledText = ZypherpunkPurple.`500`,
                disabledIcon = ZypherpunkPurple.`600`,
                disabledTagBg = ZypherpunkPurple.`800`,
                disabledTagText = ZypherpunkPurple.`500`
            ),
        Checkboxes =
            Checkboxes(
                boxOffBg = ZypherpunkShades.`06dp`,
                boxOffStroke = ZypherpunkCyan.`500`,
                boxOffHoverBg = ZypherpunkShades.`08dp`,
                boxOffHoverStroke = ZypherpunkCyan.`400`,
                boxOffDisabledBg = ZypherpunkPurple.`700`,
                boxOffDisabledStroke = ZypherpunkPurple.`600`,
                boxOnBg = ZypherpunkCyan.`400`,
                boxOnFg = ZypherpunkBase.Background,
                boxOnHoverBg = ZypherpunkBase.CyanGlow,
                boxOnDisabledBg = ZypherpunkPurple.`700`,
                boxOnDisabledStroke = ZypherpunkPurple.`600`,
                boxOnDisabledFg = ZypherpunkPurple.`500`
            ),
        Loading =
            Loading(
                loadingBgPrimary = ZypherpunkBase.Background,
                loadingBgSecondary = ZypherpunkShades.`08dp`,
                loadingFgPrimary = ZypherpunkCyan.`400`
            ),
        Modals =
            Modals(
                defaultBg = ZypherpunkBase.Background,
                defaultFg = ZypherpunkCyan.`400`,
                hoverBg = ZypherpunkShades.`08dp`,
                hoverFg = ZypherpunkBase.CyanGlow,
                focusedBg = ZypherpunkShades.`12dp`,
                focusedStroke = ZypherpunkCyan.`400`,
                disabledBg = ZypherpunkPurple.`800`,
                disabledFg = ZypherpunkPurple.`600`,
                surfacePrimary = ZypherpunkShades.`04dp`,
                surfaceStroke = ZypherpunkPurple.`600`
            ),
        HintTooltips =
            HintTooltips(
                surfacePrimary = ZypherpunkShades.`08dp`,
                defaultBg = ZypherpunkShades.`08dp`,
                defaultFg = ZypherpunkBase.Text,
                hoverBg = ZypherpunkShades.`12dp`,
                hoverFg = ZypherpunkBase.Text,
                focusedBg = ZypherpunkShades.`12dp`,
                focusedStroke = ZypherpunkCyan.`400`,
                disabledBg = ZypherpunkPurple.`700`,
                disabledFg = ZypherpunkPurple.`500`
            ),
        TwoFA =
            TwoFA(
                defaultBg = ZypherpunkShades.`08dp`,
                defaultStroke = ZypherpunkPurple.`600`,
                defaultText = ZypherpunkPurple.`600`,
                focusedBg = ZypherpunkShades.`06dp`,
                focusedStroke = ZypherpunkCyan.`400`,
                focusedText = ZypherpunkCyan.`400`,
                filledBg = ZypherpunkShades.`06dp`,
                filledStroke = ZypherpunkCyan.`500`,
                filledText = ZypherpunkBase.Text,
                disabledBg = ZypherpunkPurple.`800`,
                disabledText = ZypherpunkPurple.`600`,
                separatorDash = ZypherpunkCyan.`600`
            ),
        Utility =
            Utility(
                Gray =
                    UtilityGray(
                        utilityGray700 = ZypherpunkPurple.`200`,
                        utilityGray600 = ZypherpunkPurple.`300`,
                        utilityGray500 = ZypherpunkPurple.`400`,
                        utilityGray200 = ZypherpunkPurple.`700`,
                        utilityGray50 = ZypherpunkPurple.`900`,
                        utilityGray100 = ZypherpunkPurple.`800`,
                        utilityGray400 = ZypherpunkPurple.`500`,
                        utilityGray300 = ZypherpunkPurple.`600`,
                        utilityGray900 = ZypherpunkBase.Text,
                        utilityGray800 = ZypherpunkBase.TextSecondary
                    ),
                SuccessGreen =
                    UtilitySuccessGreen(
                        utilitySuccess600 = ZypherpunkAccent.TransmissionGreen,
                        utilitySuccess700 = ZypherpunkCyan.`300`,
                        utilitySuccess500 = ZypherpunkCyan.`500`,
                        utilitySuccess200 = ZypherpunkCyan.`800`,
                        utilitySuccess800 = ZypherpunkCyan.`200`,
                        utilitySuccess50 = ZypherpunkCyan.`950`,
                        utilitySuccess100 = ZypherpunkCyan.`900`,
                        utilitySuccess400 = ZypherpunkCyan.`600`,
                        utilitySuccess300 = ZypherpunkCyan.`700`
                    ),
                ErrorRed =
                    UtilityErrorRed(
                        utilityError600 = ZypherpunkMagenta.`400`,
                        utilityError700 = ZypherpunkMagenta.`300`,
                        utilityError500 = ZypherpunkMagenta.`500`,
                        utilityError200 = ZypherpunkMagenta.`800`,
                        utilityError800 = ZypherpunkMagenta.`200`,
                        utilityError50 = ZypherpunkMagenta.`950`,
                        utilityError100 = ZypherpunkMagenta.`900`,
                        utilityError400 = ZypherpunkMagenta.`600`,
                        utilityError300 = ZypherpunkMagenta.`700`
                    ),
                WarningYellow =
                    UtilityWarningYellow(
                        utilityOrange600 = ZypherpunkAccent.NeonYellow,
                        utilityOrange700 = ZypherpunkMagenta.`300`,
                        utilityOrange500 = ZypherpunkMagenta.`500`,
                        utilityOrange200 = ZypherpunkMagenta.`800`,
                        utilityOrange800 = ZypherpunkMagenta.`200`,
                        utilityOrange50 = ZypherpunkMagenta.`950`,
                        utilityOrange100 = ZypherpunkMagenta.`900`,
                        utilityOrange400 = ZypherpunkMagenta.`600`,
                        utilityOrange300 = ZypherpunkMagenta.`700`
                    ),
                HyperBlue =
                    UtilityHyperBlue(
                        utilityBlueDark600 = ZypherpunkCyan.`400`,
                        utilityBlueDark700 = ZypherpunkBase.CyanGlow,
                        utilityBlueDark500 = ZypherpunkCyan.`500`,
                        utilityBlueDark200 = ZypherpunkCyan.`800`,
                        utilityBlueDark800 = ZypherpunkCyan.`200`,
                        utilityBlueDark50 = ZypherpunkCyan.`950`,
                        utilityBlueDark100 = ZypherpunkCyan.`900`,
                        utilityBlueDark400 = ZypherpunkCyan.`600`,
                        utilityBlueDark300 = ZypherpunkCyan.`700`
                    ),
                Indigo =
                    UtilityIndigo(
                        utilityIndigo600 = ZypherpunkMagenta.`400`,
                        utilityIndigo700 = ZypherpunkBase.MagentaGlow,
                        utilityIndigo500 = ZypherpunkMagenta.`500`,
                        utilityIndigo200 = ZypherpunkMagenta.`800`,
                        utilityIndigo800 = ZypherpunkMagenta.`200`,
                        utilityIndigo50 = ZypherpunkMagenta.`950`,
                        utilityIndigo100 = ZypherpunkMagenta.`900`,
                        utilityIndigo400 = ZypherpunkMagenta.`600`,
                        utilityIndigo300 = ZypherpunkMagenta.`700`
                    ),
                Purple =
                    UtilityPurple(
                        utilityPurple600 = ZypherpunkMagenta.`400`,
                        utilityPurple700 = ZypherpunkMagenta.`300`,
                        utilityPurple500 = ZypherpunkMagenta.`500`,
                        utilityPurple200 = ZypherpunkMagenta.`800`,
                        utilityPurple800 = ZypherpunkMagenta.`200`,
                        utilityPurple50 = ZypherpunkMagenta.`950`,
                        utilityPurple100 = ZypherpunkMagenta.`900`,
                        utilityPurple400 = ZypherpunkMagenta.`600`,
                        utilityPurple300 = ZypherpunkMagenta.`700`,
                        utilityPurple900 = ZypherpunkMagenta.`100`
                    ),
                Espresso =
                    UtilityEspresso(
                        utilityEspresso700 = ZypherpunkPurple.`200`,
                        utilityEspresso600 = ZypherpunkPurple.`300`,
                        utilityEspresso500 = ZypherpunkPurple.`400`,
                        utilityEspresso200 = ZypherpunkPurple.`700`,
                        utilityEspresso50 = ZypherpunkPurple.`950`,
                        utilityEspresso100 = ZypherpunkPurple.`900`,
                        utilityEspresso400 = ZypherpunkPurple.`500`,
                        utilityEspresso300 = ZypherpunkPurple.`600`,
                        utilityEspresso800 = ZypherpunkPurple.`100`,
                        utilityEspresso900 = ZypherpunkPurple.`50`,
                        utilityEspresso950 = ZypherpunkBase.Text
                    )
            ),
        Transparent =
            Transparent(
                bgPrimary = TransparentColorPalette.Dark
            ),
        NoTheme = NoTheme(welcomeText = ZypherpunkCyan.`400`)
    )
