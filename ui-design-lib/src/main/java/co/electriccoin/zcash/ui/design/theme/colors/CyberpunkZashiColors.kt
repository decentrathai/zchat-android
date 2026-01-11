package co.electriccoin.zcash.ui.design.theme.colors

/**
 * Cyberpunk theme colors - neon cyan/magenta on deep purple background
 */
val CyberpunkZashiColorsInternal =
    ZashiColorsInternal(
        Surfaces =
            Surfaces(
                bgPrimary = CyberpunkBase.Background,
                bgAdjust = CyberpunkShades.`06dp`,
                bgSecondary = CyberpunkShades.`06dp`,
                bgTertiary = CyberpunkShades.`08dp`,
                bgQuaternary = CyberpunkPurple.`600`,
                strokePrimary = CyberpunkCyan.`700`,
                strokeSecondary = CyberpunkPurple.`700`,
                bgAlt = CyberpunkBase.Text,
                bgHide = CyberpunkBase.Background,
                brandBg = CyberpunkBase.Cyan,
                brandFg = CyberpunkBase.Background,
                divider = CyberpunkPurple.`700`
            ),
        Text =
            Text(
                textPrimary = CyberpunkBase.Text,
                textSecondary = CyberpunkBase.TextSecondary,
                textTertiary = CyberpunkBase.TextSecondary,
                textQuaternary = CyberpunkPurple.`300`,
                textSupport = CyberpunkPurple.`400`,
                textDisabled = CyberpunkPurple.`600`,
                textError = CyberpunkMagenta.`300`,
                textLink = CyberpunkCyan.`400`,
                textLight = CyberpunkBase.Text,
                textLightSupport = CyberpunkBase.TextSecondary
            ),
        Btns =
            Btns(
                Brand =
                    BtnBrand(
                        btnBrandBg = CyberpunkCyan.`400`,
                        btnBrandBgHover = CyberpunkCyan.`300`,
                        btnBrandFg = CyberpunkBase.Background,
                        btnBrandFgHover = CyberpunkBase.Background,
                        btnBrandBgDisabled = CyberpunkPurple.`700`,
                        btnBrandFgDisabled = CyberpunkPurple.`500`
                    ),
                Secondary =
                    BtnSecondary(
                        btnSecondaryBg = CyberpunkBase.Background,
                        btnSecondaryBgHover = CyberpunkShades.`04dp`,
                        btnSecondaryFg = CyberpunkCyan.`400`,
                        btnSecondaryFgHover = CyberpunkCyan.`300`,
                        btnSecondaryBorder = CyberpunkCyan.`700`,
                        btnSecondaryBorderHover = CyberpunkCyan.`500`,
                        btnSecondaryBgDisabled = CyberpunkPurple.`800`,
                        btnSecondaryFgDisabled = CyberpunkPurple.`500`
                    ),
                Tertiary =
                    BtnTertiary(
                        btnTertiaryBg = CyberpunkShades.`06dp`,
                        btnTertiaryBgHover = CyberpunkShades.`08dp`,
                        btnTertiaryFg = CyberpunkCyan.`300`,
                        btnTertiaryFgHover = CyberpunkCyan.`200`,
                        btnTertiaryBgDisabled = CyberpunkPurple.`800`,
                        btnTertiaryFgDisabled = CyberpunkPurple.`500`
                    ),
                Quaternary =
                    BtnQuaternary(
                        btnQuartBg = CyberpunkPurple.`600`,
                        btnQuartBgHover = CyberpunkPurple.`500`,
                        btnQuartFg = CyberpunkBase.Text,
                        btnQuartFgHover = CyberpunkBase.Text,
                        btnQuartBgDisabled = CyberpunkPurple.`800`,
                        btnQuartFgDisabled = CyberpunkPurple.`500`
                    ),
                Destructive1 =
                    BtnDestructive1(
                        btnDestroy1Bg = CyberpunkMagenta.`950`,
                        btnDestroy1BgHover = CyberpunkMagenta.`900`,
                        btnDestroy1Fg = CyberpunkMagenta.`100`,
                        btnDestroy1FgHover = CyberpunkMagenta.`50`,
                        btnDestroy1Border = CyberpunkMagenta.`700`,
                        btnDestroy1BorderHover = CyberpunkMagenta.`600`,
                        btnDestroy1BgDisabled = CyberpunkPurple.`800`,
                        btnDestroy1FgDisabled = CyberpunkPurple.`500`
                    ),
                Destructive2 =
                    BtnDestructive2(
                        btnDestroy2Bg = CyberpunkMagenta.`500`,
                        btnDestroy2BgHover = CyberpunkMagenta.`600`,
                        btnDestroy2Fg = CyberpunkMagenta.`50`,
                        btnDestroy2BgDisabled = CyberpunkPurple.`800`,
                        btnDestroy2FgDisabled = CyberpunkPurple.`500`
                    ),
                Primary =
                    BtnPrimary(
                        btnPrimaryBg = CyberpunkCyan.`400`,
                        btnPrimaryBgHover = CyberpunkCyan.`300`,
                        btnPrimaryFg = CyberpunkBase.Background,
                        btnPrimaryBgDisabled = CyberpunkPurple.`800`,
                        btnBoldFgDisabled = CyberpunkPurple.`500`
                    ),
                Ghost =
                    BtnGhost(
                        btnGhostBg = CyberpunkBase.Background,
                        btnGhostBgHover = CyberpunkShades.`04dp`,
                        btnGhostFg = CyberpunkCyan.`400`,
                        btnGhostBgDisabled = CyberpunkPurple.`800`,
                        btnGhostFgDisabled = CyberpunkPurple.`500`
                    )
            ),
        Avatars =
            Avatars(
                avatarProfileBorder = CyberpunkCyan.`500`,
                avatarBg = CyberpunkPurple.`600`,
                avatarBgSecondary = CyberpunkPurple.`500`,
                avatarStatus = CyberpunkCyan.`400`,
                avatarTextFg = CyberpunkBase.Text,
                avatarBadgeBg = CyberpunkMagenta.`400`,
                avatarBadgeFg = CyberpunkBase.Background
            ),
        Sliders =
            Sliders(
                sliderHandleBorder = CyberpunkCyan.`500`,
                sliderHandleBg = CyberpunkBase.Background
            ),
        Inputs =
            Inputs(
                Default =
                    InputDefault(
                        bg = CyberpunkShades.`06dp`,
                        bgAlt = CyberpunkShades.`04dp`,
                        label = CyberpunkBase.Text,
                        text = CyberpunkPurple.`300`,
                        hint = CyberpunkPurple.`400`,
                        required = CyberpunkMagenta.`400`,
                        icon = CyberpunkCyan.`600`,
                        stroke = CyberpunkPurple.`600`
                    ),
                Hover =
                    InputHover(
                        bg = CyberpunkShades.`08dp`,
                        bgAlt = CyberpunkShades.`06dp`,
                        asideBg = CyberpunkShades.`06dp`,
                        stroke = CyberpunkCyan.`700`,
                        label = CyberpunkBase.Text,
                        text = CyberpunkBase.TextSecondary,
                        hint = CyberpunkPurple.`400`,
                        icon = CyberpunkCyan.`500`,
                        required = CyberpunkMagenta.`400`
                    ),
                Filled =
                    InputFilled(
                        bg = CyberpunkShades.`06dp`,
                        bgAlt = CyberpunkShades.`04dp`,
                        asideBg = CyberpunkShades.`06dp`,
                        stroke = CyberpunkCyan.`600`,
                        label = CyberpunkBase.Text,
                        text = CyberpunkBase.Text,
                        hint = CyberpunkPurple.`400`,
                        icon = CyberpunkCyan.`500`,
                        iconMain = CyberpunkCyan.`400`,
                        required = CyberpunkMagenta.`400`
                    ),
                Focused =
                    InputFocused(
                        bg = CyberpunkShades.`04dp`,
                        asideBg = CyberpunkShades.`06dp`,
                        stroke = CyberpunkCyan.`400`,
                        stroke2 = CyberpunkPurple.`600`,
                        label = CyberpunkBase.Text,
                        text = CyberpunkBase.Text,
                        hint = CyberpunkPurple.`400`,
                        icon = CyberpunkCyan.`400`,
                        iconMain = CyberpunkCyan.`300`,
                        defaultRequired = CyberpunkMagenta.`400`
                    ),
                Disabled =
                    InputDisabled(
                        bg = CyberpunkShades.`06dp`,
                        stroke = CyberpunkPurple.`700`,
                        label = CyberpunkPurple.`400`,
                        text = CyberpunkPurple.`500`,
                        hint = CyberpunkPurple.`600`,
                        icon = CyberpunkPurple.`600`,
                        iconMain = CyberpunkPurple.`600`,
                        required = CyberpunkMagenta.`700`
                    ),
                ErrorDefault =
                    InputErrorDefault(
                        bg = CyberpunkShades.`04dp`,
                        bgAlt = CyberpunkShades.`06dp`,
                        label = CyberpunkBase.Text,
                        text = CyberpunkPurple.`300`,
                        textAside = CyberpunkPurple.`400`,
                        textMain = CyberpunkBase.Text,
                        hint = CyberpunkMagenta.`400`,
                        icon = CyberpunkMagenta.`400`,
                        iconMain = CyberpunkMagenta.`500`,
                        stroke = CyberpunkMagenta.`400`,
                        strokeAlt = CyberpunkPurple.`600`,
                        dropdown = CyberpunkPurple.`500`
                    ),
                ErrorHover =
                    InputErrorHover(
                        bg = CyberpunkShades.`04dp`,
                        bgAlt = CyberpunkShades.`06dp`,
                        label = CyberpunkBase.Text,
                        text = CyberpunkBase.TextSecondary,
                        textAside = CyberpunkPurple.`400`,
                        textMain = CyberpunkBase.Text,
                        hint = CyberpunkMagenta.`400`,
                        icon = CyberpunkMagenta.`400`,
                        iconMain = CyberpunkMagenta.`500`,
                        stroke = CyberpunkMagenta.`500`,
                        strokeAlt = CyberpunkPurple.`600`,
                        dropdown = CyberpunkPurple.`500`
                    ),
                ErrorFilled =
                    InputErrorFilled(
                        bg = CyberpunkShades.`04dp`,
                        bgAlt = CyberpunkShades.`06dp`,
                        label = CyberpunkBase.Text,
                        text = CyberpunkBase.Text,
                        textAside = CyberpunkPurple.`400`,
                        hint = CyberpunkMagenta.`400`,
                        icon = CyberpunkMagenta.`400`,
                        iconMain = CyberpunkMagenta.`500`,
                        stroke = CyberpunkMagenta.`500`,
                        strokeAlt = CyberpunkPurple.`600`,
                        dropdown = CyberpunkPurple.`500`
                    ),
                ErrorFocused =
                    InputErrorFocused(
                        bg = CyberpunkShades.`04dp`,
                        bgAlt = CyberpunkShades.`06dp`,
                        label = CyberpunkBase.Text,
                        text = CyberpunkBase.Text,
                        textAside = CyberpunkPurple.`400`,
                        hint = CyberpunkMagenta.`400`,
                        icon = CyberpunkMagenta.`400`,
                        iconMain = CyberpunkMagenta.`500`,
                        stroke = CyberpunkMagenta.`400`,
                        strokeAlt = CyberpunkPurple.`600`,
                        dropdown = CyberpunkPurple.`500`
                    )
            ),
        Accordion =
            Accordion(
                xBtnDefaultFg = CyberpunkCyan.`400`,
                xBtnHoverBg = CyberpunkShades.`08dp`,
                xBtnOnHoverBg = CyberpunkShades.`08dp`,
                xBtnHoverFg = CyberpunkCyan.`300`,
                xBtnFocusBg = CyberpunkShades.`12dp`,
                xBtnFocusFg = CyberpunkCyan.`300`,
                xBtnFocusStroke = CyberpunkCyan.`500`,
                xBtnDisabledBg = CyberpunkPurple.`800`,
                xBtnDisabledFg = CyberpunkPurple.`600`,
                defaultBg = CyberpunkBase.Background,
                defaultStroke = CyberpunkPurple.`700`,
                defaultIcon = CyberpunkCyan.`600`,
                focusStroke = CyberpunkCyan.`500`,
                expandedBg = CyberpunkShades.`06dp`,
                expandedHoverBg = CyberpunkShades.`08dp`,
                expandedStroke = CyberpunkCyan.`700`,
                dividers = CyberpunkPurple.`600`,
                expandedFocusStroke = CyberpunkCyan.`500`
            ),
        Switcher =
            Switcher(
                defaultText = CyberpunkBase.TextSecondary,
                defaultTagBg = CyberpunkPurple.`600`,
                defaultIcon = CyberpunkCyan.`600`,
                hoverBg = CyberpunkShades.`08dp`,
                hoverTagBg = CyberpunkPurple.`500`,
                hoverIcon = CyberpunkCyan.`500`,
                hoverText = CyberpunkBase.Text,
                hoverTagText = CyberpunkBase.Text,
                selectedBg = CyberpunkCyan.`400`,
                selectedIcon = CyberpunkBase.Background,
                selectedText = CyberpunkBase.Background,
                selectedTagBg = CyberpunkCyan.`300`,
                selectedStroke = CyberpunkCyan.`500`,
                disabledText = CyberpunkPurple.`500`,
                disabledIcon = CyberpunkPurple.`600`,
                disabledTagBg = CyberpunkPurple.`700`,
                surfacePrimary = CyberpunkShades.`06dp`
            ),
        Toggles =
            Toggles(
                tgDefaultBg = CyberpunkPurple.`600`,
                tgDefaultFg = CyberpunkPurple.`400`,
                tgActiveBg = CyberpunkCyan.`400`,
                tgActiveFg = CyberpunkBase.Background,
                tgDefaultHoverBg = CyberpunkPurple.`500`,
                tgDefaultHoverFg = CyberpunkPurple.`300`,
                tgActiveHoverBg = CyberpunkCyan.`300`,
                tgActiveHoverFg = CyberpunkBase.Background,
                tgDefaultDisabledBg = CyberpunkPurple.`700`,
                tgDefaultDisabledFg = CyberpunkPurple.`500`,
                tgActiveDisabledBg = CyberpunkPurple.`700`,
                tgActiveDisabledFg = CyberpunkPurple.`500`
            ),
        Tags =
            Tags(
                tcDefaultFg = CyberpunkCyan.`500`,
                tcHoverBg = CyberpunkShades.`08dp`,
                tcHoverFg = CyberpunkCyan.`400`,
                tcCountBg = CyberpunkMagenta.`700`,
                tcCountFg = CyberpunkMagenta.`200`,
                statusIndicator = CyberpunkCyan.`400`,
                surfacePrimary = CyberpunkBase.Background,
                surfaceStroke = CyberpunkCyan.`700`
            ),
        Dropdowns =
            Dropdowns(
                Default =
                    DropdownDefault(
                        bg = CyberpunkShades.`06dp`,
                        label = CyberpunkBase.Text,
                        text = CyberpunkPurple.`300`,
                        hint = CyberpunkPurple.`400`,
                        required = CyberpunkMagenta.`400`,
                        icon = CyberpunkCyan.`600`,
                        dropdown = CyberpunkCyan.`500`,
                        active = CyberpunkCyan.`400`
                    ),
                Filled =
                    DropdownFilled(
                        bg = CyberpunkShades.`06dp`,
                        label = CyberpunkBase.Text,
                        textMain = CyberpunkBase.Text,
                        textSupport = CyberpunkBase.TextSecondary,
                        hint = CyberpunkPurple.`400`,
                        required = CyberpunkMagenta.`400`,
                        icon = CyberpunkCyan.`500`,
                        dropdown = CyberpunkCyan.`500`,
                        active = CyberpunkCyan.`400`
                    ),
                Focused =
                    DropdownFocused(
                        bg = CyberpunkShades.`04dp`,
                        stroke = CyberpunkCyan.`400`,
                        label = CyberpunkBase.Text,
                        textMain = CyberpunkBase.Text,
                        textSupport = CyberpunkBase.TextSecondary,
                        hint = CyberpunkPurple.`400`,
                        defaultRequired = CyberpunkMagenta.`400`,
                        icon = CyberpunkCyan.`400`,
                        dropdown = CyberpunkCyan.`400`,
                        active = CyberpunkCyan.`300`
                    ),
                Disabled =
                    DropdownDisabled(
                        bg = CyberpunkShades.`06dp`,
                        stroke = CyberpunkPurple.`700`,
                        label = CyberpunkPurple.`400`,
                        textMain = CyberpunkPurple.`500`,
                        textSupport = CyberpunkPurple.`600`,
                        hint = CyberpunkPurple.`600`,
                        required = CyberpunkMagenta.`700`,
                        icon = CyberpunkPurple.`600`,
                        dropdown = CyberpunkPurple.`600`,
                        active = CyberpunkPurple.`500`
                    ),
                Parts =
                    DropdownParts(
                        scrollBar = CyberpunkCyan.`700`,
                        divider = CyberpunkPurple.`600`,
                        lhText = CyberpunkBase.TextSecondary,
                        lhBorder = CyberpunkPurple.`600`,
                        liTextPrimary = CyberpunkBase.Text,
                        liTextSecondary = CyberpunkBase.TextSecondary,
                        liTextTertiary = CyberpunkPurple.`400`,
                        liFgDisabled = CyberpunkPurple.`500`,
                        liIconDisabled = CyberpunkPurple.`600`,
                        liBgHover = CyberpunkShades.`08dp`,
                        statusActive = CyberpunkCyan.`400`,
                        statusMain = CyberpunkCyan.`500`,
                        statusDisabled = CyberpunkPurple.`600`,
                        bgDisabled = CyberpunkShades.`06dp`
                    )
            ),
        Tabs =
            Tabs(
                defaultText = CyberpunkPurple.`300`,
                defaultIcon = CyberpunkCyan.`600`,
                defaultTagBg = CyberpunkPurple.`700`,
                hoverText = CyberpunkBase.TextSecondary,
                hoverTagText = CyberpunkBase.TextSecondary,
                hoverIcon = CyberpunkCyan.`500`,
                hoverTagBg = CyberpunkPurple.`500`,
                hoverBorder = CyberpunkCyan.`600`,
                selectedText = CyberpunkCyan.`400`,
                selectedIcon = CyberpunkCyan.`400`,
                selectedTagBg = CyberpunkCyan.`700`,
                selectedBorder = CyberpunkCyan.`400`,
                disabledText = CyberpunkPurple.`500`,
                disabledIcon = CyberpunkPurple.`600`,
                disabledTagBg = CyberpunkPurple.`800`,
                disabledTagText = CyberpunkPurple.`500`
            ),
        Checkboxes =
            Checkboxes(
                boxOffBg = CyberpunkShades.`06dp`,
                boxOffStroke = CyberpunkCyan.`600`,
                boxOffHoverBg = CyberpunkShades.`08dp`,
                boxOffHoverStroke = CyberpunkCyan.`500`,
                boxOffDisabledBg = CyberpunkPurple.`700`,
                boxOffDisabledStroke = CyberpunkPurple.`600`,
                boxOnBg = CyberpunkCyan.`400`,
                boxOnFg = CyberpunkBase.Background,
                boxOnHoverBg = CyberpunkCyan.`300`,
                boxOnDisabledBg = CyberpunkPurple.`700`,
                boxOnDisabledStroke = CyberpunkPurple.`600`,
                boxOnDisabledFg = CyberpunkPurple.`500`
            ),
        Loading =
            Loading(
                loadingBgPrimary = CyberpunkBase.Background,
                loadingBgSecondary = CyberpunkShades.`08dp`,
                loadingFgPrimary = CyberpunkCyan.`400`
            ),
        Modals =
            Modals(
                defaultBg = CyberpunkBase.Background,
                defaultFg = CyberpunkCyan.`400`,
                hoverBg = CyberpunkShades.`08dp`,
                hoverFg = CyberpunkCyan.`300`,
                focusedBg = CyberpunkShades.`12dp`,
                focusedStroke = CyberpunkCyan.`500`,
                disabledBg = CyberpunkPurple.`800`,
                disabledFg = CyberpunkPurple.`600`,
                surfacePrimary = CyberpunkShades.`04dp`,
                surfaceStroke = CyberpunkPurple.`600`
            ),
        HintTooltips =
            HintTooltips(
                surfacePrimary = CyberpunkShades.`08dp`,
                defaultBg = CyberpunkShades.`08dp`,
                defaultFg = CyberpunkBase.Text,
                hoverBg = CyberpunkShades.`12dp`,
                hoverFg = CyberpunkBase.Text,
                focusedBg = CyberpunkShades.`12dp`,
                focusedStroke = CyberpunkCyan.`500`,
                disabledBg = CyberpunkPurple.`700`,
                disabledFg = CyberpunkPurple.`500`
            ),
        TwoFA =
            TwoFA(
                defaultBg = CyberpunkShades.`08dp`,
                defaultStroke = CyberpunkPurple.`600`,
                defaultText = CyberpunkPurple.`600`,
                focusedBg = CyberpunkShades.`06dp`,
                focusedStroke = CyberpunkCyan.`400`,
                focusedText = CyberpunkCyan.`400`,
                filledBg = CyberpunkShades.`06dp`,
                filledStroke = CyberpunkCyan.`600`,
                filledText = CyberpunkBase.Text,
                disabledBg = CyberpunkPurple.`800`,
                disabledText = CyberpunkPurple.`600`,
                separatorDash = CyberpunkCyan.`700`
            ),
        Utility =
            Utility(
                Gray =
                    UtilityGray(
                        utilityGray700 = CyberpunkPurple.`200`,
                        utilityGray600 = CyberpunkPurple.`300`,
                        utilityGray500 = CyberpunkPurple.`400`,
                        utilityGray200 = CyberpunkPurple.`700`,
                        utilityGray50 = CyberpunkPurple.`900`,
                        utilityGray100 = CyberpunkPurple.`800`,
                        utilityGray400 = CyberpunkPurple.`500`,
                        utilityGray300 = CyberpunkPurple.`600`,
                        utilityGray900 = CyberpunkBase.Text,
                        utilityGray800 = CyberpunkBase.TextSecondary
                    ),
                SuccessGreen =
                    UtilitySuccessGreen(
                        utilitySuccess600 = CyberpunkCyan.`400`,
                        utilitySuccess700 = CyberpunkCyan.`300`,
                        utilitySuccess500 = CyberpunkCyan.`500`,
                        utilitySuccess200 = CyberpunkCyan.`800`,
                        utilitySuccess800 = CyberpunkCyan.`200`,
                        utilitySuccess50 = CyberpunkCyan.`950`,
                        utilitySuccess100 = CyberpunkCyan.`900`,
                        utilitySuccess400 = CyberpunkCyan.`600`,
                        utilitySuccess300 = CyberpunkCyan.`700`
                    ),
                ErrorRed =
                    UtilityErrorRed(
                        utilityError600 = CyberpunkMagenta.`400`,
                        utilityError700 = CyberpunkMagenta.`300`,
                        utilityError500 = CyberpunkMagenta.`500`,
                        utilityError200 = CyberpunkMagenta.`800`,
                        utilityError800 = CyberpunkMagenta.`200`,
                        utilityError50 = CyberpunkMagenta.`950`,
                        utilityError100 = CyberpunkMagenta.`900`,
                        utilityError400 = CyberpunkMagenta.`600`,
                        utilityError300 = CyberpunkMagenta.`700`
                    ),
                WarningYellow =
                    UtilityWarningYellow(
                        utilityOrange600 = CyberpunkMagenta.`400`,
                        utilityOrange700 = CyberpunkMagenta.`300`,
                        utilityOrange500 = CyberpunkMagenta.`500`,
                        utilityOrange200 = CyberpunkMagenta.`800`,
                        utilityOrange800 = CyberpunkMagenta.`200`,
                        utilityOrange50 = CyberpunkMagenta.`950`,
                        utilityOrange100 = CyberpunkMagenta.`900`,
                        utilityOrange400 = CyberpunkMagenta.`600`,
                        utilityOrange300 = CyberpunkMagenta.`700`
                    ),
                HyperBlue =
                    UtilityHyperBlue(
                        utilityBlueDark600 = CyberpunkCyan.`400`,
                        utilityBlueDark700 = CyberpunkCyan.`300`,
                        utilityBlueDark500 = CyberpunkCyan.`500`,
                        utilityBlueDark200 = CyberpunkCyan.`800`,
                        utilityBlueDark800 = CyberpunkCyan.`200`,
                        utilityBlueDark50 = CyberpunkCyan.`950`,
                        utilityBlueDark100 = CyberpunkCyan.`900`,
                        utilityBlueDark400 = CyberpunkCyan.`600`,
                        utilityBlueDark300 = CyberpunkCyan.`700`
                    ),
                Indigo =
                    UtilityIndigo(
                        utilityIndigo600 = CyberpunkMagenta.`400`,
                        utilityIndigo700 = CyberpunkMagenta.`300`,
                        utilityIndigo500 = CyberpunkMagenta.`500`,
                        utilityIndigo200 = CyberpunkMagenta.`800`,
                        utilityIndigo800 = CyberpunkMagenta.`200`,
                        utilityIndigo50 = CyberpunkMagenta.`950`,
                        utilityIndigo100 = CyberpunkMagenta.`900`,
                        utilityIndigo400 = CyberpunkMagenta.`600`,
                        utilityIndigo300 = CyberpunkMagenta.`700`
                    ),
                Purple =
                    UtilityPurple(
                        utilityPurple600 = CyberpunkMagenta.`400`,
                        utilityPurple700 = CyberpunkMagenta.`300`,
                        utilityPurple500 = CyberpunkMagenta.`500`,
                        utilityPurple200 = CyberpunkMagenta.`800`,
                        utilityPurple800 = CyberpunkMagenta.`200`,
                        utilityPurple50 = CyberpunkMagenta.`950`,
                        utilityPurple100 = CyberpunkMagenta.`900`,
                        utilityPurple400 = CyberpunkMagenta.`600`,
                        utilityPurple300 = CyberpunkMagenta.`700`,
                        utilityPurple900 = CyberpunkMagenta.`100`
                    ),
                Espresso =
                    UtilityEspresso(
                        utilityEspresso700 = CyberpunkPurple.`200`,
                        utilityEspresso600 = CyberpunkPurple.`300`,
                        utilityEspresso500 = CyberpunkPurple.`400`,
                        utilityEspresso200 = CyberpunkPurple.`700`,
                        utilityEspresso50 = CyberpunkPurple.`950`,
                        utilityEspresso100 = CyberpunkPurple.`900`,
                        utilityEspresso400 = CyberpunkPurple.`500`,
                        utilityEspresso300 = CyberpunkPurple.`600`,
                        utilityEspresso800 = CyberpunkPurple.`100`,
                        utilityEspresso900 = CyberpunkPurple.`50`,
                        utilityEspresso950 = CyberpunkBase.Text
                    )
            ),
        Transparent =
            Transparent(
                bgPrimary = TransparentColorPalette.Dark
            ),
        NoTheme = NoTheme(welcomeText = CyberpunkCyan.`400`)
    )
