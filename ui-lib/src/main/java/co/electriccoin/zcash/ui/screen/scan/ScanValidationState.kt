package co.electriccoin.zcash.ui.screen.scan

enum class ScanValidationState {
    NONE,
    INVALID,
    VALID,

    /** QR-7: the picture chosen from the gallery holds no QR code the app can read. */
    NO_CODE_IN_PICTURE,

    /** QR-7: the picture holds codes that could name different people; nothing was used. */
    MANY_CODES_IN_PICTURE
}
