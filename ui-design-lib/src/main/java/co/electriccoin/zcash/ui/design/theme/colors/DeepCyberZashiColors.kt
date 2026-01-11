package co.electriccoin.zcash.ui.design.theme.colors

/**
 * DEEP CYBER theme colors - Full cyberpunk experience with:
 * - Ultra-dark near-black backgrounds
 * - Intense neon cyan/magenta accents
 * - Circuit board aesthetics
 * - Transmission/matrix-style headers
 * - Enhanced glow effects
 */
val DeepCyberZashiColorsInternal =
    ZashiColorsInternal(
        Surfaces =
            Surfaces(
                bgPrimary = DeepCyberBase.Background,
                bgAdjust = DeepCyberShades.`06dp`,
                bgSecondary = DeepCyberShades.`06dp`,
                bgTertiary = DeepCyberShades.`08dp`,
                bgQuaternary = DeepCyberPurple.`600`,
                strokePrimary = DeepCyberCyan.`400`,
                strokeSecondary = DeepCyberPurple.`600`,
                bgAlt = DeepCyberBase.Text,
                bgHide = DeepCyberBase.Background,
                brandBg = DeepCyberBase.Cyan,
                brandFg = DeepCyberBase.Background,
                divider = DeepCyberPurple.`700`
            ),
        Text =
            Text(
                textPrimary = DeepCyberBase.Text,
                textSecondary = DeepCyberBase.TextSecondary,
                textTertiary = DeepCyberBase.TextSecondary,
                textQuaternary = DeepCyberPurple.`300`,
                textSupport = DeepCyberPurple.`400`,
                textDisabled = DeepCyberPurple.`600`,
                textError = DeepCyberMagenta.`300`,
                textLink = DeepCyberCyan.`400`,
                textLight = DeepCyberBase.Text,
                textLightSupport = DeepCyberBase.TextSecondary
            ),
        Btns =
            Btns(
                Brand =
                    BtnBrand(
                        btnBrandBg = DeepCyberCyan.`400`,
                        btnBrandBgHover = DeepCyberBase.CyanGlow,
                        btnBrandFg = DeepCyberBase.Background,
                        btnBrandFgHover = DeepCyberBase.Background,
                        btnBrandBgDisabled = DeepCyberPurple.`700`,
                        btnBrandFgDisabled = DeepCyberPurple.`500`
                    ),
                Secondary =
                    BtnSecondary(
                        btnSecondaryBg = DeepCyberBase.Background,
                        btnSecondaryBgHover = DeepCyberShades.`04dp`,
                        btnSecondaryFg = DeepCyberCyan.`400`,
                        btnSecondaryFgHover = DeepCyberBase.CyanGlow,
                        btnSecondaryBorder = DeepCyberCyan.`600`,
                        btnSecondaryBorderHover = DeepCyberCyan.`400`,
                        btnSecondaryBgDisabled = DeepCyberPurple.`800`,
                        btnSecondaryFgDisabled = DeepCyberPurple.`500`
                    ),
                Tertiary =
                    BtnTertiary(
                        btnTertiaryBg = DeepCyberShades.`06dp`,
                        btnTertiaryBgHover = DeepCyberShades.`08dp`,
                        btnTertiaryFg = DeepCyberCyan.`300`,
                        btnTertiaryFgHover = DeepCyberBase.CyanGlow,
                        btnTertiaryBgDisabled = DeepCyberPurple.`800`,
                        btnTertiaryFgDisabled = DeepCyberPurple.`500`
                    ),
                Quaternary =
                    BtnQuaternary(
                        btnQuartBg = DeepCyberPurple.`600`,
                        btnQuartBgHover = DeepCyberPurple.`500`,
                        btnQuartFg = DeepCyberBase.Text,
                        btnQuartFgHover = DeepCyberBase.Text,
                        btnQuartBgDisabled = DeepCyberPurple.`800`,
                        btnQuartFgDisabled = DeepCyberPurple.`500`
                    ),
                Destructive1 =
                    BtnDestructive1(
                        btnDestroy1Bg = DeepCyberMagenta.`950`,
                        btnDestroy1BgHover = DeepCyberMagenta.`900`,
                        btnDestroy1Fg = DeepCyberMagenta.`100`,
                        btnDestroy1FgHover = DeepCyberMagenta.`50`,
                        btnDestroy1Border = DeepCyberMagenta.`600`,
                        btnDestroy1BorderHover = DeepCyberMagenta.`500`,
                        btnDestroy1BgDisabled = DeepCyberPurple.`800`,
                        btnDestroy1FgDisabled = DeepCyberPurple.`500`
                    ),
                Destructive2 =
                    BtnDestructive2(
                        btnDestroy2Bg = DeepCyberMagenta.`500`,
                        btnDestroy2BgHover = DeepCyberMagenta.`600`,
                        btnDestroy2Fg = DeepCyberMagenta.`50`,
                        btnDestroy2BgDisabled = DeepCyberPurple.`800`,
                        btnDestroy2FgDisabled = DeepCyberPurple.`500`
                    ),
                Primary =
                    BtnPrimary(
                        btnPrimaryBg = DeepCyberCyan.`400`,
                        btnPrimaryBgHover = DeepCyberBase.CyanGlow,
                        btnPrimaryFg = DeepCyberBase.Background,
                        btnPrimaryBgDisabled = DeepCyberPurple.`800`,
                        btnBoldFgDisabled = DeepCyberPurple.`500`
                    ),
                Ghost =
                    BtnGhost(
                        btnGhostBg = DeepCyberBase.Background,
                        btnGhostBgHover = DeepCyberShades.`04dp`,
                        btnGhostFg = DeepCyberCyan.`400`,
                        btnGhostBgDisabled = DeepCyberPurple.`800`,
                        btnGhostFgDisabled = DeepCyberPurple.`500`
                    )
            ),
        Avatars =
            Avatars(
                avatarProfileBorder = DeepCyberCyan.`400`,
                avatarBg = DeepCyberPurple.`600`,
                avatarBgSecondary = DeepCyberPurple.`500`,
                avatarStatus = DeepCyberAccent.TransmissionGreen,
                avatarTextFg = DeepCyberBase.Text,
                avatarBadgeBg = DeepCyberMagenta.`400`,
                avatarBadgeFg = DeepCyberBase.Background
            ),
        Sliders =
            Sliders(
                sliderHandleBorder = DeepCyberCyan.`400`,
                sliderHandleBg = DeepCyberBase.Background
            ),
        Inputs =
            Inputs(
                Default =
                    InputDefault(
                        bg = DeepCyberShades.`06dp`,
                        bgAlt = DeepCyberShades.`04dp`,
                        label = DeepCyberBase.Text,
                        text = DeepCyberPurple.`300`,
                        hint = DeepCyberPurple.`400`,
                        required = DeepCyberMagenta.`400`,
                        icon = DeepCyberCyan.`500`,
                        stroke = DeepCyberPurple.`600`
                    ),
                Hover =
                    InputHover(
                        bg = DeepCyberShades.`08dp`,
                        bgAlt = DeepCyberShades.`06dp`,
                        asideBg = DeepCyberShades.`06dp`,
                        stroke = DeepCyberCyan.`600`,
                        label = DeepCyberBase.Text,
                        text = DeepCyberBase.TextSecondary,
                        hint = DeepCyberPurple.`400`,
                        icon = DeepCyberCyan.`400`,
                        required = DeepCyberMagenta.`400`
                    ),
                Filled =
                    InputFilled(
                        bg = DeepCyberShades.`06dp`,
                        bgAlt = DeepCyberShades.`04dp`,
                        asideBg = DeepCyberShades.`06dp`,
                        stroke = DeepCyberCyan.`500`,
                        label = DeepCyberBase.Text,
                        text = DeepCyberBase.Text,
                        hint = DeepCyberPurple.`400`,
                        icon = DeepCyberCyan.`400`,
                        iconMain = DeepCyberCyan.`400`,
                        required = DeepCyberMagenta.`400`
                    ),
                Focused =
                    InputFocused(
                        bg = DeepCyberShades.`04dp`,
                        asideBg = DeepCyberShades.`06dp`,
                        stroke = DeepCyberCyan.`400`,
                        stroke2 = DeepCyberPurple.`600`,
                        label = DeepCyberBase.Text,
                        text = DeepCyberBase.Text,
                        hint = DeepCyberPurple.`400`,
                        icon = DeepCyberCyan.`400`,
                        iconMain = DeepCyberBase.CyanGlow,
                        defaultRequired = DeepCyberMagenta.`400`
                    ),
                Disabled =
                    InputDisabled(
                        bg = DeepCyberShades.`06dp`,
                        stroke = DeepCyberPurple.`700`,
                        label = DeepCyberPurple.`400`,
                        text = DeepCyberPurple.`500`,
                        hint = DeepCyberPurple.`600`,
                        icon = DeepCyberPurple.`600`,
                        iconMain = DeepCyberPurple.`600`,
                        required = DeepCyberMagenta.`700`
                    ),
                ErrorDefault =
                    InputErrorDefault(
                        bg = DeepCyberShades.`04dp`,
                        bgAlt = DeepCyberShades.`06dp`,
                        label = DeepCyberBase.Text,
                        text = DeepCyberPurple.`300`,
                        textAside = DeepCyberPurple.`400`,
                        textMain = DeepCyberBase.Text,
                        hint = DeepCyberMagenta.`400`,
                        icon = DeepCyberMagenta.`400`,
                        iconMain = DeepCyberMagenta.`500`,
                        stroke = DeepCyberMagenta.`400`,
                        strokeAlt = DeepCyberPurple.`600`,
                        dropdown = DeepCyberPurple.`500`
                    ),
                ErrorHover =
                    InputErrorHover(
                        bg = DeepCyberShades.`04dp`,
                        bgAlt = DeepCyberShades.`06dp`,
                        label = DeepCyberBase.Text,
                        text = DeepCyberBase.TextSecondary,
                        textAside = DeepCyberPurple.`400`,
                        textMain = DeepCyberBase.Text,
                        hint = DeepCyberMagenta.`400`,
                        icon = DeepCyberMagenta.`400`,
                        iconMain = DeepCyberMagenta.`500`,
                        stroke = DeepCyberMagenta.`500`,
                        strokeAlt = DeepCyberPurple.`600`,
                        dropdown = DeepCyberPurple.`500`
                    ),
                ErrorFilled =
                    InputErrorFilled(
                        bg = DeepCyberShades.`04dp`,
                        bgAlt = DeepCyberShades.`06dp`,
                        label = DeepCyberBase.Text,
                        text = DeepCyberBase.Text,
                        textAside = DeepCyberPurple.`400`,
                        hint = DeepCyberMagenta.`400`,
                        icon = DeepCyberMagenta.`400`,
                        iconMain = DeepCyberMagenta.`500`,
                        stroke = DeepCyberMagenta.`500`,
                        strokeAlt = DeepCyberPurple.`600`,
                        dropdown = DeepCyberPurple.`500`
                    ),
                ErrorFocused =
                    InputErrorFocused(
                        bg = DeepCyberShades.`04dp`,
                        bgAlt = DeepCyberShades.`06dp`,
                        label = DeepCyberBase.Text,
                        text = DeepCyberBase.Text,
                        textAside = DeepCyberPurple.`400`,
                        hint = DeepCyberMagenta.`400`,
                        icon = DeepCyberMagenta.`400`,
                        iconMain = DeepCyberMagenta.`500`,
                        stroke = DeepCyberMagenta.`400`,
                        strokeAlt = DeepCyberPurple.`600`,
                        dropdown = DeepCyberPurple.`500`
                    )
            ),
        Accordion =
            Accordion(
                xBtnDefaultFg = DeepCyberCyan.`400`,
                xBtnHoverBg = DeepCyberShades.`08dp`,
                xBtnOnHoverBg = DeepCyberShades.`08dp`,
                xBtnHoverFg = DeepCyberBase.CyanGlow,
                xBtnFocusBg = DeepCyberShades.`12dp`,
                xBtnFocusFg = DeepCyberBase.CyanGlow,
                xBtnFocusStroke = DeepCyberCyan.`400`,
                xBtnDisabledBg = DeepCyberPurple.`800`,
                xBtnDisabledFg = DeepCyberPurple.`600`,
                defaultBg = DeepCyberBase.Background,
                defaultStroke = DeepCyberPurple.`700`,
                defaultIcon = DeepCyberCyan.`500`,
                focusStroke = DeepCyberCyan.`400`,
                expandedBg = DeepCyberShades.`06dp`,
                expandedHoverBg = DeepCyberShades.`08dp`,
                expandedStroke = DeepCyberCyan.`600`,
                dividers = DeepCyberPurple.`600`,
                expandedFocusStroke = DeepCyberCyan.`400`
            ),
        Switcher =
            Switcher(
                defaultText = DeepCyberBase.TextSecondary,
                defaultTagBg = DeepCyberPurple.`600`,
                defaultIcon = DeepCyberCyan.`500`,
                hoverBg = DeepCyberShades.`08dp`,
                hoverTagBg = DeepCyberPurple.`500`,
                hoverIcon = DeepCyberCyan.`400`,
                hoverText = DeepCyberBase.Text,
                hoverTagText = DeepCyberBase.Text,
                selectedBg = DeepCyberCyan.`400`,
                selectedIcon = DeepCyberBase.Background,
                selectedText = DeepCyberBase.Background,
                selectedTagBg = DeepCyberBase.CyanGlow,
                selectedStroke = DeepCyberCyan.`400`,
                disabledText = DeepCyberPurple.`500`,
                disabledIcon = DeepCyberPurple.`600`,
                disabledTagBg = DeepCyberPurple.`700`,
                surfacePrimary = DeepCyberShades.`06dp`
            ),
        Toggles =
            Toggles(
                tgDefaultBg = DeepCyberPurple.`600`,
                tgDefaultFg = DeepCyberPurple.`400`,
                tgActiveBg = DeepCyberCyan.`400`,
                tgActiveFg = DeepCyberBase.Background,
                tgDefaultHoverBg = DeepCyberPurple.`500`,
                tgDefaultHoverFg = DeepCyberPurple.`300`,
                tgActiveHoverBg = DeepCyberBase.CyanGlow,
                tgActiveHoverFg = DeepCyberBase.Background,
                tgDefaultDisabledBg = DeepCyberPurple.`700`,
                tgDefaultDisabledFg = DeepCyberPurple.`500`,
                tgActiveDisabledBg = DeepCyberPurple.`700`,
                tgActiveDisabledFg = DeepCyberPurple.`500`
            ),
        Tags =
            Tags(
                tcDefaultFg = DeepCyberCyan.`400`,
                tcHoverBg = DeepCyberShades.`08dp`,
                tcHoverFg = DeepCyberBase.CyanGlow,
                tcCountBg = DeepCyberMagenta.`600`,
                tcCountFg = DeepCyberMagenta.`100`,
                statusIndicator = DeepCyberAccent.TransmissionGreen,
                surfacePrimary = DeepCyberBase.Background,
                surfaceStroke = DeepCyberCyan.`600`
            ),
        Dropdowns =
            Dropdowns(
                Default =
                    DropdownDefault(
                        bg = DeepCyberShades.`06dp`,
                        label = DeepCyberBase.Text,
                        text = DeepCyberPurple.`300`,
                        hint = DeepCyberPurple.`400`,
                        required = DeepCyberMagenta.`400`,
                        icon = DeepCyberCyan.`500`,
                        dropdown = DeepCyberCyan.`400`,
                        active = DeepCyberCyan.`400`
                    ),
                Filled =
                    DropdownFilled(
                        bg = DeepCyberShades.`06dp`,
                        label = DeepCyberBase.Text,
                        textMain = DeepCyberBase.Text,
                        textSupport = DeepCyberBase.TextSecondary,
                        hint = DeepCyberPurple.`400`,
                        required = DeepCyberMagenta.`400`,
                        icon = DeepCyberCyan.`400`,
                        dropdown = DeepCyberCyan.`400`,
                        active = DeepCyberCyan.`400`
                    ),
                Focused =
                    DropdownFocused(
                        bg = DeepCyberShades.`04dp`,
                        stroke = DeepCyberCyan.`400`,
                        label = DeepCyberBase.Text,
                        textMain = DeepCyberBase.Text,
                        textSupport = DeepCyberBase.TextSecondary,
                        hint = DeepCyberPurple.`400`,
                        defaultRequired = DeepCyberMagenta.`400`,
                        icon = DeepCyberCyan.`400`,
                        dropdown = DeepCyberCyan.`400`,
                        active = DeepCyberBase.CyanGlow
                    ),
                Disabled =
                    DropdownDisabled(
                        bg = DeepCyberShades.`06dp`,
                        stroke = DeepCyberPurple.`700`,
                        label = DeepCyberPurple.`400`,
                        textMain = DeepCyberPurple.`500`,
                        textSupport = DeepCyberPurple.`600`,
                        hint = DeepCyberPurple.`600`,
                        required = DeepCyberMagenta.`700`,
                        icon = DeepCyberPurple.`600`,
                        dropdown = DeepCyberPurple.`600`,
                        active = DeepCyberPurple.`500`
                    ),
                Parts =
                    DropdownParts(
                        scrollBar = DeepCyberCyan.`600`,
                        divider = DeepCyberPurple.`600`,
                        lhText = DeepCyberBase.TextSecondary,
                        lhBorder = DeepCyberPurple.`600`,
                        liTextPrimary = DeepCyberBase.Text,
                        liTextSecondary = DeepCyberBase.TextSecondary,
                        liTextTertiary = DeepCyberPurple.`400`,
                        liFgDisabled = DeepCyberPurple.`500`,
                        liIconDisabled = DeepCyberPurple.`600`,
                        liBgHover = DeepCyberShades.`08dp`,
                        statusActive = DeepCyberCyan.`400`,
                        statusMain = DeepCyberCyan.`400`,
                        statusDisabled = DeepCyberPurple.`600`,
                        bgDisabled = DeepCyberShades.`06dp`
                    )
            ),
        Tabs =
            Tabs(
                defaultText = DeepCyberPurple.`300`,
                defaultIcon = DeepCyberCyan.`500`,
                defaultTagBg = DeepCyberPurple.`700`,
                hoverText = DeepCyberBase.TextSecondary,
                hoverTagText = DeepCyberBase.TextSecondary,
                hoverIcon = DeepCyberCyan.`400`,
                hoverTagBg = DeepCyberPurple.`500`,
                hoverBorder = DeepCyberCyan.`500`,
                selectedText = DeepCyberCyan.`400`,
                selectedIcon = DeepCyberCyan.`400`,
                selectedTagBg = DeepCyberCyan.`600`,
                selectedBorder = DeepCyberCyan.`400`,
                disabledText = DeepCyberPurple.`500`,
                disabledIcon = DeepCyberPurple.`600`,
                disabledTagBg = DeepCyberPurple.`800`,
                disabledTagText = DeepCyberPurple.`500`
            ),
        Checkboxes =
            Checkboxes(
                boxOffBg = DeepCyberShades.`06dp`,
                boxOffStroke = DeepCyberCyan.`500`,
                boxOffHoverBg = DeepCyberShades.`08dp`,
                boxOffHoverStroke = DeepCyberCyan.`400`,
                boxOffDisabledBg = DeepCyberPurple.`700`,
                boxOffDisabledStroke = DeepCyberPurple.`600`,
                boxOnBg = DeepCyberCyan.`400`,
                boxOnFg = DeepCyberBase.Background,
                boxOnHoverBg = DeepCyberBase.CyanGlow,
                boxOnDisabledBg = DeepCyberPurple.`700`,
                boxOnDisabledStroke = DeepCyberPurple.`600`,
                boxOnDisabledFg = DeepCyberPurple.`500`
            ),
        Loading =
            Loading(
                loadingBgPrimary = DeepCyberBase.Background,
                loadingBgSecondary = DeepCyberShades.`08dp`,
                loadingFgPrimary = DeepCyberCyan.`400`
            ),
        Modals =
            Modals(
                defaultBg = DeepCyberBase.Background,
                defaultFg = DeepCyberCyan.`400`,
                hoverBg = DeepCyberShades.`08dp`,
                hoverFg = DeepCyberBase.CyanGlow,
                focusedBg = DeepCyberShades.`12dp`,
                focusedStroke = DeepCyberCyan.`400`,
                disabledBg = DeepCyberPurple.`800`,
                disabledFg = DeepCyberPurple.`600`,
                surfacePrimary = DeepCyberShades.`04dp`,
                surfaceStroke = DeepCyberPurple.`600`
            ),
        HintTooltips =
            HintTooltips(
                surfacePrimary = DeepCyberShades.`08dp`,
                defaultBg = DeepCyberShades.`08dp`,
                defaultFg = DeepCyberBase.Text,
                hoverBg = DeepCyberShades.`12dp`,
                hoverFg = DeepCyberBase.Text,
                focusedBg = DeepCyberShades.`12dp`,
                focusedStroke = DeepCyberCyan.`400`,
                disabledBg = DeepCyberPurple.`700`,
                disabledFg = DeepCyberPurple.`500`
            ),
        TwoFA =
            TwoFA(
                defaultBg = DeepCyberShades.`08dp`,
                defaultStroke = DeepCyberPurple.`600`,
                defaultText = DeepCyberPurple.`600`,
                focusedBg = DeepCyberShades.`06dp`,
                focusedStroke = DeepCyberCyan.`400`,
                focusedText = DeepCyberCyan.`400`,
                filledBg = DeepCyberShades.`06dp`,
                filledStroke = DeepCyberCyan.`500`,
                filledText = DeepCyberBase.Text,
                disabledBg = DeepCyberPurple.`800`,
                disabledText = DeepCyberPurple.`600`,
                separatorDash = DeepCyberCyan.`600`
            ),
        Utility =
            Utility(
                Gray =
                    UtilityGray(
                        utilityGray700 = DeepCyberPurple.`200`,
                        utilityGray600 = DeepCyberPurple.`300`,
                        utilityGray500 = DeepCyberPurple.`400`,
                        utilityGray200 = DeepCyberPurple.`700`,
                        utilityGray50 = DeepCyberPurple.`900`,
                        utilityGray100 = DeepCyberPurple.`800`,
                        utilityGray400 = DeepCyberPurple.`500`,
                        utilityGray300 = DeepCyberPurple.`600`,
                        utilityGray900 = DeepCyberBase.Text,
                        utilityGray800 = DeepCyberBase.TextSecondary
                    ),
                SuccessGreen =
                    UtilitySuccessGreen(
                        utilitySuccess600 = DeepCyberAccent.TransmissionGreen,
                        utilitySuccess700 = DeepCyberCyan.`300`,
                        utilitySuccess500 = DeepCyberCyan.`500`,
                        utilitySuccess200 = DeepCyberCyan.`800`,
                        utilitySuccess800 = DeepCyberCyan.`200`,
                        utilitySuccess50 = DeepCyberCyan.`950`,
                        utilitySuccess100 = DeepCyberCyan.`900`,
                        utilitySuccess400 = DeepCyberCyan.`600`,
                        utilitySuccess300 = DeepCyberCyan.`700`
                    ),
                ErrorRed =
                    UtilityErrorRed(
                        utilityError600 = DeepCyberMagenta.`400`,
                        utilityError700 = DeepCyberMagenta.`300`,
                        utilityError500 = DeepCyberMagenta.`500`,
                        utilityError200 = DeepCyberMagenta.`800`,
                        utilityError800 = DeepCyberMagenta.`200`,
                        utilityError50 = DeepCyberMagenta.`950`,
                        utilityError100 = DeepCyberMagenta.`900`,
                        utilityError400 = DeepCyberMagenta.`600`,
                        utilityError300 = DeepCyberMagenta.`700`
                    ),
                WarningYellow =
                    UtilityWarningYellow(
                        utilityOrange600 = DeepCyberAccent.NeonYellow,
                        utilityOrange700 = DeepCyberMagenta.`300`,
                        utilityOrange500 = DeepCyberMagenta.`500`,
                        utilityOrange200 = DeepCyberMagenta.`800`,
                        utilityOrange800 = DeepCyberMagenta.`200`,
                        utilityOrange50 = DeepCyberMagenta.`950`,
                        utilityOrange100 = DeepCyberMagenta.`900`,
                        utilityOrange400 = DeepCyberMagenta.`600`,
                        utilityOrange300 = DeepCyberMagenta.`700`
                    ),
                HyperBlue =
                    UtilityHyperBlue(
                        utilityBlueDark600 = DeepCyberCyan.`400`,
                        utilityBlueDark700 = DeepCyberBase.CyanGlow,
                        utilityBlueDark500 = DeepCyberCyan.`500`,
                        utilityBlueDark200 = DeepCyberCyan.`800`,
                        utilityBlueDark800 = DeepCyberCyan.`200`,
                        utilityBlueDark50 = DeepCyberCyan.`950`,
                        utilityBlueDark100 = DeepCyberCyan.`900`,
                        utilityBlueDark400 = DeepCyberCyan.`600`,
                        utilityBlueDark300 = DeepCyberCyan.`700`
                    ),
                Indigo =
                    UtilityIndigo(
                        utilityIndigo600 = DeepCyberMagenta.`400`,
                        utilityIndigo700 = DeepCyberBase.MagentaGlow,
                        utilityIndigo500 = DeepCyberMagenta.`500`,
                        utilityIndigo200 = DeepCyberMagenta.`800`,
                        utilityIndigo800 = DeepCyberMagenta.`200`,
                        utilityIndigo50 = DeepCyberMagenta.`950`,
                        utilityIndigo100 = DeepCyberMagenta.`900`,
                        utilityIndigo400 = DeepCyberMagenta.`600`,
                        utilityIndigo300 = DeepCyberMagenta.`700`
                    ),
                Purple =
                    UtilityPurple(
                        utilityPurple600 = DeepCyberMagenta.`400`,
                        utilityPurple700 = DeepCyberMagenta.`300`,
                        utilityPurple500 = DeepCyberMagenta.`500`,
                        utilityPurple200 = DeepCyberMagenta.`800`,
                        utilityPurple800 = DeepCyberMagenta.`200`,
                        utilityPurple50 = DeepCyberMagenta.`950`,
                        utilityPurple100 = DeepCyberMagenta.`900`,
                        utilityPurple400 = DeepCyberMagenta.`600`,
                        utilityPurple300 = DeepCyberMagenta.`700`,
                        utilityPurple900 = DeepCyberMagenta.`100`
                    ),
                Espresso =
                    UtilityEspresso(
                        utilityEspresso700 = DeepCyberPurple.`200`,
                        utilityEspresso600 = DeepCyberPurple.`300`,
                        utilityEspresso500 = DeepCyberPurple.`400`,
                        utilityEspresso200 = DeepCyberPurple.`700`,
                        utilityEspresso50 = DeepCyberPurple.`950`,
                        utilityEspresso100 = DeepCyberPurple.`900`,
                        utilityEspresso400 = DeepCyberPurple.`500`,
                        utilityEspresso300 = DeepCyberPurple.`600`,
                        utilityEspresso800 = DeepCyberPurple.`100`,
                        utilityEspresso900 = DeepCyberPurple.`50`,
                        utilityEspresso950 = DeepCyberBase.Text
                    )
            ),
        Transparent =
            Transparent(
                bgPrimary = TransparentColorPalette.Dark
            ),
        NoTheme = NoTheme(welcomeText = DeepCyberCyan.`400`)
    )
