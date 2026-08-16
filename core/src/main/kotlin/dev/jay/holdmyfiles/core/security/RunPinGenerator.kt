package dev.jay.holdmyfiles.core.security

class RunPinGenerator(
    private val random: RandomNumberSource = SecureRandomNumberSource(),
) {
    fun generate(): String = random.nextInt(PIN_SPACE).toString().padStart(PIN_LENGTH, '0')

    private companion object {
        const val PIN_LENGTH = 6
        const val PIN_SPACE = 1_000_000
    }
}
